package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.solana.sava.SavaSolanaMintChainAdapter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("digital-banking.local-solana")
public record LocalSolanaProperties(
        URI rpcUri,
        String clusterIdentity,
        String mintAddress,
        String destinationOwner,
        Path runtimeRoot,
        Path feePayerKeyFile,
        String feePayerPublicKey,
        String feePayerKeyAlias,
        String feePayerKeyVersion,
        Path mintAuthorityKeyFile,
        String mintAuthorityPublicKey,
        String mintAuthorityKeyAlias,
        String mintAuthorityKeyVersion,
        String assetId,
        String unitId,
        int unitVersion,
        int decimals,
        BigInteger maxAtomicUnits,
        String policyVersion,
        SavaSolanaMintChainAdapter.CommitmentLevel preparationCommitment,
        SavaSolanaMintChainAdapter.CommitmentLevel observationCommitment,
        BigInteger minimumFeePayerLamports,
        BigInteger maximumFeeLamports,
        Duration requestTimeout) {

    public LocalSolanaProperties {
        Objects.requireNonNull(runtimeRoot, "runtimeRoot");
        Objects.requireNonNull(feePayerKeyFile, "feePayerKeyFile");
        Objects.requireNonNull(mintAuthorityKeyFile, "mintAuthorityKeyFile");
        if (maxAtomicUnits == null || maxAtomicUnits.signum() <= 0) {
            throw new IllegalArgumentException(
                    "local Solana maximum atomic units must be positive");
        }
        new SavaSolanaMintChainAdapter.Configuration(
                rpcUri, clusterIdentity, mintAddress, destinationOwner,
                feePayerPublicKey, feePayerKeyAlias, feePayerKeyVersion,
                mintAuthorityPublicKey, mintAuthorityKeyAlias, mintAuthorityKeyVersion,
                assetId, unitId, unitVersion, decimals, policyVersion,
                preparationCommitment, observationCommitment,
                minimumFeePayerLamports, maximumFeeLamports, requestTimeout);
    }
}
