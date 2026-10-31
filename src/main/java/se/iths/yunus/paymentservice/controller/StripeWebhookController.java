package se.iths.yunus.paymentservice.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import se.iths.yunus.paymentservice.service.StripeWebhookService;

@RestController
@RequiredArgsConstructor
public class StripeWebhookController {
    private final StripeWebhookService service;

    // Tar emot rå request-body så att Stripes signatur kan verifieras utan ändrad JSON.
    @PostMapping("/stripe/webhook")
    public ResponseEntity<Void> handle(@RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) throws StripeException {
        service.handle(payload, signature);
        return ResponseEntity.ok().build();
    }

    // Nekar felaktig signatur eller ogiltiga betalningsuppgifter utan att avslöja hemligheter.
    @ExceptionHandler({SignatureVerificationException.class, IllegalArgumentException.class})
    public ResponseEntity<String> invalidWebhook() {
        return ResponseEntity.badRequest().body("Invalid Stripe webhook.");
    }

    // Ber Stripe försöka igen när en beroende tjänst eller säker betalningsbehandling inte är tillgänglig.
    @ExceptionHandler({StripeException.class, RestClientException.class, IllegalStateException.class})
    public ResponseEntity<String> temporaryFailure() {
        return ResponseEntity.status(503).body("Payment processing is temporarily unavailable.");
    }
}
