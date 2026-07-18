package io.github.johnwhitton.digitalbanking;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.AccountingApplicationService;
import io.github.johnwhitton.digitalbanking.application.MockBankApplicationService;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowApplicationService;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.controlplane.api.LocalMockBankController;
import io.github.johnwhitton.digitalbanking.controlplane.api.UsdzelleWorkflowController;
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

    @Test
    void localFinancialCapabilityIsAbsentWithoutTheExplicitProfile() {
        assertTrue(context.getBeansOfType(LocalMockBankController.class).isEmpty());
        assertTrue(context.getBeansOfType(MockBankApplicationService.class).isEmpty());
        assertTrue(context.getBeansOfType(AccountingApplicationService.class).isEmpty());
        assertTrue(context.getBeansOfType(UsdzelleWorkflowController.class).isEmpty());
        assertTrue(context.getBeansOfType(
                UsdzelleWorkflowApplicationService.class).isEmpty());
    }
}
