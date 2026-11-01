package se.iths.yunus.paymentservice.controller;

import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import se.iths.yunus.paymentservice.dto.CheckoutRequest;
import se.iths.yunus.paymentservice.service.CheckoutService;
import java.util.UUID;

@RestController
public class PaymentController {

    private final CheckoutService checkoutService;

    // Tar emot tjänsten som kopplar orderns produkter till Stripe Checkout.
    public PaymentController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    // Visar information om tjänsten utan någon fristående testprodukt eller betalningsknapp.
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String checkoutPage() {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Test payment</title></head>
                <body>
                <h1>Webshop payments</h1>
                <p>Checkout is created from a saved webshop order through the protected backend API.</p>
                </body></html>
                """;
    }

    // Skapar checkout för riktiga orderrader; endast betrodda interna anrop får nå endpointen.
    @PostMapping("/internal/checkouts/{id}")
    public CheckoutService.CheckoutResponse createCheckout(@PathVariable UUID id,
            @Valid @RequestBody CheckoutRequest request) throws StripeException {
        return checkoutService.create(id, request);
    }

    // Hämtar sparad betalningsstatus för kunden som gatewayn har verifierat.
    @GetMapping("/internal/checkouts/{id}/status")
    public CheckoutService.CheckoutStatus status(@PathVariable UUID id, @RequestParam String customerName) {
        return checkoutService.status(id, customerName);
    }

    public record CancelRequest(@jakarta.validation.constraints.NotBlank String customerName) { }

    // Tar emot kundens uttryckliga avbrott via gatewayn och verifierar Stripe innan lagret frigörs.
    @PostMapping("/internal/checkouts/{id}/cancel")
    public CheckoutService.CheckoutStatus cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request)
            throws StripeException {
        return checkoutService.cancel(id, request.customerName());
    }

    // Visar retursidan utan att bekräfta betalning eller uppdatera någon order.
    @GetMapping(value = "/success", produces = MediaType.TEXT_HTML_VALUE)
    public String success() {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Checkout return</title></head>
                <body><h1>Returned from Stripe Checkout</h1>
                <p>This return page does not confirm payment. Server-side payment confirmation is required.</p>
                <a href="/">Back</a></body></html>
                """;
    }

    // Visar en sida när användaren avbryter checkout och ger möjlighet att försöka igen.
    @GetMapping(value = "/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public String cancel() {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Checkout cancelled</title></head>
                <body><h1>Checkout cancelled</h1><a href="/">Try again</a></body></html>
                """;
    }

    // Returnerar ett generellt felmeddelande när anropet till Stripe misslyckas.
    @ExceptionHandler(StripeException.class)
    public ResponseEntity<String> stripeError() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("Unable to open Stripe Checkout. Check the test key and connection, then try again.");
    }

    // Returnerar konflikt utan att markera ordern som betald när sessionen inte kan användas.
    @ExceptionHandler({IllegalStateException.class, ArithmeticException.class})
    public ResponseEntity<String> invalidCheckout() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("Checkout cannot be created for this payment order.");
    }
}
