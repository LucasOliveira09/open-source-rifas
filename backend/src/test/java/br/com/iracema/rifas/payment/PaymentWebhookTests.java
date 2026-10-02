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
import br.com.iracema.rifas.payment.MercadoPagoClient.PixPaymentStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentWebhookTests {
    private final MercadoPagoClient client = mock(MercadoPagoClient.class);
    private final PurchaseStore store = mock(PurchaseStore.class);
    private final MercadoPagoWebhookController controller = new MercadoPagoWebhookController(client, store);
    private final String secret = "segredo-local-exclusivo-de-teste";
    private final String paymentId = "123456789";
    private final String requestId = UUID.randomUUID().toString();

    private String signature() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String manifest = "id:" + paymentId + ";request-id:" + requestId + ";ts:" + timestamp + ";";
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
        assertThatThrownBy(() -> controller.receive(null, requestId, paymentId, "payment", Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        verify(client, never()).getPaymentStatus(anyString());
        verifyNoInteractions(store);
    }

    @Test void rejectsIncorrectHmacBeforeChangingDatabase() {
        configured();
        assertThatThrownBy(() -> controller.receive("ts=1,v1=" + "0".repeat(64), requestId, paymentId, "payment", Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        verifyNoInteractions(store);
    }

    @Test void validSignatureQueriesProviderAndUsesProviderReference() throws Exception {
        configured();
        var purchaseId = UUID.randomUUID();
        var payment = new PixPaymentStatus(paymentId, purchaseId.toString(), "approved", "accredited", 500,
                500, "pix", "bank_transfer", "BRL", null, null, null);
        when(client.getPaymentStatus(paymentId)).thenReturn(payment);
        assertThat(controller.receive(signature(), requestId, paymentId, "payment", Map.of("id", "event-test")).getStatusCode().value()).isEqualTo(200);
        verify(store).applyProviderPayment("payment:event-test", payment, purchaseId);
    }

    @Test void refusesProviderResponseForAnotherOrder() throws Exception {
        configured();
        when(client.getPaymentStatus(paymentId)).thenReturn(new PixPaymentStatus("other-payment", UUID.randomUUID().toString(),
                "approved", "accredited", 500, 500, "pix", "bank_transfer", "BRL", null, null, null));
        var signed = signature();
        assertThatThrownBy(() -> controller.receive(signed, requestId, paymentId, "payment", Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("502");
        verifyNoInteractions(store);
    }
}
