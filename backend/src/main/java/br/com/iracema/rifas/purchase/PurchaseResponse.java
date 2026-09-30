package br.com.iracema.rifas.purchase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PurchaseResponse(
		UUID id,
		String status,
		List<Integer> numbers,
		int totalCents,
		Instant expiresAt,
		PixDetails pix) {

	public record PixDetails(String copyPaste, String qrCodeBase64, String paymentUrl) {
	}
}
