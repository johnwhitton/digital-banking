package io.github.johnwhitton.digitalbanking.controlplane.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-demo & local-signer")
public class LocalSignerProfileConflictConfiguration {

    @Bean
    Object localSignerProfileConflict() {
        throw new IllegalStateException(
                "local-demo and local-signer must not be active together");
    }
}
