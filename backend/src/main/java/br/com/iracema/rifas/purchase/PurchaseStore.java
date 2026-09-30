package br.com.iracema.rifas.purchase;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.com.iracema.rifas.payment.MercadoPagoClient.PixOrder;
import br.com.iracema.rifas.payment.MercadoPagoClient.PixOrderStatus;

@Repository
public class PurchaseStore {

	private final JdbcTemplate jdbcTemplate;

	public PurchaseStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public ReservedPurchase reserve(PurchaseRequest request, Instant expiresAt) {
		releaseExpiredReservations();
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
				""", purchaseId, raffle.id(), request.name().trim(), request.email().trim().toLowerCase(),
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

		return new ReservedPurchase(purchaseId, request.email().trim().toLowerCase(), totalCents, expiresAt, requestedNumbers);
	}

	@Transactional
	public void releaseExpiredReservations() {
		jdbcTemplate.update("""
				UPDATE purchase
				SET status = 'EXPIRED', updated_at = now()
				WHERE status = 'PENDING_PAYMENT' AND expires_at <= now()
				""");
		jdbcTemplate.update("""
				UPDATE raffle_number
				SET status = 'AVAILABLE', reserved_by_purchase = NULL, reserved_until = NULL
				WHERE status = 'RESERVED' AND reserved_until <= now()
				""");
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
	public void savePixOrder(UUID purchaseId, PixOrder order) {
		int updated = jdbcTemplate.update("""
				UPDATE purchase
				SET mercado_pago_order_id = ?, pix_copy_paste = ?, pix_qr_code_base64 = ?, payment_url = ?, updated_at = now()
				WHERE id = ? AND status = 'PENDING_PAYMENT'
				""", order.orderId(), order.copyPaste(), order.qrCodeBase64(), order.paymentUrl(), purchaseId);
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
	public void applyProviderOrder(String eventId, PixOrderStatus order, UUID purchaseId) {
		PurchaseHeader purchase = jdbcTemplate.queryForObject("""
				SELECT id, status, total_cents, expires_at, pix_copy_paste, pix_qr_code_base64, payment_url
				FROM purchase WHERE id = ? FOR UPDATE
				""", (result, rowNumber) -> new PurchaseHeader(
					UUID.fromString(result.getString("id")), result.getString("status"), result.getInt("total_cents"),
					result.getTimestamp("expires_at").toInstant(), result.getString("pix_copy_paste"),
					result.getString("pix_qr_code_base64"), result.getString("payment_url")), purchaseId);

		String savedOrderId = jdbcTemplate.queryForObject(
				"SELECT mercado_pago_order_id FROM purchase WHERE id = ?", String.class, purchaseId);
		if (!order.orderId().equals(savedOrderId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A order recebida não corresponde à compra.");
		}

		int inserted = jdbcTemplate.update("""
				INSERT INTO payment_event (event_id, mercado_pago_order_id)
				VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING
				""", eventId, order.orderId());
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
				""", order.copyPaste(), order.qrCodeBase64(), order.paymentUrl(), purchaseId);

		if ("processed".equals(order.status())) {
			if (order.totalPaidCents() == null || order.totalPaidCents() != purchase.totalCents()) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "O valor confirmado não corresponde ao total da compra.");
			}
			jdbcTemplate.update("UPDATE purchase SET status = 'PAID', updated_at = now() WHERE id = ?", purchase.id());
			jdbcTemplate.update("""
					UPDATE raffle_number
					SET status = 'PAID', reserved_until = NULL
					WHERE reserved_by_purchase = ? AND status = 'RESERVED'
					""", purchase.id());
		} else if ("cancelled".equals(order.status()) || "canceled".equals(order.status()) || "expired".equals(order.status())) {
			String finalStatus = "expired".equals(order.status()) ? "EXPIRED" : "FAILED";
			jdbcTemplate.update("UPDATE purchase SET status = ?, updated_at = now() WHERE id = ?", finalStatus, purchase.id());
			jdbcTemplate.update("""
					UPDATE raffle_number
					SET status = 'AVAILABLE', reserved_by_purchase = NULL, reserved_until = NULL
					WHERE reserved_by_purchase = ? AND status = 'RESERVED'
					""", purchase.id());
		}
	}

	public record ReservedPurchase(UUID id, String buyerEmail, int totalCents, Instant expiresAt, List<Integer> numbers) {
	}

	public record NumberReservation(long id, int number) {
	}

	public record RafflePrice(long id, Integer unitPriceCents) {
	}

	private record PurchaseHeader(
			UUID id, String status, int totalCents, Instant expiresAt,
			String copyPaste, String qrCodeBase64, String paymentUrl) {
	}
}
