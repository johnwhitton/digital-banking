package io.github.johnwhitton.digitalbanking;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReadinessSmokeTests {

    @Test
    void readinessEndpointReportsUp()
            throws ClassNotFoundException, IOException, InterruptedException {
        Class<?> applicationClass = Class.forName(
                "io.github.johnwhitton.digitalbanking.DigitalBankingApplication");

        try (ConfigurableApplicationContext context = SpringApplication.run(
                applicationClass,
                "--spring.main.banner-mode=off",
                "--server.port=0")) {
            int port = context.getEnvironment()
                    .getRequiredProperty("local.server.port", Integer.class);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UP\""));
        }
    }
}
