package io.github.johnwhitton.digitalbanking.controlplane.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-demo-environment")
@EnableConfigurationProperties(LocalDemoIdentityProperties.class)
class LocalDemoIdentityConfiguration {

    @Bean
    LocalDemoBearerAuthenticator localDemoBearerAuthenticator(
            LocalDemoIdentityProperties properties) {
        return new LocalDemoBearerAuthenticator(
                properties.senderTokenFile(), properties.operatorTokenFile());
    }

    @Bean
    LocalDemoBearerTokenAuthenticationFilter localDemoBearerTokenAuthenticationFilter(
            LocalDemoBearerAuthenticator authenticator) {
        return new LocalDemoBearerTokenAuthenticationFilter(authenticator);
    }
}
