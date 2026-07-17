package io.github.johnwhitton.digitalbanking;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DigitalBankingApplicationTests extends PostgresApiIntegrationSupport {

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void applicationEntryPointLoadsASpringContext() throws ClassNotFoundException {
        Class<?> applicationClass = Class.forName(
                "io.github.johnwhitton.digitalbanking.DigitalBankingApplication");

        assertNotNull(applicationClass.getAnnotation(SpringBootApplication.class));

        assertTrue(context.isRunning());
    }

    @Test
    void signingAuthorityHasNoRuntimeProviderOrPublicUseCaseBean() {
        assertTrue(context.getBeansOfType(SignerPort.class).isEmpty());
        assertTrue(context.getBeansOfType(SigningAuthorityService.class).isEmpty());
    }
}
