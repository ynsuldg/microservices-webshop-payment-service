package se.iths.yunus.paymentservice.service;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import se.iths.yunus.paymentservice.client.PaymentOrderClient;
import se.iths.yunus.paymentservice.dto.CheckoutRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

@Service
public class CheckoutService {
    private final StripeClient stripe;
    private final PaymentOrderClient orders;
    private final String baseUrl;

    public record CheckoutResponse(Long orderId, String sessionId, String url) { }

    // Tar emot Stripe-klienten, orderklienten och en serverkonfigurerad returadress.
    public CheckoutService(StripeClient stripe, PaymentOrderClient orders,
                           @Value("${payment.return-url}") String baseUrl) {
        this.stripe = stripe;
        this.orders = orders;
        this.baseUrl = baseUrl;
    }

    // Skapar checkout från orderns priser och återanvänder session eller idempotensnyckel vid återförsök.
    public CheckoutResponse create(UUID reservationId, CheckoutRequest request) throws StripeException {
        var order = orders.prepare(reservationId, request);
        if (!Objects.equals(reservationId, order.reservationId()) ||
                !Objects.equals(request.customerName(), order.customerName()) ||
                !"PENDING_PAYMENT".equals(order.status()) || !order.stockReserved()) {
            throw new IllegalStateException("Payment order is not ready for Checkout.");
        }
        if (order.id() == null || order.items() == null || order.items().isEmpty()) {
            throw new IllegalStateException("Payment order has no items.");
        }
        long total = order.totalPrice().movePointRight(2).longValueExact();
        if (total <= 0) {
            throw new IllegalStateException("Payment total must be positive.");
        }

        Session session;
        if (order.stripeCheckoutSessionId() != null) {
            session = stripe.v1().checkout().sessions().retrieve(order.stripeCheckoutSessionId());
        } else {
            // Stripe kan glömma idempotensnycklar efter 24 timmar; skapa inte en ny betalning på en gammal avsikt.
            if (order.checkoutRequestedAt() == null ||
                    order.checkoutRequestedAt().isBefore(Instant.now().minus(Duration.ofHours(23)))) {
                throw new IllegalStateException("Checkout attempt is too old; it must be reconciled before retrying.");
            }
            var params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                    .setClientReferenceId(order.id().toString())
                    .putMetadata("order_id", order.id().toString())
                    .putMetadata("reservation_id", reservationId.toString())
                    .setSuccessUrl(baseUrl + "?reservation_id=" + reservationId + "&result=success")
                    .setCancelUrl(baseUrl + "?reservation_id=" + reservationId + "&result=cancel");
            BigDecimal sum = BigDecimal.ZERO;
            for (var item : order.items().stream().sorted(Comparator.comparing(i -> i.productId())).toList()) {
                if (item.quantity() <= 0 || item.price() == null || item.price().signum() < 0 ||
                        item.name() == null || item.name().isBlank()) {
                    throw new IllegalStateException("Invalid order item.");
                }
                long unitAmount = item.price().movePointRight(2).longValueExact();
                sum = sum.add(item.price().multiply(BigDecimal.valueOf(item.quantity())));
                params.addLineItem(SessionCreateParams.LineItem.builder().setQuantity((long) item.quantity())
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency("usd").setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(item.name()).build()).build()).build());
            }
            if (sum.compareTo(order.totalPrice()) != 0) {
                throw new IllegalStateException("Order total does not match its items.");
            }
            var options = RequestOptions.builder().setIdempotencyKey("checkout-" + reservationId).build();
            session = stripe.v1().checkout().sessions().create(params.build(), options);
        }

        if (!Boolean.FALSE.equals(session.getLivemode()) || session.getId() == null ||
                !session.getId().startsWith("cs_test_") || !"open".equals(session.getStatus()) ||
                !"usd".equals(session.getCurrency()) || !Objects.equals(session.getAmountTotal(), total) ||
                session.getMetadata() == null ||
                !order.id().toString().equals(session.getMetadata().get("order_id")) ||
                !reservationId.toString().equals(session.getMetadata().get("reservation_id")) || session.getUrl() == null) {
            throw new IllegalStateException("Stripe session does not match an open test Checkout for this order.");
        }
        orders.attachSession(reservationId, request.customerName(), session.getId());
        return new CheckoutResponse(order.id(), session.getId(), session.getUrl());
    }

    public record CheckoutStatus(Long orderId, String status) { }

    // Lämnar endast ut status när den verifierade kunden äger betalningsordern.
    public CheckoutStatus status(UUID id, String customerName) {
        se.iths.yunus.paymentservice.dto.PaymentOrder order;
        try {
            order = orders.get(id);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            // Bevarar saknad order som 404 utan att förväxla den med ett tillfälligt serverfel.
            if (exception.getStatusCode().value() == 404) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payment order not found.");
            }
            throw exception;
        }
        if (!Objects.equals(customerName, order.customerName())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return new CheckoutStatus(order.id(), order.status());
    }
}
