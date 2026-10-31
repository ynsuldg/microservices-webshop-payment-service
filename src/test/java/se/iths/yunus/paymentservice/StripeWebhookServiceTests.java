package se.iths.yunus.paymentservice;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import se.iths.yunus.paymentservice.client.PaymentOrderClient;
import se.iths.yunus.paymentservice.dto.PaymentOrder;
import se.iths.yunus.paymentservice.service.StripeWebhookService;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTests {
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) private StripeClient stripe;
    @Mock private PaymentOrderClient orders;
    private StripeWebhookService service;
    private final UUID id = UUID.randomUUID();
    private static final String SECRET = "whsec_placeholder_for_tests";

    // Använder riktig signaturverifiering men mockar alla anrop till Stripe och order-service.
    @BeforeEach
    void setUp() {
        service = new StripeWebhookService(stripe, orders, SECRET);
    }

    // Kontrollerar att signerad betalning jämförs med ordern innan den bekräftas internt.
    @Test
    void confirmsSignedPaidSession() throws Exception {
        var payload = event("checkout.session.completed");
        stubSession("complete", "paid");
        service.handle(payload, signature(payload, Instant.now().getEpochSecond()));
        verify(orders).confirmPayment(id, "cs_test_webhook", 2500L, "usd");
        verify(orders, never()).expirePayment(any(), any());
    }

    // Kontrollerar att endast en faktiskt utgången och obetald session återställer reservationen.
    @Test
    void releasesSignedExpiredSession() throws Exception {
        var payload = event("checkout.session.expired");
        stubSession("expired", "unpaid");
        service.handle(payload, signature(payload, Instant.now().getEpochSecond()));
        verify(orders).expirePayment(id, "cs_test_webhook");
        verify(orders, never()).confirmPayment(any(), any(), any(), any());
    }

    // Kontrollerar att aktuell betalningsstatus vinner över en försenad utgångshändelse.
    @Test
    void doesNotReleaseSessionThatIsAlreadyPaid() throws Exception {
        var payload = event("checkout.session.expired");
        stubSession("complete", "paid");
        service.handle(payload, signature(payload, Instant.now().getEpochSecond()));
        verify(orders).confirmPayment(id, "cs_test_webhook", 2500L, "usd");
        verify(orders, never()).expirePayment(any(), any());
    }

    // Kontrollerar att fel signatur stoppas innan någon beroende tjänst anropas.
    @Test
    void rejectsInvalidSignature() {
        assertThrows(SignatureVerificationException.class,
                () -> service.handle(event("checkout.session.completed"), "t=1,v1=invalid"));
        verifyNoInteractions(stripe, orders);
    }

    // Kontrollerar att gamla signerade anrop nekas av Stripes tidskontroll.
    @Test
    void rejectsStaleSignature() throws Exception {
        var payload = event("checkout.session.completed");
        var signature = signature(payload, Instant.now().minusSeconds(1000).getEpochSecond());
        assertThrows(SignatureVerificationException.class, () -> service.handle(payload, signature));
        verifyNoInteractions(stripe, orders);
    }

    // Kontrollerar att ett signerat event ändå inte får bekräfta fel belopp.
    @Test
    void rejectsAmountMismatch() throws Exception {
        var payload = event("checkout.session.completed");
        var session = stubSession("complete", "paid");
        session.setAmountTotal(1L);
        assertThrows(IllegalArgumentException.class,
                () -> service.handle(payload, signature(payload, Instant.now().getEpochSecond())));
        verify(orders, never()).confirmPayment(any(), any(), any(), any());
    }

    // Skapar det aktuella sessionssvaret och den sparade ordern utan några nätverksanrop.
    private Session stubSession(String status, String paymentStatus) throws Exception {
        var session = new Session();
        session.setId("cs_test_webhook");
        session.setMode("payment");
        session.setLivemode(false);
        session.setStatus(status);
        session.setPaymentStatus(paymentStatus);
        session.setCurrency("usd");
        session.setAmountTotal(2500L);
        session.setMetadata(Map.of("order_id", "1", "reservation_id", id.toString()));
        when(stripe.v1().checkout().sessions().retrieve("cs_test_webhook")).thenReturn(session);
        when(orders.get(id)).thenReturn(new PaymentOrder(1L, id, "customer", "PENDING_PAYMENT", true,
                "cs_test_webhook", LocalDateTime.now(), Instant.now(), new BigDecimal("25.00"), List.of()));
        return session;
    }

    // Skapar ett minimalt Stripe-event med den installerade SDK-versionens API-version.
    private String event(String type) {
        return """
                {"id":"evt_test","object":"event","api_version":"%s","type":"%s","livemode":false,
                "data":{"object":{"id":"cs_test_webhook","object":"checkout.session"}}}
                """.formatted(Stripe.API_VERSION, type);
    }

    // Signerar exakt testets råa JSON enligt Stripes HMAC-format.
    private String signature(String payload, long timestamp) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var hash = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hash);
    }
}
