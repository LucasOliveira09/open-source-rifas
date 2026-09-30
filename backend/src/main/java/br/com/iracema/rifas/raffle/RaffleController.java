package br.com.iracema.rifas.raffle;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/raffle")
public class RaffleController {

	private final JdbcTemplate jdbcTemplate;
	private final String mercadoPagoAccessToken;
	private final String mercadoPagoWebhookSecret;

	public RaffleController(
			JdbcTemplate jdbcTemplate,
			@Value("${app.mercado-pago.access-token:}") String mercadoPagoAccessToken,
			@Value("${app.mercado-pago.webhook-secret:}") String mercadoPagoWebhookSecret) {
		this.jdbcTemplate = jdbcTemplate;
		this.mercadoPagoAccessToken = mercadoPagoAccessToken;
		this.mercadoPagoWebhookSecret = mercadoPagoWebhookSecret;
	}

	@GetMapping
	public RaffleResponse getRaffle() {
		RaffleSummary raffle = jdbcTemplate.queryForObject("""
				SELECT r.id,
				       r.title,
				       r.description,
				       r.unit_price_cents,
				       r.currency,
				       COUNT(n.id)::int AS total_numbers,
				       COUNT(n.id) FILTER (WHERE n.status = 'AVAILABLE')::int AS available_numbers
				FROM raffle r
			LEFT JOIN raffle_number n ON n.raffle_id = r.id
			WHERE r.slug = 'iracema' AND r.status = 'ACTIVE'
			GROUP BY r.id
		""", (result, rowNumber) -> new RaffleSummary(
					result.getLong("id"),
					result.getString("title"),
					result.getString("description"),
					result.getObject("unit_price_cents", Integer.class),
					result.getString("currency"),
					result.getInt("total_numbers"),
					result.getInt("available_numbers")));

		return new RaffleResponse(
				raffle.id(), raffle.title(), raffle.description(), raffle.unitPriceCents(), raffle.currency(),
				raffle.totalNumbers(), raffle.availableNumbers(),
				raffle.unitPriceCents() != null && !mercadoPagoAccessToken.isBlank() && !mercadoPagoWebhookSecret.isBlank(),
				findPrizes(raffle.id()));
	}

	@GetMapping("/numbers")
	public List<RaffleNumberResponse> getNumbers() {
		return jdbcTemplate.query("""
				SELECT number_value, status
				FROM raffle_number
			WHERE raffle_id = (SELECT id FROM raffle WHERE slug = 'iracema' AND status = 'ACTIVE')
			ORDER BY number_value
			""", (result, rowNumber) -> new RaffleNumberResponse(
				result.getInt("number_value"),
				result.getString("status")));
	}

	private List<RaffleResponse.PrizeResponse> findPrizes(long raffleId) {
		return jdbcTemplate.query("""
				SELECT id, title, description, image_url
				FROM prize
				WHERE raffle_id = ?
				ORDER BY display_order, id
				""", (result, rowNumber) -> new RaffleResponse.PrizeResponse(
				result.getLong("id"),
				result.getString("title"),
				result.getString("description"),
				result.getString("image_url")), raffleId);
	}

	private record RaffleSummary(
			long id,
			String title,
			String description,
			Integer unitPriceCents,
			String currency,
			int totalNumbers,
			int availableNumbers) {
	}
}
