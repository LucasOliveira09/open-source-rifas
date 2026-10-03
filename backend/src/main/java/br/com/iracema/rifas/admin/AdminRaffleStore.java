package br.com.iracema.rifas.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class AdminRaffleStore {
    private final JdbcTemplate jdbc;

    public AdminRaffleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<RaffleSummary> findRaffles() {
        return jdbc.query("""
                SELECT r.id, r.title, r.slug, r.status, r.unit_price_cents,
                       COUNT(n.id)::int AS total_numbers,
                       COUNT(n.id) FILTER (WHERE n.status = 'PAID')::int AS paid_numbers,
                       COUNT(n.id) FILTER (WHERE n.status = 'RESERVED')::int AS reserved_numbers,
                       (SELECT COUNT(*)::int FROM raffle_draw d WHERE d.raffle_id = r.id) AS draw_count
                FROM raffle r
                LEFT JOIN raffle_number n ON n.raffle_id = r.id
                GROUP BY r.id
                ORDER BY r.created_at DESC, r.id DESC
                """, (rs, row) -> new RaffleSummary(rs.getLong("id"), rs.getString("title"), rs.getString("slug"),
                rs.getString("status"), rs.getObject("unit_price_cents", Integer.class), rs.getInt("total_numbers"),
                rs.getInt("paid_numbers"), rs.getInt("reserved_numbers"), rs.getInt("draw_count")));
    }

    @Transactional(readOnly = true)
    public List<PaidPurchase> findPaidPurchases(long raffleId, Integer number) {
        requireRaffle(raffleId);
        if (number != null && (number < 1 || number > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um número entre 1 e 100.");
        }
        return jdbc.query("""
                SELECT p.id, p.buyer_name, p.buyer_email, p.buyer_phone, p.updated_at,
                       string_agg(n.number_value::text, ', ' ORDER BY n.number_value) AS numbers
                FROM purchase p
                JOIN purchase_number pn ON pn.purchase_id = p.id
                JOIN raffle_number n ON n.id = pn.raffle_number_id
                WHERE p.raffle_id = ? AND p.status = 'PAID'
                  AND (CAST(? AS SMALLINT) IS NULL OR EXISTS (
                      SELECT 1 FROM purchase_number search_pn
                      JOIN raffle_number search_n ON search_n.id = search_pn.raffle_number_id
                      WHERE search_pn.purchase_id = p.id AND search_n.number_value = CAST(? AS SMALLINT)
                  ))
                GROUP BY p.id
                ORDER BY p.updated_at DESC, p.buyer_name
                """, (rs, row) -> new PaidPurchase(rs.getString("id"), rs.getString("buyer_name"),
                rs.getString("buyer_email"), rs.getString("buyer_phone"), rs.getString("numbers"),
                rs.getTimestamp("updated_at").toInstant()), raffleId, number, number);
    }

    @Transactional(readOnly = true)
    public List<DrawResult> findDraws(long raffleId) {
        requireRaffle(raffleId);
        return jdbc.query("""
                SELECT d.draw_sequence, n.number_value, p.buyer_name, d.drawn_by, d.drawn_at
                FROM raffle_draw d
                JOIN raffle_number n ON n.id = d.raffle_number_id
                JOIN purchase p ON p.id = d.purchase_id
                WHERE d.raffle_id = ?
                ORDER BY d.draw_sequence
                """, (rs, row) -> new DrawResult(rs.getInt("draw_sequence"), rs.getInt("number_value"),
                rs.getString("buyer_name"), rs.getString("drawn_by"), rs.getTimestamp("drawn_at").toInstant()), raffleId);
    }

    @Transactional
    public DrawResult drawNext(long raffleId, String adminUsername) {
        String raffleStatus = jdbc.query("SELECT status FROM raffle WHERE id = ? FOR UPDATE", rs ->
                rs.next() ? rs.getString("status") : null, raffleId);
        if (raffleStatus == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rifa não encontrada.");
        }

        DrawCandidate candidate = jdbc.query("""
                SELECT n.id AS number_id, n.number_value, p.id AS purchase_id, p.buyer_name
                FROM raffle_number n
                JOIN purchase_number pn ON pn.raffle_number_id = n.id
                JOIN purchase p ON p.id = pn.purchase_id
                WHERE n.raffle_id = ? AND n.status = 'PAID' AND p.status = 'PAID'
                  AND NOT EXISTS (SELECT 1 FROM raffle_draw d WHERE d.raffle_number_id = n.id)
                ORDER BY random()
                LIMIT 1
                FOR UPDATE OF n, p
                """, rs -> rs.next() ? new DrawCandidate(rs.getLong("number_id"), rs.getInt("number_value"),
                rs.getObject("purchase_id", java.util.UUID.class), rs.getString("buyer_name")) : null, raffleId);
        if (candidate == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Não há números pagos e ainda não sorteados.");
        }

        Integer sequence = jdbc.queryForObject(
                "SELECT COALESCE(MAX(draw_sequence), 0) + 1 FROM raffle_draw WHERE raffle_id = ?", Integer.class, raffleId);
        Instant drawnAt = Instant.now();
        jdbc.update("""
                INSERT INTO raffle_draw (raffle_id, draw_sequence, raffle_number_id, purchase_id, drawn_by, drawn_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, raffleId, sequence, candidate.numberId(), candidate.purchaseId(), adminUsername, Timestamp.from(drawnAt));
        return new DrawResult(sequence, candidate.number(), candidate.buyerName(), adminUsername, drawnAt);
    }

    private void requireRaffle(long raffleId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM raffle WHERE id = ?)", Boolean.class, raffleId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rifa não encontrada.");
        }
    }

    public record RaffleSummary(long id, String title, String slug, String status, Integer unitPriceCents,
            int totalNumbers, int paidNumbers, int reservedNumbers, int drawCount) {}
    public record PaidPurchase(String id, String buyerName, String buyerEmail, String buyerPhone, String numbers,
            Instant paidAt) {}
    public record DrawResult(int sequence, int number, String buyerName, String drawnBy, Instant drawnAt) {}
    private record DrawCandidate(long numberId, int number, java.util.UUID purchaseId, String buyerName) {}
}
