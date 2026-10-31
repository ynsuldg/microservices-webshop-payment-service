package se.iths.yunus.paymentservice;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import se.iths.yunus.paymentservice.client.PaymentOrderClient;
import se.iths.yunus.paymentservice.dto.CheckoutRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PaymentOrderClientTests {
    // Kontrollerar JSON, tidsformat och intern header mot en tillfällig lokal testserver, inte riktig order-service.
    @Test
    void readsOrderAndAttachesSessionOverHttp() throws Exception {
        var id = UUID.randomUUID();
        var token = "internal-test-token-not-for-real-use-123456";
        var receivedToken = new AtomicReference<String>();
        var attachedBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/payment-orders/" + id + "/prepare", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            exchange.getRequestBody().readAllBytes();
            var json = """
                    {"id":1,"reservationId":"%s","customerName":"customer","status":"PENDING_PAYMENT",
                    "stockReserved":true,"stripeCheckoutSessionId":null,"orderDate":"2026-10-30T12:00:00",
                    "checkoutRequestedAt":"2026-10-30T12:00:00Z","totalPrice":25.00,
                    "items":[{"productId":42,"name":"Product","price":12.50,"quantity":2}]}
                    """.formatted(id).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.createContext("/internal/payment-orders/" + id + "/session", exchange -> {
            attachedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new PaymentOrderClient("http://127.0.0.1:" + server.getAddress().getPort(), token);
            var order = client.prepare(id, new CheckoutRequest("customer", List.of(new CheckoutRequest.Item(42L, 2))));
            assertEquals(id, order.reservationId());
            assertEquals(Instant.parse("2026-10-30T12:00:00Z"), order.checkoutRequestedAt());
            assertEquals(42L, order.items().get(0).productId());
            assertEquals(token, receivedToken.get());
            client.attachSession(id, "customer", "cs_test_checkout");
            assertTrue(attachedBody.get().contains("cs_test_checkout"));
        } finally {
            server.stop(0);
        }
    }
}
