package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.util.Objects;
import java.util.Set;

import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("digital-banking.local-signer")
public record LocalSignerProperties(
        Set<SigningRequest.KeyRole> evmRoles,
        Set<SettlementNetwork> evmNetworks,
        Set<SigningRequest.KeyRole> solanaRoles,
        Set<SettlementNetwork> solanaNetworks,
        int maxSolanaMessageBytes) {

    public LocalSignerProperties {
        evmRoles = copy(evmRoles);
        evmNetworks = copy(evmNetworks);
        solanaRoles = copy(solanaRoles);
        solanaNetworks = copy(solanaNetworks);
        if (evmRoles.isEmpty() || solanaRoles.isEmpty()) {
            throw new IllegalArgumentException("local signer role allowlists are required");
        }
        if (!evmNetworks.equals(Set.of(SettlementNetwork.ETHEREUM))) {
            throw new IllegalArgumentException(
                    "local signer EVM network allowlist must contain only ETHEREUM");
        }
        if (!solanaNetworks.equals(Set.of(SettlementNetwork.SOLANA))) {
            throw new IllegalArgumentException(
                    "local signer Solana network allowlist must contain only SOLANA");
        }
        if (maxSolanaMessageBytes < 1 || maxSolanaMessageBytes > 65_536) {
            throw new IllegalArgumentException(
                    "local signer Solana message limit must be between 1 and 65536 bytes");
        }
    }

    private static <T> Set<T> copy(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(Objects.requireNonNull(values));
    }
}
