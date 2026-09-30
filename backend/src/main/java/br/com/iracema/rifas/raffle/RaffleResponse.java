package br.com.iracema.rifas.raffle;

import java.util.List;

public record RaffleResponse(
		long id,
		String title,
		String description,
		Integer unitPriceCents,
		String currency,
		int totalNumbers,
		int availableNumbers,
		boolean purchaseEnabled,
		List<PrizeResponse> prizes) {

	public record PrizeResponse(long id, String title, String description, String imageUrl) {
	}
}
