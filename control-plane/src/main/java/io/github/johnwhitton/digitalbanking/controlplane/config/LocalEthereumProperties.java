package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.math.BigInteger;
import java.net.URI;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("digital-banking.local-ethereum")
public record LocalEthereumProperties(
        String rpcUrl,
        long chainId,
        String contractAddress,
        String recipientAddress,
        BigInteger maxPriorityFeePerGas,
        BigInteger maxFeePerGas,
        BigInteger gasLimit,
        int confirmations,
        String assetId,
        String unitId,
        int unitVersion,
        int decimals,
        BigInteger maxAtomicUnits,
        String policyVersion,
        String redemptionSourceWallet) {

    public LocalEthereumProperties {
        URI endpoint;
        try {
            endpoint = URI.create(rpcUrl);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("local Ethereum RPC URL is invalid", failure);
        }
        Set<String> loopbackHosts = Set.of("127.0.0.1", "localhost", "::1", "[::1]");
        if (!"http".equals(endpoint.getScheme())
                || !loopbackHosts.contains(endpoint.getHost())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || endpoint.getPort() < 1) {
            throw new IllegalArgumentException(
                    "local Ethereum RPC URL must be an uncredentialed loopback HTTP endpoint");
        }
        if (chainId != 31_337L) {
            throw new IllegalArgumentException("local Ethereum chain ID must be 31337");
        }
        requireText(contractAddress, "contractAddress");
        recipientAddress = recipientAddress == null ? "" : recipientAddress;
        requireNonNegative(maxPriorityFeePerGas, "maxPriorityFeePerGas");
        requireNonNegative(maxFeePerGas, "maxFeePerGas");
        if (maxFeePerGas.compareTo(maxPriorityFeePerGas) < 0) {
            throw new IllegalArgumentException("maxFeePerGas must cover the priority fee");
        }
        if (gasLimit == null || gasLimit.signum() <= 0) {
            throw new IllegalArgumentException("gasLimit must be positive");
        }
        if (confirmations < 1 || confirmations > 100) {
            throw new IllegalArgumentException("confirmations must be between 1 and 100");
        }
        requireText(assetId, "assetId");
        requireText(unitId, "unitId");
        if (unitVersion < 1 || decimals != 2
                || maxAtomicUnits == null || maxAtomicUnits.signum() <= 0) {
            throw new IllegalArgumentException("local Ethereum asset/unit policy is invalid");
        }
        requireText(policyVersion, "policyVersion");
        if (redemptionSourceWallet == null
                || !redemptionSourceWallet.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,109}")) {
            throw new IllegalArgumentException(
                    "redemptionSourceWallet must be a bounded synthetic-wallet name");
        }
    }

    String requiredMintRecipientAddress() {
        requireText(recipientAddress, "recipientAddress");
        return recipientAddress;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
    }

    private static void requireNonNegative(BigInteger value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
