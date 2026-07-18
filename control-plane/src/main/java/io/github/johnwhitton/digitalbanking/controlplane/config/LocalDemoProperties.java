package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("digital-banking.local-demo")
public record LocalDemoProperties(
        long chainId,
        String adminRedemptionKeyAlias,
        List<Wallet> wallets) {

    public LocalDemoProperties {
        wallets = wallets == null ? List.of() : List.copyOf(wallets);
        if (chainId != 31337) {
            throw new IllegalArgumentException(
                    "local demo requires local chain ID 31337");
        }
        if (!"ADMIN".equals(adminRedemptionKeyAlias)) {
            throw new IllegalArgumentException(
                    "local demo ADMIN_REDEMPTION must resolve to ADMIN");
        }
        if (wallets.isEmpty()) {
            throw new IllegalArgumentException(
                    "local demo wallet configuration is required");
        }
    }

    @Override
    public String toString() {
        return "LocalDemoProperties[chainId=" + chainId
                + ", wallets=" + wallets.size() + ", secrets=[REDACTED]]";
    }

    public record Wallet(
            String reference,
            Set<String> aliases,
            WalletIdentityRegistry.OwnerCategory ownerCategory,
            SettlementNetwork network,
            String keyReference,
            @JsonIgnore SecretValue privateKey,
            String expectedAddress,
            Set<WalletIdentityRegistry.Purpose> allowedPurposes,
            boolean enabled) {

        public Wallet {
            aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
            allowedPurposes = allowedPurposes == null
                    ? Set.of() : Set.copyOf(allowedPurposes);
            if (reference == null || reference.isBlank()
                    || ownerCategory == null || network == null
                    || keyReference == null || keyReference.isBlank()
                    || privateKey == null || expectedAddress == null
                    || expectedAddress.isBlank() || allowedPurposes.isEmpty()) {
                throw new IllegalArgumentException(
                        "local demo wallet metadata must be complete");
            }
        }

        @Override
        public String toString() {
            return "Wallet[reference=" + reference + ", privateKey=[REDACTED]]";
        }
    }

    void destroySecrets() {
        wallets.forEach(wallet -> wallet.privateKey().destroy());
    }

    public static final class SecretValue {

        private final char[] value;

        public SecretValue(String value) {
            this.value = Objects.requireNonNullElse(value, "").toCharArray();
        }

        @JsonIgnore
        char[] copy() {
            return value.clone();
        }

        void destroy() {
            Arrays.fill(value, '\0');
        }

        @Override
        public String toString() {
            return "[REDACTED]";
        }
    }
}
