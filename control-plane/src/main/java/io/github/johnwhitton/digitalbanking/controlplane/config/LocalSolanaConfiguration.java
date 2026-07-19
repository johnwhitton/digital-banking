package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalSolanaConfiguredSigner;
import io.github.johnwhitton.digitalbanking.solana.sava.SavaSolanaMintChainAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-solana & !local-ethereum & !local-demo & !local-signer")
@EnableConfigurationProperties(LocalSolanaProperties.class)
public class LocalSolanaConfiguration {

    @Bean(destroyMethod = "close")
    LocalSolanaConfiguredSigner localSolanaConfiguredSigner(
            LocalSolanaProperties properties) {
        return new LocalSolanaConfiguredSigner(
                new LocalSolanaConfiguredSigner.Configuration(
                        properties.runtimeRoot(), List.of(
                                new LocalSolanaConfiguredSigner.ConfiguredKey(
                                        new KeyAlias(properties.feePayerKeyAlias()),
                                        SigningRequest.KeyRole.FEE_PAYER,
                                        properties.feePayerKeyFile(),
                                        properties.feePayerPublicKey(),
                                        properties.feePayerKeyVersion()),
                                new LocalSolanaConfiguredSigner.ConfiguredKey(
                                        new KeyAlias(properties.mintAuthorityKeyAlias()),
                                        SigningRequest.KeyRole.MINT_AUTHORITY,
                                        properties.mintAuthorityKeyFile(),
                                        properties.mintAuthorityPublicKey(),
                                        properties.mintAuthorityKeyVersion()))));
    }

    @Bean
    SigningRequestRepository localSolanaSigningRequestRepository(DataSource dataSource) {
        return new PostgresSigningRequestRepository(dataSource);
    }

    @Bean
    SigningAuthorizationPort localSolanaSigningAuthorization() {
        return request -> new SigningAuthorizationPort.Authorized(
                new EvidenceRef("internal:local-solana:authorization:"
                        + request.requestId().value()));
    }

    @Bean
    SigningIdentityGenerator localSolanaSigningIdentityGenerator() {
        return new SigningIdentityGenerator() {
            @Override public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(UUID.randomUUID());
            }
            @Override public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-solana-provider:" + UUID.randomUUID());
            }
        };
    }

    @Bean
    SigningAuthorityService localSolanaSigningAuthorityService(
            SigningRequestRepository requests,
            LocalSolanaConfiguredSigner signer,
            SigningAuthorizationPort authorization,
            SigningIdentityGenerator identities,
            ClockPort clock) {
        return new SigningAuthorityService(
                requests, signer, authorization, signer, identities, clock);
    }

    @Bean
    SavaSolanaMintChainAdapter localSolanaMintChainAdapter(
            DataSource dataSource, LocalSolanaProperties properties) {
        return SavaSolanaMintChainAdapter.local(
                dataSource, new SavaSolanaMintChainAdapter.Configuration(
                        properties.rpcUri(), properties.clusterIdentity(),
                        properties.mintAddress(), properties.destinationOwner(),
                        properties.feePayerPublicKey(), properties.feePayerKeyAlias(),
                        properties.feePayerKeyVersion(),
                        properties.mintAuthorityPublicKey(),
                        properties.mintAuthorityKeyAlias(),
                        properties.mintAuthorityKeyVersion(), properties.assetId(),
                        properties.unitId(), properties.unitVersion(), properties.decimals(),
                        properties.policyVersion(), properties.preparationCommitment(),
                        properties.observationCommitment(),
                        properties.minimumFeePayerLamports(),
                        properties.maximumFeeLamports(), properties.requestTimeout()),
                Clock.systemUTC());
    }

    @Bean
    OperationDeliveryHandler localSolanaMintDeliveryHandler(
            OperationRepository operations,
            SavaSolanaMintChainAdapter chain,
            SigningAuthorityService signing,
            ClockPort clock,
            IdGenerator ids,
            LocalSolanaProperties properties) {
        return new TokenOperationAcceptedDeliveryHandler(
                operations, chain, signing, clock, ids,
                new TokenOperationAcceptedDeliveryHandler.Policy(
                        new KeyAlias(properties.feePayerKeyAlias()),
                        properties.feePayerPublicKey(), Duration.ofMinutes(5),
                        properties.policyVersion(), OperationKind.MINT,
                        SigningRequest.Action.MINT, SigningRequest.KeyRole.FEE_PAYER,
                        SettlementNetwork.SOLANA,
                        SigningRequest.Mode.SOLANA_MESSAGE,
                        SigningRequest.Algorithm.ED25519));
    }

    @Bean
    @Primary
    OperationDeliveryQueue localSolanaMintDeliveryQueue(DataSource dataSource) {
        return PostgresOperationDeliveryQueue.mintOnly(dataSource);
    }

    @Bean
    @Primary
    AssetUnitCatalog localSolanaAssetUnitCatalog(LocalSolanaProperties properties) {
        AssetUnit unit = new AssetUnit(
                properties.assetId(), properties.unitId(), properties.unitVersion(),
                properties.decimals(), properties.maxAtomicUnits());
        return (assetId, unitId, version) -> assetId.equals(unit.assetId())
                && unitId.equals(unit.unitId()) && version == unit.version()
                ? java.util.Optional.of(unit) : java.util.Optional.empty();
    }
}
