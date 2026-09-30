package br.com.iracema.rifas.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import br.com.iracema.rifas.purchase.PurchaseStore.ReservedPurchase;

@Component
public class MercadoPagoClient {

	private final RestClient restClient = RestClient.create("https://api.mercadopago.com");
	private final String accessToken;
	private final String webhookSecret;
	private final Duration pixExpiration;

	public MercadoPagoClient(
			@Value("${app.mercado-pago.access-token:}") String accessToken,
			@Value("${app.mercado-pago.webhook-secret:}") String webhookSecret,
			@Value("${app.pix.expiration:PT24H}") String pixExpiration) {
		this.accessToken = accessToken;
		this.webhookSecret = webhookSecret;
		this.pixExpiration = Duration.parse(pixExpiration);
	}

	public boolean isConfigured() {
		return !accessToken.isBlank() && !webhookSecret.isBlank();
	}

	public Duration pixExpiration() {
		return pixExpiration;
	}

	public String webhookSecret() {
		return webhookSecret;
	}

	public PixOrder createPixOrder(ReservedPurchase purchase) {
		String amount = formatAmount(purchase.totalCents());
		Map<String, Object> body = Map.of(
				"type", "online",
				"total_amount", amount,
				"external_reference", purchase.id().toString(),
				"processing_mode", "automatic",
				"transactions", Map.of("payments", List.of(Map.of(
						"amount", amount,
						"payment_method", Map.of("id", "pix", "type", "bank_transfer"),
						"expiration_time", pixExpiration.toString()))),
				"payer", Map.of("email", purchase.buyerEmail()));

		Map<?, ?> response = postOrder(body, purchase.id());
		String orderId = string(response, "id");
		if (orderId == null || orderId.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago não retornou o identificador da order.");
		}
		Map<?, ?> paymentMethod = firstPaymentMethod(response);
		return new PixOrder(
				orderId,
				optionalString(paymentMethod, "qr_code"),
				optionalString(paymentMethod, "qr_code_base64"),
				optionalString(paymentMethod, "ticket_url"));
	}

	public PixOrderStatus getOrderStatus(String orderId) {
		try {
			Map<?, ?> response = restClient.get()
					.uri("/v1/orders/{orderId}", orderId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(Map.class);
			if (response == null) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago retornou uma resposta vazia.");
			}
			Integer totalPaidCents = cents(response.get("total_paid_amount"));
			Map<?, ?> payment = firstPayment(response);
			Map<?, ?> paymentMethod = firstPaymentMethod(response);
			return new PixOrderStatus(
					string(response, "id"), string(response, "external_reference"),
					string(response, "status"), string(response, "status_detail"), totalPaidCents,
					string(payment, "status"), string(payment, "status_detail"),
					string(paymentMethod, "id"), string(response, "currency_id"),
					optionalString(paymentMethod, "qr_code"),
					optionalString(paymentMethod, "qr_code_base64"),
					optionalString(paymentMethod, "ticket_url"));
		} catch (RestClientResponseException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar a confirmação do Pix.");
		} catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o Mercado Pago.");
		}
	}

	private Map<?, ?> postOrder(Map<String, Object> body, UUID idempotencyKey) {
		try {
			Map<?, ?> response = restClient.post()
					.uri("/v1/orders")
					.header("Authorization", "Bearer " + accessToken)
					.header("X-Idempotency-Key", idempotencyKey.toString())
					.body(body)
					.retrieve()
					.body(Map.class);
			if (response == null) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago retornou uma resposta vazia.");
			}
			return response;
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().is4xxClientError()) {
				throw new PixOrderRejectedException(exception);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível iniciar o pagamento Pix.");
		} catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível conectar ao Mercado Pago.");
		}
	}

	private static Map<?, ?> firstPaymentMethod(Map<?, ?> order) {
		Object paymentMethod = firstPayment(order).get("payment_method");
		return paymentMethod instanceof Map<?, ?> map ? map : Map.of();
	}

	private static Map<?, ?> firstPayment(Map<?, ?> order) {
		Object transactionsValue = order.get("transactions");
		if (!(transactionsValue instanceof Map<?, ?> transactions)) {
			return Map.of();
		}
		Object paymentsValue = transactions.get("payments");
		if (!(paymentsValue instanceof List<?> payments) || payments.isEmpty()) {
			return Map.of();
		}
		return asMap(payments.getFirst());
	}

	private static Map<?, ?> asMap(Object value) {
		if (value instanceof Map<?, ?> map) {
			return map;
		}
		throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago retornou dados inválidos.");
	}

	private static String string(Map<?, ?> values, String key) {
		Object value = values.get(key);
		return value == null ? null : value.toString();
	}

	private static String optionalString(Map<?, ?> values, String key) {
		return values == null ? null : string(values, key);
	}

	private static Integer cents(Object amount) {
		if (amount == null) {
			return null;
		}
		try {
			return new BigDecimal(amount.toString()).movePointRight(2).intValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago retornou um valor inválido.");
		}
	}

	private static String formatAmount(int cents) {
		return BigDecimal.valueOf(cents, 2).setScale(2).toPlainString();
	}

	public record PixOrder(String orderId, String copyPaste, String qrCodeBase64, String paymentUrl) {
	}

	public record PixOrderStatus(
		String orderId, String externalReference, String status, String statusDetail, Integer totalPaidCents,
		String paymentStatus, String paymentStatusDetail, String paymentMethodId, String currencyId,
		String copyPaste, String qrCodeBase64, String paymentUrl) {

		public boolean isApprovedPix() {
			return "processed".equals(status)
					&& "accredited".equals(statusDetail)
					&& "processed".equals(paymentStatus)
					&& "accredited".equals(paymentStatusDetail)
					&& "pix".equals(paymentMethodId)
					&& (currencyId == null || "BRL".equals(currencyId));
		}
	}

	public static class PixOrderRejectedException extends RuntimeException {
		public PixOrderRejectedException(Throwable cause) {
			super("O Mercado Pago recusou a criação da ordem Pix.", cause);
		}
	}
}
