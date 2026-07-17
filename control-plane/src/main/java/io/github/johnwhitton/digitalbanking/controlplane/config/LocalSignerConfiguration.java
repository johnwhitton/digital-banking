package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalEphemeralSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-signer")
@EnableConfigurationProperties(LocalSignerProperties.class)
public class LocalSignerConfiguration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LocalSignerConfiguration.class);

    @Bean(destroyMethod = "close")
    LocalEphemeralSigner localEphemeralSigner(LocalSignerProperties properties) {
        LOGGER.warn("LOCAL_EPHEMERAL signer is active for local development only; "
                + "keys are in-memory, disposable, and replaced after restart");
        return new LocalEphemeralSigner(
                new LocalEphemeralSigner.Configuration(
                        properties.evmRoles(), properties.solanaRoles(),
                        properties.maxSolanaMessageBytes()),
                Clock.systemUTC(), new SecureRandom());
    }

    @Bean
    SigningRequestRepository localSigningRequestRepository(DataSource dataSource) {
        return new PostgresSigningRequestRepository(dataSource);
    }

    @Bean
    SigningAuthorizationPort localSigningAuthorization() {
        return request -> new SigningAuthorizationPort.Authorized(
                new EvidenceRef("internal:local-signer:authorization:"
                        + request.requestId().value()));
    }

    @Bean
    SigningIdentityGenerator localSigningIdentityGenerator() {
        return new SigningIdentityGenerator() {
            @Override
            public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(UUID.randomUUID());
            }

            @Override
            public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-provider:" + UUID.randomUUID());
            }
        };
    }

    @Bean
    SigningAuthorityService localSigningAuthorityService(
            SigningRequestRepository requests,
            LocalEphemeralSigner signer,
            SigningAuthorizationPort authorization,
            SigningIdentityGenerator identities,
            ClockPort clock) {
        return new SigningAuthorityService(
                requests, signer, authorization, signer, identities, clock);
    }
}
