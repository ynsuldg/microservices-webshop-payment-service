package se.iths.yunus.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    // Kontrollerar att applikationens Spring-kontext kan startas.
    @Test
    void contextLoads() {
    }

    // Kontrollerar att rotadressen visar samma statussida som övriga backendtjänster.
    @Test
    void rootPageShowsServiceStatus() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment Service")))
                .andExpect(content().string(containsString("Service is running")));
    }

    // Kontrollerar att den interna checkout-endpointen kräver serverhemlighet.
    @Test
    void checkoutRejectsRequestsWithoutInternalToken() throws Exception {
        mockMvc.perform(post("/internal/checkouts/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized());
    }

    // Kontrollerar att webhooken nås utan CSRF-token men nekar anrop utan Stripe-signatur.
    @Test
    void webhookRejectsMissingStripeSignature() throws Exception {
        mockMvc.perform(post("/stripe/webhook").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

}
