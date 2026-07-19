package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.RedemptionAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.WalletTransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresWalletTransferRepository;
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

    static final WalletReference USER_1 =
            new WalletReference("synthetic-wallet:USER_1");
    static final WalletReference USER_2 =
            new WalletReference("synthetic-wallet:USER_2");
    static final WalletReference ADMIN =
            new WalletReference("synthetic-wallet:ADMIN");
    static final WalletReference ADMIN_REDEMPTION =
            new WalletReference("synthetic-wallet:ADMIN_REDEMPTION");

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
                                        properties.mintAuthorityKeyVersion()),
                                new LocalSolanaConfiguredSigner.ConfiguredKey(
                                        new KeyAlias(
                                                properties.transferAuthorityKeyAlias()),
                                        SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                        properties.transferAuthorityKeyFile(),
                                        properties.destinationOwner(),
                                        properties.transferAuthorityKeyVersion()),
                                new LocalSolanaConfiguredSigner.ConfiguredKey(
                                        new KeyAlias(properties.burnAuthorityKeyAlias()),
                                        SigningRequest.KeyRole.BURN_AUTHORITY,
                                        properties.burnAuthorityKeyFile(),
                                        properties.redemptionOwner(),
                                        properties.burnAuthorityKeyVersion()))));
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
                        properties.mintAuthorityKeyVersion(),
                        properties.transferDestinationOwner(),
                        properties.transferAuthorityKeyAlias(),
                        properties.transferAuthorityKeyVersion(),
                        properties.redemptionOwner(),
                        properties.burnAuthorityKeyAlias(),
                        properties.burnAuthorityKeyVersion(),
                        properties.walletRegistryVersion(), properties.assetId(),
                        properties.unitId(), properties.unitVersion(), properties.decimals(),
                        properties.policyVersion(), properties.preparationCommitment(),
                        properties.observationCommitment(),
                        properties.minimumFeePayerLamports(),
                        properties.maximumFeeLamports(), properties.requestTimeout()),
                Clock.systemUTC());
    }

    @Bean
    TokenOperationAcceptedDeliveryHandler localSolanaMintDeliveryHandler(
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
    TokenOperationAcceptedDeliveryHandler localSolanaBurnDeliveryHandler(
            OperationRepository operations,
            SavaSolanaMintChainAdapter chain,
            SigningAuthorityService signing,
            ClockPort clock,
            IdGenerator ids,
            LocalSolanaProperties properties) {
        return new TokenOperationAcceptedDeliveryHandler(
                operations, chain, signing, clock, ids,
                new TokenOperationAcceptedDeliveryHandler.Policy(
                        new KeyAlias(properties.burnAuthorityKeyAlias()),
                        properties.redemptionOwner(), Duration.ofMinutes(5),
                        properties.policyVersion(), OperationKind.BURN,
                        SigningRequest.Action.BURN,
                        SigningRequest.KeyRole.BURN_AUTHORITY,
                        SettlementNetwork.SOLANA,
                        SigningRequest.Mode.SOLANA_MESSAGE,
                        SigningRequest.Algorithm.ED25519));
    }

    @Bean
    WalletTransferRepository localSolanaWalletTransferRepository(DataSource dataSource) {
        return new PostgresWalletTransferRepository(dataSource);
    }

    @Bean
    WalletIdentityRegistry localSolanaWalletIdentityRegistry(
            LocalSolanaProperties properties) {
        Map<WalletReference, WalletIdentityRegistry.WalletIdentity> identities = Map.of(
                USER_1, userIdentity(
                        USER_1, properties.destinationOwner(),
                        new KeyAlias(properties.transferAuthorityKeyAlias()),
                        properties.transferAuthorityKeyVersion(),
                        properties.walletRegistryVersion()),
                USER_2, userIdentity(
                        USER_2, properties.transferDestinationOwner(),
                        new KeyAlias("local-solana:user-2-public"),
                        "local-solana-user-2-v1",
                        properties.walletRegistryVersion()),
                ADMIN, new WalletIdentityRegistry.WalletIdentity(
                        ADMIN, Set.of(ADMIN_REDEMPTION),
                        WalletIdentityRegistry.OwnerCategory.ADMIN,
                        SettlementNetwork.SOLANA, properties.redemptionOwner(),
                        new KeyAlias(properties.burnAuthorityKeyAlias()),
                        properties.walletRegistryVersion(),
                        properties.burnAuthorityKeyVersion(),
                        Set.of(WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY,
                                WalletIdentityRegistry.Purpose.BURN_AUTHORITY),
                        WalletIdentityRegistry.Status.ENABLED));
        return new WalletIdentityRegistry() {
            @Override public WalletIdentity resolve(WalletReference reference) {
                WalletIdentity identity = identities.get(reference);
                if (identity == null) {
                    throw new IllegalArgumentException("unknown local Solana wallet");
                }
                return identity;
            }

            @Override public List<WalletIdentity> identities() {
                return List.copyOf(identities.values());
            }
        };
    }

    @Bean
    WalletTransferAcceptanceService localSolanaWalletTransferAcceptanceService(
            WalletTransferRepository transfers,
            AssetUnitCatalog assets,
            WalletIdentityRegistry wallets,
            ClockPort clock,
            IdGenerator operationIds,
            TransferIdentityGenerator transferIds,
            LocalSolanaProperties properties) {
        return new WalletTransferAcceptanceService(
                transfers, assets, wallets, clock, operationIds, transferIds,
                new WalletTransferAcceptanceService.Policy(
                        properties.mintAddress(), "local-solana-token-v1",
                        properties.policyVersion()));
    }

    @Bean
    WalletTransferAcceptedDeliveryHandler localSolanaTransferDeliveryHandler(
            WalletTransferRepository transfers,
            SavaSolanaMintChainAdapter chain,
            SigningAuthorityService signing,
            WalletIdentityRegistry wallets,
            ClockPort clock) {
        return new WalletTransferAcceptedDeliveryHandler(
                transfers, chain, signing, wallets, clock, Duration.ofMinutes(5));
    }

    @Bean
    RedemptionAcceptedDeliveryHandler localSolanaRedemptionDeliveryHandler(
            OperationRepository operations,
            WalletTransferRepository transfers,
            WalletTransferAcceptanceService acceptance,
            TokenOperationAcceptedDeliveryHandler localSolanaBurnDeliveryHandler,
            WalletTransferAcceptedDeliveryHandler custody) {
        return new RedemptionAcceptedDeliveryHandler(
                operations, transfers, acceptance,
                localSolanaBurnDeliveryHandler, custody, USER_1, ADMIN_REDEMPTION);
    }

    @Bean
    @Primary
    OperationDeliveryHandler localSolanaDeliveryHandler(
            TokenOperationAcceptedDeliveryHandler localSolanaMintDeliveryHandler,
            RedemptionAcceptedDeliveryHandler redemption,
            OperationRepository operations) {
        return delivery -> {
            if (WalletTransferAcceptedDeliveryHandler.EVENT_TYPE.equals(
                    delivery.eventType())) {
                return redemption.handle(delivery);
            }
            return operations.findById(delivery.operationId())
                    .filter(operation -> operation.kind() == OperationKind.BURN)
                    .isPresent()
                    ? redemption.handle(delivery)
                    : localSolanaMintDeliveryHandler.handle(delivery);
        };
    }

    @Bean
    @Primary
    OperationDeliveryQueue localSolanaMintDeliveryQueue(DataSource dataSource) {
        return PostgresOperationDeliveryQueue.localSolana(dataSource);
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

    private static WalletIdentityRegistry.WalletIdentity userIdentity(
            WalletReference reference, String address, KeyAlias keyAlias,
            String keyVersion, String registryVersion) {
        return new WalletIdentityRegistry.WalletIdentity(
                reference, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.SOLANA, address, keyAlias,
                registryVersion, keyVersion,
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
    }
}
