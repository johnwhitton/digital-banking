package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.nio.file.Path;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("digital-banking.local-demo-identity")
record LocalDemoIdentityProperties(
        @NotNull Path senderTokenFile,
        @NotNull Path operatorTokenFile) {
}
