package se.iths.yunus.paymentservice;

import com.stripe.StripeClient;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionExpireParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import se.iths.yunus.paymentservice.client.PaymentOrderClient;
import se.iths.yunus.paymentservice.dto.CheckoutRequest;
import se.iths.yunus.paymentservice.dto.PaymentOrder;
import se.iths.yunus.paymentservice.service.CheckoutService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTests {
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) private StripeClient stripe;
    @Mock private PaymentOrderClient orders;
    private CheckoutService service;
    private final UUID id = UUID.randomUUID();
    private final CheckoutRequest request = new CheckoutRequest("customer",
            List.of(new CheckoutRequest.Item(42L, 2)));

    // Skapar checkout-tjänsten med mockad Stripe- och orderkommunikation.
    @BeforeEach
    void setUp() {
        service = new CheckoutService(stripe, orders, "http://localhost:5173/checkout/return");
    }

    // Bevarar 404 från order-service så att frontend kan hantera en gammal betalningsreferens.
    @Test
    void missingOrderStatusReturnsNotFound() {
        when(orders.get(id)).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not found",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.status(id, "customer"));
        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(stripe);
    }

    // Ett serverfel får aldrig tolkas som att ordern saknas och betalningsförsöket kan rensas.
    @Test
    void unavailableOrderServiceIsNotReportedAsMissing() {
        var failure = new org.springframework.web.client.HttpServerErrorException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        when(orders.get(id)).thenThrow(failure);
        assertSame(failure, assertThrows(org.springframework.web.client.HttpServerErrorException.class,
                () -> service.status(id, "customer")));
    }

    // Kontrollerar backendpris, antal, valuta, metadata, idempotensnyckel och sparad sessionskoppling.
    @Test
    void createsCheckoutFromSavedOrder() throws Exception {
        when(orders.prepare(id, request)).thenReturn(order(null, Instant.now(), new BigDecimal("12.50")));
        when(stripe.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(session());

        var response = service.create(id, request);

        var params = ArgumentCaptor.forClass(SessionCreateParams.class);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripe.v1().checkout().sessions()).create(params.capture(), options.capture());
        var line = params.getValue().getLineItems().get(0);
        assertEquals(1250L, line.getPriceData().getUnitAmount());
        assertEquals(2L, line.getQuantity());
        assertEquals("usd", line.getPriceData().getCurrency());
        assertEquals("Product", line.getPriceData().getProductData().getName());
        assertEquals(id.toString(), params.getValue().getMetadata().get("reservation_id"));
        assertEquals("http://localhost:5173/checkout/return?reservation_id=" + id + "&result=success",
                params.getValue().getSuccessUrl());
        assertEquals("checkout-" + id, options.getValue().getIdempotencyKey());
        verify(orders).attachSession(id, "customer", "cs_test_checkout");
        assertEquals("cs_test_checkout", response.sessionId());
    }

    // Kontrollerar att en redan sparad Stripe-session hämtas i stället för att skapa en ny.
    @Test
    void reusesSavedSession() throws Exception {
        when(orders.prepare(id, request)).thenReturn(order("cs_test_checkout", Instant.now(), new BigDecimal("12.50")));
        when(stripe.v1().checkout().sessions().retrieve("cs_test_checkout")).thenReturn(session());
        service.create(id, request);
        verify(stripe.v1().checkout().sessions(), never()).create(any(SessionCreateParams.class), any(RequestOptions.class));
    }

    // Kontrollerar att ett tappat svar från order-service inte ändrar nästa anrops idempotensnyckel.
    @Test
    void retriesLostAttachmentWithSameStripeKey() throws Exception {
        when(orders.prepare(id, request)).thenReturn(order(null, Instant.now(), new BigDecimal("12.50")));
        when(stripe.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(session());
        doThrow(new IllegalStateException("Simulated lost response")).doNothing()
                .when(orders).attachSession(id, "customer", "cs_test_checkout");
        assertThrows(IllegalStateException.class, () -> service.create(id, request));
        service.create(id, request);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripe.v1().checkout().sessions(), times(2)).create(any(SessionCreateParams.class), options.capture());
        assertEquals(options.getAllValues().get(0).getIdempotencyKey(), options.getAllValues().get(1).getIdempotencyKey());
    }

    // Kontrollerar att en gammal betalningsavsikt inte skapar en ny session efter idempotensnyckelns tidsgräns.
    @Test
    void rejectsOldAttemptWithoutSavedSession() {
        when(orders.prepare(id, request)).thenReturn(order(null, Instant.now().minus(Duration.ofHours(25)), new BigDecimal("12.50")));
        assertThrows(IllegalStateException.class, () -> service.create(id, request));
        verifyNoInteractions(stripe);
    }

    // Kontrollerar att priset inte avrundas till ett annat belopp vid omvandling till cent.
    @Test
    void rejectsFractionalCents() {
        when(orders.prepare(id, request)).thenReturn(order(null, Instant.now(), new BigDecimal("12.501")));
        assertThrows(ArithmeticException.class, () -> service.create(id, request));
        verifyNoInteractions(stripe);
    }

    // Kontrollerar att en session med fel reservationskoppling aldrig sparas på ordern.
    @Test
    void rejectsMismatchedStripeSession() throws Exception {
        when(orders.prepare(id, request)).thenReturn(order(null, Instant.now(), new BigDecimal("12.50")));
        var session = session();
        session.setMetadata(Map.of("order_id", "999", "reservation_id", id.toString()));
        when(stripe.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(session);
        assertThrows(IllegalStateException.class, () -> service.create(id, request));
        verify(orders, never()).attachSession(any(), any(), any());
    }

    // Kontrollerar att en kund inte kan läsa en annan kunds betalningsstatus.
    @Test
    void rejectsStatusForAnotherCustomer() {
        when(orders.get(id)).thenReturn(order(null, Instant.now(), new BigDecimal("12.50")));
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.status(id, "someone-else"));
        assertEquals(403, error.getStatusCode().value());
    }

    // Kontrollerar att status kommer från den sparade ordern och inte från returadressens parametrar.
    @Test
    void returnsStoredStatusForOwner() {
        when(orders.get(id)).thenReturn(order(null, Instant.now(), new BigDecimal("12.50")));
        assertEquals("PENDING_PAYMENT", service.status(id, "customer").status());
        verifyNoInteractions(stripe);
    }

    // Frigör ordern först efter att Stripe bekräftat utgång och tål ett upprepat avbrott.
    @Test
    void cancelsOpenCheckoutBeforeReleasingReservation() throws Exception {
        var pending = order("cs_test_checkout", Instant.now(), new BigDecimal("12.50"));
        var cancelled = closedOrder("CANCELLED");
        when(orders.get(id)).thenReturn(pending, cancelled, cancelled);
        var open = session();
        open.setMode("payment");
        open.setPaymentStatus("unpaid");
        var expired = session();
        expired.setMode("payment");
        expired.setStatus("expired");
        expired.setPaymentStatus("unpaid");
        var sessions = stripe.v1().checkout().sessions();
        when(sessions.retrieve("cs_test_checkout")).thenReturn(open);
        when(sessions.expire(eq("cs_test_checkout"), any(SessionExpireParams.class))).thenReturn(expired);

        assertEquals("CANCELLED", service.cancel(id, "customer").status());
        assertEquals("CANCELLED", service.cancel(id, "customer").status());
        var sequence = inOrder(sessions, orders);
        sequence.verify(sessions).expire(eq("cs_test_checkout"), any(SessionExpireParams.class));
        sequence.verify(orders).expirePayment(id, "cs_test_checkout");
        verify(orders, times(1)).expirePayment(id, "cs_test_checkout");
        verify(orders, never()).confirmPayment(any(), any(), any(), any());
    }

    // Bekräftar en betalning som hann slutföras medan kunden försökte avbryta.
    @Test
    void paymentWinsRaceAgainstCancellation() throws Exception {
        when(orders.get(id)).thenReturn(order("cs_test_checkout", Instant.now(), new BigDecimal("12.50")),
                closedOrder("PAID"));
        var open = session();
        open.setMode("payment");
        open.setPaymentStatus("unpaid");
        var paid = session();
        paid.setMode("payment");
        paid.setStatus("complete");
        paid.setPaymentStatus("paid");
        var sessions = stripe.v1().checkout().sessions();
        when(sessions.retrieve("cs_test_checkout")).thenReturn(open, paid);
        when(sessions.expire(eq("cs_test_checkout"), any(SessionExpireParams.class)))
                .thenThrow(new com.stripe.exception.InvalidRequestException("Not open", null, null, null, 400, null));

        assertEquals("PAID", service.cancel(id, "customer").status());
        verify(orders).confirmPayment(id, "cs_test_checkout", 2500L, "usd");
        verify(orders, never()).expirePayment(any(), any());
    }

    // Tillåter återförsök när Stripe redan stängts men det tidigare ordersvaret försvann.
    @Test
    void reconcilesAlreadyExpiredSessionWithoutExpiringAgain() throws Exception {
        when(orders.get(id)).thenReturn(order("cs_test_checkout", Instant.now(), new BigDecimal("12.50")),
                closedOrder("CANCELLED"));
        var expired = session();
        expired.setMode("payment");
        expired.setStatus("expired");
        expired.setPaymentStatus("unpaid");
        when(stripe.v1().checkout().sessions().retrieve("cs_test_checkout")).thenReturn(expired);

        assertEquals("CANCELLED", service.cancel(id, "customer").status());
        verify(stripe.v1().checkout().sessions(), never()).expire(anyString(), any(SessionExpireParams.class));
        verify(orders).expirePayment(id, "cs_test_checkout");
    }

    // Nekar annan kund innan något Stripe-anrop kan påverka ordern.
    @Test
    void rejectsCancellationForAnotherCustomer() {
        when(orders.get(id)).thenReturn(order("cs_test_checkout", Instant.now(), new BigDecimal("12.50")));
        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.cancel(id, "someone-else"));
        assertEquals(403, failure.getStatusCode().value());
        verifyNoInteractions(stripe);
        verify(orders, never()).expirePayment(any(), any());
    }

    // Frisläpper inget om ett tappat Checkout-svar ännu inte säkert kopplats till ordern.
    @Test
    void refusesCancellationWithoutLinkedStripeSession() {
        when(orders.get(id)).thenReturn(order(null, Instant.now(), new BigDecimal("12.50")));
        assertThrows(IllegalStateException.class, () -> service.cancel(id, "customer"));
        verifyNoInteractions(stripe);
        verify(orders, never()).expirePayment(any(), any());
    }

    // Förhindrar att en felkopplad eller manipulerad session stängs.
    @Test
    void refusesCancellationForMismatchedSession() throws Exception {
        when(orders.get(id)).thenReturn(order("cs_test_checkout", Instant.now(), new BigDecimal("12.50")));
        var wrong = session();
        wrong.setMode("payment");
        wrong.setAmountTotal(1L);
        when(stripe.v1().checkout().sessions().retrieve("cs_test_checkout")).thenReturn(wrong);
        assertThrows(IllegalStateException.class, () -> service.cancel(id, "customer"));
        verify(stripe.v1().checkout().sessions(), never()).expire(anyString(), any(SessionExpireParams.class));
        verify(orders, never()).expirePayment(any(), any());
    }

    // Frisläpper inte lagret om Stripe inte kan bekräfta att sessionen stängts.
    @Test
    void keepsReservationWhenStripeExpirationFails() throws Exception {
        when(orders.get(id)).thenReturn(order("cs_test_checkout", Instant.now(), new BigDecimal("12.50")));
        var open = session();
        open.setMode("payment");
        open.setPaymentStatus("unpaid");
        when(stripe.v1().checkout().sessions().retrieve("cs_test_checkout")).thenReturn(open);
        var failure = mock(com.stripe.exception.StripeException.class);
        when(stripe.v1().checkout().sessions().expire(eq("cs_test_checkout"), any(SessionExpireParams.class)))
                .thenThrow(failure);
        assertSame(failure, assertThrows(com.stripe.exception.StripeException.class,
                () -> service.cancel(id, "customer")));
        verify(orders, never()).expirePayment(any(), any());
        verify(orders, never()).confirmPayment(any(), any(), any(), any());
    }

    // Skapar serverns terminala svar utan att ändra varukorg eller Stripe i testerna.
    private PaymentOrder closedOrder(String status) {
        var order = order("cs_test_checkout", Instant.now(), new BigDecimal("12.50"));
        return new PaymentOrder(order.id(), id, "customer", status, false, order.stripeCheckoutSessionId(),
                order.orderDate(), order.checkoutRequestedAt(), order.totalPrice(), order.items());
    }

    // Skapar en sparad betalningsorder med serverstyrda produktuppgifter.
    private PaymentOrder order(String sessionId, Instant attemptedAt, BigDecimal price) {
        return new PaymentOrder(1L, id, "customer", "PENDING_PAYMENT", true, sessionId, LocalDateTime.now(),
                attemptedAt, price.multiply(BigDecimal.valueOf(2)),
                List.of(new PaymentOrder.Item(42L, "Product", price, 2)));
    }

    // Skapar Stripes mockade testsvar utan nätverksanrop eller riktig betalning.
    private Session session() {
        var session = new Session();
        session.setId("cs_test_checkout");
        session.setLivemode(false);
        session.setStatus("open");
        session.setCurrency("usd");
        session.setAmountTotal(2500L);
        session.setUrl("https://checkout.stripe.com/test-session");
        session.setMetadata(Map.of("order_id", "1", "reservation_id", id.toString()));
        return session;
    }
}
