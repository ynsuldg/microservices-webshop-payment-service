package se.iths.yunus.paymentservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import se.iths.yunus.paymentservice.dto.CheckoutRequest;
import se.iths.yunus.paymentservice.dto.PaymentOrder;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Kommunicerar med order-service med den interna serverhemligheten. */
@Component
public class PaymentOrderClient {
    private final RestClient client;
    private final String token;

    // Konfigurerar orderadressen och en begränsad väntetid för interna anrop.
    public PaymentOrderClient(@Value("${order.service-url}") String url,
                              @Value("${internal.service-token:}") String token) {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.token = token;
    }

    // Förbereder betalningsordern med samma reservations-id vid varje återförsök.
    public PaymentOrder prepare(UUID id, CheckoutRequest request) {
        checkToken();
        var order = client.post().uri("/internal/payment-orders/{id}/prepare", id)
                .header("X-Internal-Service-Token", token).body(request)
                .retrieve().body(PaymentOrder.class);
        if (order == null) {
            throw new IllegalStateException("Order service returned no payment order.");
        }
        return order;
    }

    // Sparar sessionskopplingen innan Stripe-adressen returneras till anroparen.
    public void attachSession(UUID id, String customerName, String sessionId) {
        checkToken();
        client.post().uri("/internal/payment-orders/{id}/session", id)
                .header("X-Internal-Service-Token", token)
                .body(Map.of("customerName", customerName, "sessionId", sessionId))
                .retrieve().toBodilessEntity();
    }

    // Läser den sparade ordern för att jämföra Stripe-sessionens id, belopp och valuta.
    public PaymentOrder get(UUID id) {
        checkToken();
        var order = client.get().uri("/internal/payment-orders/{id}", id)
                .header("X-Internal-Service-Token", token).retrieve().body(PaymentOrder.class);
        if (order == null) {
            throw new IllegalStateException("Order service returned no payment order.");
        }
        return order;
    }

    // Meddelar order-service om en verifierat betald Stripe-session.
    public void confirmPayment(UUID id, String sessionId, Long amountTotal, String currency) {
        checkToken();
        client.post().uri("/internal/payment-orders/{id}/paid", id)
                .header("X-Internal-Service-Token", token)
                .body(Map.of("sessionId", sessionId, "amountTotal", amountTotal, "currency", currency))
                .retrieve().toBodilessEntity();
    }

    // Meddelar order-service först när Stripe bekräftat att sessionen har löpt ut.
    public void expirePayment(UUID id, String sessionId) {
        checkToken();
        client.post().uri("/internal/payment-orders/{id}/expired", id)
                .header("X-Internal-Service-Token", token).body(Map.of("sessionId", sessionId))
                .retrieve().toBodilessEntity();
    }

    // Stoppar anrop utan konfigurerad intern serverhemlighet.
    private void checkToken() {
        if (token.length() < 32) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN must contain at least 32 characters.");
        }
    }
}
