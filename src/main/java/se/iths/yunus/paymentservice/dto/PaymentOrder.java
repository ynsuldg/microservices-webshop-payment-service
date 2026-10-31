package se.iths.yunus.paymentservice.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Sparade orderuppgifter och backendpriser som används för Stripe Checkout. */
public record PaymentOrder(Long id, UUID reservationId, String customerName, String status,
                           boolean stockReserved, String stripeCheckoutSessionId, LocalDateTime orderDate,
                           java.time.Instant checkoutRequestedAt, BigDecimal totalPrice, List<Item> items) {
    public record Item(Long productId, String name, BigDecimal price, int quantity) { }
}
