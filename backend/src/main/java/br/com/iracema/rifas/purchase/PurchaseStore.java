package br.com.iracema.rifas.purchase;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.com.iracema.rifas.payment.MercadoPagoClient.PixPayment;
import br.com.iracema.rifas.payment.MercadoPagoClient.PixPaymentStatus;

@Repository
public class PurchaseStore {

	private final JdbcTemplate jdbcTemplate;
	private final String buyerEmail;

	public PurchaseStore(JdbcTemplate jdbcTemplate, Validator validator,
			@Value("${app.pix.buyer-email:rifas@example.com}") String buyerEmail) {
		this.jdbcTemplate = jdbcTemplate;
		this.buyerEmail = buyerEmail.trim().toLowerCase(Locale.ROOT);
		if (!validator.validate(new BuyerEmail(this.buyerEmail)).isEmpty()) {
			throw new IllegalArgumentException("PIX_BUYER_EMAIL deve conter um e-mail válido.");
		}
	}

	@Transactional
	public ReservedPurchase reserve(PurchaseRequest request, Instant expiresAt) {
		RafflePrice raffle = jdbcTemplate.queryForObject("""
				SELECT id, unit_price_cents
				FROM raffle
				WHERE slug = 'iracema' AND status = 'ACTIVE'
				""", (result, rowNumber) -> new RafflePrice(
					result.getLong("id"), result.getObject("unit_price_cents", Integer.class)));

		if (raffle == null || raffle.unitPriceCents() == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "O preço da rifa ainda não foi definido.");
		}

		List<Integer> requestedNumbers = request.numbers().stream().sorted().toList();
		if (requestedNumbers.stream().distinct().count() != requestedNumbers.size()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remova os números repetidos da seleção.");
		}

		String placeholders = String.join(",", java.util.Collections.nCopies(requestedNumbers.size(), "?"));
		List<Object> parameters = new ArrayList<>();
		parameters.add(raffle.id());
		parameters.addAll(requestedNumbers);
		List<NumberReservation> available = jdbcTemplate.query("""
				SELECT id, number_value
				FROM raffle_number
				WHERE raffle_id = ? AND number_value IN (""" + placeholders + ") AND status = 'AVAILABLE' ORDER BY number_value FOR UPDATE",
				(result, rowNumber) -> new NumberReservation(result.getLong("id"), result.getInt("number_value")),
				parameters.toArray());

		if (available.size() != requestedNumbers.size()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Um ou mais números já foram reservados ou vendidos.");
		}

