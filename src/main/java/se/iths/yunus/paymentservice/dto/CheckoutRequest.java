package se.iths.yunus.paymentservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Betrodd backend skickar kundidentitet samt produkt-id:n och antal, aldrig priser. */
public record CheckoutRequest(@NotBlank String customerName,
                              @NotEmpty List<@NotNull @Valid Item> items) {
    public record Item(@NotNull @Positive Long productId, @Positive int quantity) { }
}
