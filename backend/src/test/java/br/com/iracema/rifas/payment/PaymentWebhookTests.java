package br.com.iracema.rifas.payment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import br.com.iracema.rifas.purchase.PurchaseStore;
import br.com.iracema.rifas.payment.MercadoPagoClient.PixOrderStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentWebhookTests {
    private final MercadoPagoClient client = mock(MercadoPagoClient.class);
    private final PurchaseStore store = mock(PurchaseStore.class);
    private final MercadoPagoWebhookController controller = new MercadoPagoWebhookController(client, store);
    private final String secret = "segredo-local-exclusivo-de-teste";
    private final String orderId = "ORDTEST123";
    private final String requestId = UUID.randomUUID().toString();

    private String signature() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String manifest = "id:" + orderId + ";request-id:" + requestId + ";ts:" + timestamp + ";";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "ts=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
    }

    private void configured() {
        when(client.isConfigured()).thenReturn(true);
        when(client.webhookSecret()).thenReturn(secret);
    }

    @Test void rejectsMissingSignatureBeforeContactingProvider() {
        configured();
        assertThatThrownBy(() -> controller.receive(null, requestId, orderId, "order", Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        verify(client, never()).getOrderStatus(anyString());
        verifyNoInteractions(store);
    }

    @Test void rejectsIncorrectHmacBeforeChangingDatabase() {
        configured();
        assertThatThrownBy(() -> controller.receive("ts=1,v1=" + "0".repeat(64), requestId, orderId, "order", Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        verifyNoInteractions(store);
    }

    @Test void validSignatureQueriesProviderAndUsesProviderReference() throws Exception {
        configured();
        var purchaseId = UUID.randomUUID();
        var order = new PixOrderStatus(orderId, purchaseId.toString(), "processed", "accredited", 500,
                "processed", "accredited", "pix", "BRL", null, null, null);
        when(client.getOrderStatus(orderId)).thenReturn(order);
        assertThat(controller.receive(signature(), requestId, orderId, "order", Map.of("id", "event-test")).getStatusCode().value()).isEqualTo(200);
        verify(store).applyProviderOrder("event-test", order, purchaseId);
    }

    @Test void refusesProviderResponseForAnotherOrder() throws Exception {
        configured();
        when(client.getOrderStatus(orderId)).thenReturn(new PixOrderStatus("other-order", UUID.randomUUID().toString(),
                "processed", "accredited", 500, "processed", "accredited", "pix", "BRL", null, null, null));
        var signed = signature();
        assertThatThrownBy(() -> controller.receive(signed, requestId, orderId, "order", Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("502");
        verifyNoInteractions(store);
    }
}
