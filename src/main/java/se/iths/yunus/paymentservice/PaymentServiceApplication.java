package se.iths.yunus.paymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentServiceApplication {

    // Startar payment-service med Spring Boot.
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

}
