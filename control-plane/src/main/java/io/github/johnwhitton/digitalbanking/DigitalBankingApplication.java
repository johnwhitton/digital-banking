package io.github.johnwhitton.digitalbanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring composition root for the digital banking reference control plane.
 */
@SpringBootApplication
public class DigitalBankingApplication {

    private DigitalBankingApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(DigitalBankingApplication.class, args);
    }
}
