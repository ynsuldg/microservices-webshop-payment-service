package se.iths.yunus.paymentservice.service;

import tools.jackson.databind.json.JsonMapper;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import se.iths.yunus.paymentservice.client.PaymentOrderClient;
import java.util.Objects;
import java.util.UUID;

/** Verifierar Stripes råa webhook och hämtar aktuellt sessionsläge innan ordern ändras. */
@Service
public class StripeWebhookService {
    private final StripeClient stripe;
    private final PaymentOrderClient orders;
    private final String secret;

    // Tar emot Stripe-klient, orderklient och webhookens separata signeringshemlighet.
    public StripeWebhookService(StripeClient stripe, PaymentOrderClient orders,
                                @Value("${stripe.webhook-secret:}") String secret) {
        this.stripe = stripe;
        this.orders = orders;
        this.secret = secret;
    }

    // Behandlar signerade betalnings- och utgångshändelser utan att lita på retursidan eller gammal eventstatus.
    public void handle(String payload, String signature) throws StripeException {
        if (!secret.startsWith("whsec_")) {
            throw new IllegalStateException("STRIPE_WEBHOOK_SECRET is not configured.");
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Stripe signature is required.");
        }
        com.stripe.model.Event event;
        try {
            event = Webhook.constructEvent(payload, signature, secret);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Stripe event payload.", exception);
        }
        if (!Boolean.FALSE.equals(event.getLivemode())) {
            throw new IllegalArgumentException("Only Stripe test events are accepted.");
        }
        if (!"checkout.session.completed".equals(event.getType()) &&
                !"checkout.session.expired".equals(event.getType())) {
            return;
        }
        // Endast id läses ur det redan signerade eventet; övriga uppgifter hämtas direkt från Stripe.
        String sessionId;
        try {
            sessionId = JsonMapper.builder().build().readTree(payload)
                    .get("data").get("object").get("id").asText();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Webhook contains no Checkout session ID.", exception);
        }
        if (!sessionId.startsWith("cs_test_")) {
            throw new IllegalArgumentException("Only test Checkout sessions are accepted.");
        }
        var session = stripe.v1().checkout().sessions().retrieve(sessionId);
        if (!Boolean.FALSE.equals(session.getLivemode()) || !Objects.equals(sessionId, session.getId()) ||
                !"payment".equals(session.getMode()) || session.getMetadata() == null ||
                session.getMetadata().get("reservation_id") == null || session.getMetadata().get("order_id") == null) {
            throw new IllegalArgumentException("Invalid Stripe test Checkout session.");
        }
        var reservationId = UUID.fromString(session.getMetadata().get("reservation_id"));
        var order = orders.get(reservationId);
        if (!Objects.equals(reservationId, order.reservationId()) || order.id() == null ||
                !order.id().toString().equals(session.getMetadata().get("order_id")) ||
                (order.stripeCheckoutSessionId() != null && !order.stripeCheckoutSessionId().equals(sessionId)) ||
                !"usd".equals(session.getCurrency()) || order.totalPrice() == null ||
                !Objects.equals(session.getAmountTotal(), order.totalPrice().movePointRight(2).longValueExact())) {
            throw new IllegalArgumentException("Stripe session does not match the saved order.");
        }
        if ("complete".equals(session.getStatus()) && "paid".equals(session.getPaymentStatus())) {
            orders.confirmPayment(reservationId, sessionId, session.getAmountTotal(), session.getCurrency());
        } else if ("checkout.session.expired".equals(event.getType()) && "expired".equals(session.getStatus()) &&
                "unpaid".equals(session.getPaymentStatus())) {
            orders.expirePayment(reservationId, sessionId);
        } else if ("checkout.session.expired".equals(event.getType())) {
            throw new IllegalStateException("Stripe session has not expired and cannot be released.");
        }
    }
}
