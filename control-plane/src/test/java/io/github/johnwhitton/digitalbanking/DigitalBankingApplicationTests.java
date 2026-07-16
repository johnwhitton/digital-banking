package io.github.johnwhitton.digitalbanking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalBankingApplicationTests {

    @Test
    void applicationEntryPointLoadsASpringContext() throws ClassNotFoundException {
        Class<?> applicationClass = Class.forName(
                "io.github.johnwhitton.digitalbanking.DigitalBankingApplication");

        assertNotNull(applicationClass.getAnnotation(SpringBootApplication.class));

        try (ConfigurableApplicationContext context = SpringApplication.run(
                applicationClass,
                "--spring.main.banner-mode=off",
                "--server.port=0")) {
            assertTrue(context.isActive());
        }
    }
}
