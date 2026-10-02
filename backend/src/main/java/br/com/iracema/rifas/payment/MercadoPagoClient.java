package br.com.iracema.rifas.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import br.com.iracema.rifas.purchase.PurchaseStore.ReservedPurchase;

@Component
public class MercadoPagoClient {
    private static final Logger logger = LoggerFactory.getLogger(MercadoPagoClient.class);
    private static final DateTimeFormatter EXPIRATION_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    private final RestClient restClient;
    private final String accessToken;
    private final String webhookSecret;
    private final String notificationUrl;
    private final Duration pixExpiration;

    public MercadoPagoClient(
            @Value("${app.mercado-pago.access-token:}") String accessToken,
            @Value("${app.mercado-pago.webhook-secret:}") String webhookSecret,
            @Value("${app.pix.expiration:PT24H}") String pixExpiration,
            @Value("${app.mercado-pago.notification-url:}") String notificationUrl) {
        this.accessToken = accessToken.trim();
        this.webhookSecret = webhookSecret.trim();
        this.pixExpiration = Duration.parse(pixExpiration);
        if (this.pixExpiration.compareTo(Duration.ofMinutes(30)) < 0
                || this.pixExpiration.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("PIX_EXPIRATION deve ser de 30 minutos a 30 dias.");
        }
        this.notificationUrl = notificationUrl.trim();
        if (!this.notificationUrl.isBlank()) {
            URI uri = URI.create(this.notificationUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())) {
                throw new IllegalArgumentException("MERCADO_PAGO_NOTIFICATION_URL deve ser uma URL pública HTTPS.");
            }
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(12));
        this.restClient = RestClient.builder().baseUrl("https://api.mercadopago.com")
                .requestFactory(requestFactory).build();
    }

    public boolean isConfigured() { return !accessToken.isBlank() && !webhookSecret.isBlank(); }
    public Duration pixExpiration() { return pixExpiration; }
    public String webhookSecret() { return webhookSecret; }

    public PixPayment createPixPayment(ReservedPurchase purchase) {
        Map<String, Object> body = new HashMap<>();
        body.put("transaction_amount", BigDecimal.valueOf(purchase.totalCents(), 2));
        body.put("description", "Rifa SEBRAE - 3º ano A e B");
        body.put("payment_method_id", "pix");
        body.put("external_reference", purchase.id().toString());
        body.put("date_of_expiration", purchase.expiresAt().atOffset(ZoneOffset.UTC).format(EXPIRATION_FORMAT));
        body.put("payer", Map.of("email", purchase.buyerEmail()));
        if (!notificationUrl.isBlank()) { body.put("notification_url", notificationUrl); }
        Map<?, ?> response = postPayment(body, purchase.id());
        String paymentId = string(response, "id");
        if (paymentId == null || !paymentId.matches("[0-9]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago não retornou o identificador do pagamento.");
        }
        Map<?, ?> data = transactionData(response);
        return new PixPayment(paymentId, string(data, "qr_code"), string(data, "qr_code_base64"), string(data, "ticket_url"));
    }

    public PixPaymentStatus getPaymentStatus(String paymentId) {
        if (!paymentId.matches("[0-9]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador do pagamento inválido.");
        }
        try {
            Map<?, ?> response = restClient.get().uri("/v1/payments/{paymentId}", paymentId)
                    .header("Authorization", "Bearer " + accessToken).retrieve().body(Map.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago retornou uma resposta vazia.");
            }
            Map<?, ?> data = transactionData(response);
            return new PixPaymentStatus(string(response, "id"), string(response, "external_reference"),
                    string(response, "status"), string(response, "status_detail"),
                    cents(asMap(response.get("transaction_details")).get("total_paid_amount")),
                    cents(response.get("transaction_amount")), string(response, "payment_method_id"),
                    string(response, "payment_type_id"), string(response, "currency_id"),
                    string(data, "qr_code"), string(data, "qr_code_base64"), string(data, "ticket_url"));
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar a confirmação do Pix.");
        }
    }

    private Map<?, ?> postPayment(Map<String, Object> body, UUID idempotencyKey) {
        try {
            Map<?, ?> response = restClient.post().uri("/v1/payments")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-Idempotency-Key", idempotencyKey.toString())
                    .body(body).retrieve().body(Map.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago retornou uma resposta vazia.");
            }
            return response;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            logger.warn("Mercado Pago rejeitou POST /v1/payments: HTTP {}, compra {}", status, idempotencyKey);
            if (status == 400 || status == 401 || status == 403 || status == 404 || status == 422) {
                throw new PixPaymentRejectedException(exception);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível iniciar o pagamento Pix.");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível conectar ao Mercado Pago.");
        }
    }

    private static Map<?, ?> transactionData(Map<?, ?> payment) {
        return asMap(asMap(payment.get("point_of_interaction")).get("transaction_data"));
    }
    private static Map<?, ?> asMap(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static String string(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }
    private static Integer cents(Object amount) {
        if (amount == null) { return null; }
        try {
            return new BigDecimal(amount.toString()).movePointRight(2).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Mercado Pago retornou um valor inválido.");
        }
    }

    public record PixPayment(String paymentId, String copyPaste, String qrCodeBase64, String paymentUrl) {}
    public record PixPaymentStatus(
            String paymentId, String externalReference, String status, String statusDetail, Integer totalPaidCents,
            Integer transactionAmountCents, String paymentMethodId, String paymentTypeId, String currencyId,
            String copyPaste, String qrCodeBase64, String paymentUrl) {
        public boolean isApprovedPix() {
            return "approved".equals(status) && "accredited".equals(statusDetail)
                    && "pix".equals(paymentMethodId) && "bank_transfer".equals(paymentTypeId)
                    && "BRL".equals(currencyId);
        }
    }
    public static class PixPaymentRejectedException extends RuntimeException {
        public PixPaymentRejectedException(RestClientResponseException cause) {
            super(switch (cause.getStatusCode().value()) {
                case 403 -> "O Pix está indisponível por uma restrição na integração com o Mercado Pago.";
                case 401 -> "O Pix está indisponível porque o Mercado Pago não aceitou as credenciais da integração.";
                default -> "O Mercado Pago recusou a criação do Pix.";
            }, cause);
        }
    }
}
