package br.com.iracema.rifas.purchase;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import br.com.iracema.rifas.payment.MercadoPagoClient;
import br.com.iracema.rifas.payment.MercadoPagoClient.PixPayment;
import br.com.iracema.rifas.payment.MercadoPagoClient.PixPaymentRejectedException;
import br.com.iracema.rifas.purchase.PurchaseStore.ReservedPurchase;

@Service
public class PurchaseService {

	private final PurchaseStore purchaseStore;
	private final MercadoPagoClient mercadoPagoClient;

	public PurchaseService(PurchaseStore purchaseStore, MercadoPagoClient mercadoPagoClient) {
		this.purchaseStore = purchaseStore;
		this.mercadoPagoClient = mercadoPagoClient;
	}

	public PurchaseResponse create(PurchaseRequest request) {
		if (!mercadoPagoClient.isConfigured()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "O pagamento Pix ainda não está configurado.");
		}

		Instant expiresAt = Instant.now().plus(mercadoPagoClient.pixExpiration());
		ReservedPurchase purchase = purchaseStore.reserve(request, expiresAt);
		PixPayment pixPayment;
		try {
			pixPayment = mercadoPagoClient.createPixPayment(purchase);
		} catch (PixPaymentRejectedException exception) {
			purchaseStore.failAndRelease(purchase.id());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
		}
		purchaseStore.savePixPayment(purchase.id(), pixPayment);
		return purchaseStore.getPurchase(purchase.id());
	}

	public PurchaseResponse get(UUID purchaseId) {
		try {
			return purchaseStore.getPurchase(purchaseId);
		} catch (org.springframework.dao.EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Compra não encontrada.");
		}
	}
}