		int totalCents = Math.multiplyExact(raffle.unitPriceCents(), requestedNumbers.size());
		UUID purchaseId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO purchase (
					id, raffle_id, buyer_name, buyer_email, buyer_phone, total_cents, status, expires_at
				) VALUES (?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT', ?)
				""", purchaseId, raffle.id(), request.name().trim(), buyerEmail,
				request.phone().trim(), totalCents, Timestamp.from(expiresAt));

		for (NumberReservation number : available) {
			jdbcTemplate.update("INSERT INTO purchase_number (purchase_id, raffle_number_id) VALUES (?, ?)", purchaseId, number.id());
		}

		String numberIds = String.join(",", java.util.Collections.nCopies(available.size(), "?"));
		List<Object> updateParameters = new ArrayList<>();
		updateParameters.add(purchaseId);
		updateParameters.add(Timestamp.from(expiresAt));
		available.forEach(number -> updateParameters.add(number.id()));
		jdbcTemplate.update("""
				UPDATE raffle_number
				SET status = 'RESERVED', reserved_by_purchase = ?, reserved_until = ?
				WHERE id IN (""" + numberIds + ")", updateParameters.toArray());

		return new ReservedPurchase(purchaseId, buyerEmail, totalCents, expiresAt, requestedNumbers);
	}

	@Transactional
	public void failAndRelease(UUID purchaseId) {
		jdbcTemplate.update("""
				UPDATE purchase
				SET status = 'FAILED', updated_at = now()
				WHERE id = ? AND status = 'PENDING_PAYMENT'
				""", purchaseId);
		jdbcTemplate.update("""
				UPDATE raffle_number
				SET status = 'AVAILABLE', reserved_by_purchase = NULL, reserved_until = NULL
				WHERE reserved_by_purchase = ? AND status = 'RESERVED'
				""", purchaseId);
	}

	@Transactional
	public void savePixPayment(UUID purchaseId, PixPayment payment) {
		int updated = jdbcTemplate.update("""
				UPDATE purchase
				SET mercado_pago_payment_id = COALESCE(mercado_pago_payment_id, ?),
				    pix_copy_paste = COALESCE(?, pix_copy_paste),
				    pix_qr_code_base64 = COALESCE(?, pix_qr_code_base64),
				    payment_url = COALESCE(?, payment_url),
				    updated_at = now()
				WHERE id = ? AND status IN ('PENDING_PAYMENT', 'PAID')
				  AND mercado_pago_order_id IS NULL
				  AND (mercado_pago_payment_id IS NULL OR mercado_pago_payment_id = ?)
				""", payment.paymentId(), payment.copyPaste(), payment.qrCodeBase64(), payment.paymentUrl(), purchaseId, payment.paymentId());
		if (updated != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A compra não está mais aguardando pagamento.");
		}
	}

	@Transactional(readOnly = true)
	public PurchaseResponse getPurchase(UUID purchaseId) {
		PurchaseHeader purchase = jdbcTemplate.queryForObject("""
				SELECT id, status, total_cents, expires_at, pix_copy_paste, pix_qr_code_base64, payment_url
				FROM purchase WHERE id = ?
				""", (result, rowNumber) -> new PurchaseHeader(
					UUID.fromString(result.getString("id")), result.getString("status"), result.getInt("total_cents"),
					result.getTimestamp("expires_at").toInstant(), result.getString("pix_copy_paste"),
					result.getString("pix_qr_code_base64"), result.getString("payment_url")), purchaseId);

		List<Integer> numbers = jdbcTemplate.query("""
				SELECT n.number_value
				FROM purchase_number pn
			JOIN raffle_number n ON n.id = pn.raffle_number_id
			WHERE pn.purchase_id = ?
			ORDER BY n.number_value
			""", (result, rowNumber) -> result.getInt("number_value"), purchaseId);

		PurchaseResponse.PixDetails pix = !"PENDING_PAYMENT".equals(purchase.status()) || purchase.copyPaste() == null ? null : new PurchaseResponse.PixDetails(
				purchase.copyPaste(), purchase.qrCodeBase64(), purchase.paymentUrl());
		return new PurchaseResponse(purchase.id(), purchase.status(), numbers, purchase.totalCents(), purchase.expiresAt(), pix);
	}

	@Transactional
	public void applyProviderPayment(String eventId, PixPaymentStatus payment, UUID purchaseId) {
		PurchaseHeader purchase = jdbcTemplate.queryForObject("""
				SELECT id, status, total_cents, expires_at, pix_copy_paste, pix_qr_code_base64, payment_url
				FROM purchase WHERE id = ? FOR UPDATE
				""", (result, rowNumber) -> new PurchaseHeader(
					UUID.fromString(result.getString("id")), result.getString("status"), result.getInt("total_cents"),
					result.getTimestamp("expires_at").toInstant(), result.getString("pix_copy_paste"),
					result.getString("pix_qr_code_base64"), result.getString("payment_url")), purchaseId);

		String savedPaymentId = jdbcTemplate.queryForObject(
				"SELECT mercado_pago_payment_id FROM purchase WHERE id = ?", String.class, purchaseId);
		if (savedPaymentId != null && !payment.paymentId().equals(savedPaymentId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O pagamento recebido não corresponde à compra.");
		}
		if (savedPaymentId == null) {
			int linked = jdbcTemplate.update("""
					UPDATE purchase SET mercado_pago_payment_id = ?, updated_at = now()
					WHERE id = ? AND status = 'PENDING_PAYMENT' AND mercado_pago_payment_id IS NULL
					  AND mercado_pago_order_id IS NULL
					""", payment.paymentId(), purchaseId);
			if (linked != 1) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Não foi possível associar o pagamento à compra.");
			}
		}

		int inserted = jdbcTemplate.update("""
				INSERT INTO payment_event (event_id, mercado_pago_payment_id)
				VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING
				""", eventId, payment.paymentId());
		if (inserted == 0 || !"PENDING_PAYMENT".equals(purchase.status())) {
			return;
		}
		jdbcTemplate.update("""
				UPDATE purchase
				SET pix_copy_paste = COALESCE(?, pix_copy_paste),
				    pix_qr_code_base64 = COALESCE(?, pix_qr_code_base64),
				    payment_url = COALESCE(?, payment_url),
				    updated_at = now()
				WHERE id = ?
				""", payment.copyPaste(), payment.qrCodeBase64(), payment.paymentUrl(), purchaseId);

		if ("approved".equals(payment.status())) {
			if (!payment.isApprovedPix() || payment.totalPaidCents() == null || payment.totalPaidCents() != purchase.totalCents()
					|| payment.transactionAmountCents() == null || payment.transactionAmountCents() != purchase.totalCents()) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "O Pix não está aprovado pelo valor integral da compra.");
			}
			int linkedNumbers = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM purchase_number WHERE purchase_id = ?", Integer.class, purchase.id());
			jdbcTemplate.update("UPDATE purchase SET status = 'PAID', updated_at = now() WHERE id = ?", purchase.id());
			int soldNumbers = jdbcTemplate.update("""
					UPDATE raffle_number
					SET status = 'PAID', reserved_until = NULL
					WHERE reserved_by_purchase = ? AND status = 'RESERVED'
					""", purchase.id());
			if (linkedNumbers == 0 || soldNumbers != linkedNumbers) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Os números reservados não correspondem à compra.");
			}
		} else if ("cancelled".equals(payment.status()) || "canceled".equals(payment.status())
				|| "expired".equals(payment.status()) || "rejected".equals(payment.status())) {
			String finalStatus = "expired".equals(payment.status()) || "canceled".equals(payment.status())
					|| "cancelled".equals(payment.status()) ? "EXPIRED" : "FAILED";
			int linkedNumbers = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM purchase_number WHERE purchase_id = ?", Integer.class, purchase.id());
			jdbcTemplate.update("UPDATE purchase SET status = ?, updated_at = now() WHERE id = ?", finalStatus, purchase.id());
			int releasedNumbers = jdbcTemplate.update("""
					UPDATE raffle_number
					SET status = 'AVAILABLE', reserved_by_purchase = NULL, reserved_until = NULL
					WHERE reserved_by_purchase = ? AND status = 'RESERVED'
					""", purchase.id());
			if (linkedNumbers == 0 || releasedNumbers != linkedNumbers) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Os números reservados não correspondem à compra.");
			}
		}
	}

	public record ReservedPurchase(UUID id, String buyerEmail, int totalCents, Instant expiresAt, List<Integer> numbers) {
	}

	public record NumberReservation(long id, int number) {
	}

	public record RafflePrice(long id, Integer unitPriceCents) {
	}

	private record BuyerEmail(@NotBlank @Email @Size(max = 254) String value) {
	}

	private record PurchaseHeader(
			UUID id, String status, int totalCents, Instant expiresAt,
			String copyPaste, String qrCodeBase64, String paymentUrl) {
	}
}
