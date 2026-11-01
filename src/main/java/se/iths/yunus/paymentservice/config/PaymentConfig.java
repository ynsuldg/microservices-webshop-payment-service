package se.iths.yunus.paymentservice.config;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PaymentConfig {

    // Skapar Stripe-klienten och tillåter endast en hemlig testnyckel.
    @Bean
    public StripeClient stripeClient(@Value("${stripe.secret-key}") String secretKey) {
        if (!secretKey.startsWith("sk_test_")) {
            throw new IllegalArgumentException("STRIPE_SECRET_KEY must be a test key starting with sk_test_.");
        }
        return new StripeClient(secretKey);
    }

    // Tillåter informations- och retursidor men behåller CSRF-skyddet och nekar andra publika anrop.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/index.html").permitAll()
                .requestMatchers("/", "/success", "/cancel", "/error", "/stripe/webhook").permitAll()
                .anyRequest().denyAll())
                // Webhooken verifieras med Stripes signatur och använder inte webbläsarens sessionscookie.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/stripe/webhook"))
                .build();
    }
}
