package br.com.iracema.rifas.purchase;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import br.com.iracema.rifas.payment.MercadoPagoClient.PixOrder;
import br.com.iracema.rifas.payment.MercadoPagoClient.PixOrderStatus;
import br.com.iracema.rifas.purchase.PurchaseStore.ReservedPurchase;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class PurchaseStoreTests {
    @Autowired PurchaseStore store;
    @Autowired JdbcTemplate jdbc;

    private ReservedPurchase reserve(Integer... numbers) {
        return store.reserve(new PurchaseRequest("Comprador de teste", "teste@example.com", "11999999999", List.of(numbers)), Instant.now().plusSeconds(3600));
    }

    private PixOrderStatus status(ReservedPurchase purchase, String state, int paidCents, String method) {
        return new PixOrderStatus("order-" + purchase.id(), purchase.id().toString(), state,
                "processed".equals(state) ? "accredited" : state, paidCents,
                state, "processed".equals(state) ? "accredited" : state,
                method, "BRL", null, null, null);
    }

    @Test void persistsBuyerNumbersAndServerCalculatedAmount() {
        var purchase = reserve(90, 91);
        assertThat(purchase.totalCents()).isEqualTo(1000);
        assertThat(store.getPurchase(purchase.id()).numbers()).containsExactly(90, 91);
        assertThat(jdbc.queryForObject("SELECT buyer_name FROM purchase WHERE id = ?", String.class, purchase.id())).isEqualTo("Comprador de teste");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raffle_number WHERE reserved_by_purchase = ? AND status = 'RESERVED'", Integer.class, purchase.id())).isEqualTo(2);
    }

    @Test void rejectsAnAlreadyReservedNumber() {
        reserve(90);
        assertThatThrownBy(() -> reserve(90)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test void rejectsDuplicateNumbers() {
        assertThatThrownBy(() -> reserve(90, 90)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test void refusesCheckoutWithoutPrice() {
        jdbc.update("UPDATE raffle SET unit_price_cents = NULL WHERE slug = 'iracema'");
        assertThatThrownBy(() -> reserve(90)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
    }

    @Test void confirmsApprovedPixAndDeduplicatesEvents() {
        var purchase = reserve(90);
        var order = status(purchase, "processed", 500, "pix");
        store.applyProviderOrder("event-" + purchase.id(), order, purchase.id());
        store.applyProviderOrder("event-" + purchase.id(), order, purchase.id());
        assertThat(store.getPurchase(purchase.id()).status()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM raffle_number WHERE number_value = 90", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_event WHERE event_id = ?", Integer.class, "event-" + purchase.id())).isEqualTo(1);
    }

    @Test void rejectsAnApprovedPixWithWrongAmount() {
        var purchase = reserve(90);
        assertThatThrownBy(() -> store.applyProviderOrder("event-" + purchase.id(), status(purchase, "processed", 499, "pix"), purchase.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(store.getPurchase(purchase.id()).status()).isEqualTo("PENDING_PAYMENT");
    }

    @Test void rejectsAnotherPaymentMethod() {
        var purchase = reserve(90);
        assertThatThrownBy(() -> store.applyProviderOrder("event-" + purchase.id(), status(purchase, "processed", 500, "credit_card"), purchase.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test void rejectsAnOrderDifferentFromTheSavedOrder() {
        var purchase = reserve(90);
        store.savePixOrder(purchase.id(), new PixOrder("original-order", null, null, null));
        assertThatThrownBy(() -> store.applyProviderOrder("event-" + purchase.id(), status(purchase, "processed", 500, "pix"), purchase.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test void releasesCancelledNumberAndPreservesPurchaseHistory() {
        var purchase = reserve(90);
        store.applyProviderOrder("event-" + purchase.id(), status(purchase, "canceled", 0, "pix"), purchase.id());
        assertThat(store.getPurchase(purchase.id()).status()).isEqualTo("EXPIRED");
        var retry = reserve(90);
        assertThat(retry.id()).isNotEqualTo(purchase.id());
        assertThat(store.getPurchase(purchase.id()).numbers()).containsExactly(90);
        assertThat(store.getPurchase(retry.id()).status()).isEqualTo("PENDING_PAYMENT");
    }

    @Test void neverReleasesPaidNumberAfterARepeatedTerminalEvent() {
        var purchase = reserve(90);
        store.applyProviderOrder("paid-" + purchase.id(), status(purchase, "processed", 500, "pix"), purchase.id());
        store.applyProviderOrder("cancel-" + purchase.id(), status(purchase, "canceled", 0, "pix"), purchase.id());
        assertThat(store.getPurchase(purchase.id()).status()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM raffle_number WHERE number_value = 90", String.class)).isEqualTo("PAID");
    }
}
