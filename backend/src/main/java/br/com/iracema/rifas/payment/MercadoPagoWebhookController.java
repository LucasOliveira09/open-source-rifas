package br.com.iracema.rifas.payment;

import java.util.Map;
import java.util.UUID;

import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.webhook.WebhookSignatureValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import br.com.iracema.rifas.payment.MercadoPagoClient.PixPaymentStatus;
import br.com.iracema.rifas.purchase.PurchaseStore;

@RestController
@RequestMapping("/api/webhooks/mercadopago")
public class MercadoPagoWebhookController {

	private final MercadoPagoClient mercadoPagoClient;
	private final PurchaseStore purchaseStore;

	public MercadoPagoWebhookController(MercadoPagoClient mercadoPagoClient, PurchaseStore purchaseStore) {
		this.mercadoPagoClient = mercadoPagoClient;
		this.purchaseStore = purchaseStore;
	}

	@PostMapping
	public ResponseEntity<Void> receive(
			@RequestHeader(value = "x-signature", required = false) String signature,
			@RequestHeader(value = "x-request-id", required = false) String requestId,
			@RequestParam(name = "data.id", required = false) String dataId,
			@RequestParam(name = "type", required = false) String queryType,
			@RequestBody Map<String, Object> notification) {
		if (!mercadoPagoClient.isConfigured() || signature == null || requestId == null || dataId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Notificação inválida.");
		}

		try {
			WebhookSignatureValidator.validate(signature, requestId, dataId, mercadoPagoClient.webhookSecret());
		} catch (MPInvalidWebhookSignatureException exception) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Assinatura do Webhook inválida.");
		}

		String type = queryType == null ? String.valueOf(notification.get("type")) : queryType;
		if (!"payment".equals(type)) {
			return ResponseEntity.ok().build();
		}
		if (!dataId.matches("[0-9]{1,64}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador do pagamento inválido.");
		}

		PixPaymentStatus payment = mercadoPagoClient.getPaymentStatus(dataId);
		if (!dataId.equals(payment.paymentId())) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O pagamento retornado não corresponde à notificação.");
		}

		UUID purchaseId;
		try {
			purchaseId = UUID.fromString(payment.externalReference());
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A referência da compra é inválida.");
		}

		String eventId = "payment:" + (notification.get("id") == null ? requestId : notification.get("id").toString());
		purchaseStore.applyProviderPayment(eventId, payment, purchaseId);
		return ResponseEntity.ok().build();
	}
}
