package io.github.johnwhitton.digitalbanking;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

abstract class PostgresApiIntegrationSupport {

    static final String POSTGRES_IMAGE = "postgres:17.10-alpine3.23";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("digital_banking_api")
                    .withUsername("fixture_only_database_user")
                    .withPassword("fixture-only-database-password")
                    .withStartupTimeout(Duration.ofSeconds(60));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.clean-disabled", () -> "true");
    }
}
