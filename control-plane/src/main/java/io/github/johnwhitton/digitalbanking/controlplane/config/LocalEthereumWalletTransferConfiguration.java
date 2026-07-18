package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.RedemptionAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.WalletTransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.UsdzelleWorkflowAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowContextResolver;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.PostgresWalletTransferRepository;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jEthereumBurnChainAdapter;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jEthereumWalletTransferChainAdapter;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;

/** Local-only composition for the standalone user-wallet transfer slice. */
@Configuration(proxyBeanMethods = false)
@Profile("local-demo & local-ethereum & !local-signer")
@EnableConfigurationProperties(LocalEthereumProperties.class)
public class LocalEthereumWalletTransferConfiguration {

    static final String CONTRACT_VERSION = "local-usdzelle-v1";
    static final String POLICY_VERSION = "local-ethereum-wallet-transfer-v1";
    static final String REDEMPTION_POLICY_VERSION = "local-ethereum-redemption-v1";
    static final WalletReference ADMIN_REDEMPTION =
            new WalletReference("synthetic-wallet:ADMIN_REDEMPTION");

    @Bean
    WalletTransferRepository walletTransferRepository(DataSource dataSource) {
        return new PostgresWalletTransferRepository(dataSource);
    }

    @Bean
    @Primary
    OperationDeliveryQueue localEthereumWalletTransferDeliveryQueue(DataSource dataSource) {
        return PostgresOperationDeliveryQueue.localEthereumDemo(dataSource);
    }

    @Bean(destroyMethod = "close")
    Web3jEthereumBurnChainAdapter localEthereumBurnChainAdapter(
            DataSource dataSource,
            WalletIdentityRegistry wallets,
            LocalEthereumProperties properties) {
        WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN_REDEMPTION);
        return Web3jEthereumBurnChainAdapter.local(
                dataSource, properties.rpcUrl(),
                new Web3jEthereumBurnChainAdapter.Configuration(
                        properties.chainId(), properties.contractAddress(),
                        admin.normalizedAddress(), admin.keyReference().value(),
                        admin.registryVersion(), admin.keyVersion(),
                        properties.maxPriorityFeePerGas(), properties.maxFeePerGas(),
                        properties.gasLimit(), properties.confirmations(),
                        properties.assetId(), properties.unitId(), properties.unitVersion(),
                        properties.decimals(), REDEMPTION_POLICY_VERSION), Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    Web3jEthereumWalletTransferChainAdapter localEthereumWalletTransferChainAdapter(
            DataSource dataSource, LocalEthereumProperties properties) {
        return Web3jEthereumWalletTransferChainAdapter.local(
                dataSource, properties.rpcUrl(),
                new Web3jEthereumWalletTransferChainAdapter.Configuration(
                        properties.chainId(), properties.contractAddress(), CONTRACT_VERSION,
                        properties.maxPriorityFeePerGas(), properties.maxFeePerGas(),
                        properties.gasLimit(), properties.confirmations(),
                        properties.assetId(), properties.unitId(), properties.unitVersion(),
                        properties.decimals(), POLICY_VERSION), Clock.systemUTC());
    }

    @Bean
    @Primary
    OperationDeliveryHandler localEthereumWalletTransferDeliveryHandler(
            OperationRepository operations,
            WalletTransferRepository transfers,
            WalletTransferAcceptanceService acceptance,
            Web3jEthereumWalletTransferChainAdapter transferChain,
            Web3jEthereumBurnChainAdapter burnChain,
            SigningAuthorityService signing,
            WalletIdentityRegistry wallets,
            ClockPort clock,
            IdGenerator ids,
            LocalEthereumProperties properties,
            UsdzelleWorkflowRepository workflows,
            UsdzelleWorkflowContextResolver workflowContexts,
            @Qualifier("localUsdzelleMintHandler") OperationDeliveryHandler mintHandler,
            UsdzelleWorkflowAcceptedDeliveryHandler workflowHandler) {
        WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN_REDEMPTION);
        var custodyHandler = new WalletTransferAcceptedDeliveryHandler(
                transfers, transferChain, signing, wallets, clock, Duration.ofMinutes(5));
        var burnHandler = new TokenOperationAcceptedDeliveryHandler(
                operations, burnChain, signing, clock, ids,
                new TokenOperationAcceptedDeliveryHandler.Policy(
                        admin.keyReference(), admin.normalizedAddress(), Duration.ofMinutes(5),
                        REDEMPTION_POLICY_VERSION, OperationKind.BURN,
                        SigningRequest.Action.BURN,
                        SigningRequest.KeyRole.BURN_AUTHORITY));
        var standaloneRedemption = new RedemptionAcceptedDeliveryHandler(
                operations, transfers, acceptance, burnHandler, custodyHandler,
                new WalletReference("synthetic-wallet:"
                        + properties.redemptionSourceWallet()),
                ADMIN_REDEMPTION);
        return delivery -> {
            if (UsdzelleWorkflowAcceptedDeliveryHandler.EVENT_TYPE.equals(
                    delivery.eventType())) {
                return workflowHandler.handle(delivery);
            }
            if (WalletTransferAcceptedDeliveryHandler.EVENT_TYPE.equals(
                    delivery.eventType())) {
                return custodyHandler.handle(delivery);
            }
            if (TokenOperationAcceptedDeliveryHandler.EVENT_TYPE.equals(
                    delivery.eventType())) {
                var operation = operations.findById(delivery.operationId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "token operation was not found"));
                if (operation.acceptanceContext().businessCorrelation()
                        .startsWith("usdzelle:")) {
                    verifyWorkflowContext(operation, workflows, workflowContexts);
                }
                if (operation.kind() == OperationKind.MINT) {
                    return mintHandler.handle(delivery);
                }
                if (operation.acceptanceContext().businessCorrelation()
                        .startsWith("usdzelle:")) {
                    return burnHandler.handle(delivery);
                }
            }
            return standaloneRedemption.handle(delivery);
        };
    }

    private static void verifyWorkflowContext(
            io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation operation,
            UsdzelleWorkflowRepository workflows,
            UsdzelleWorkflowContextResolver contexts) {
        String[] correlation = operation.acceptanceContext()
                .businessCorrelation().split(":", 3);
        if (correlation.length != 3) {
            throw new IllegalStateException("workflow child correlation is invalid");
        }
        UsdzelleWorkflow workflow = workflows.findById(new UsdzelleWorkflow.Id(
                        java.util.UUID.fromString(correlation[1])))
                .orElseThrow(() -> new IllegalStateException(
                        "workflow child parent is unavailable"));
        contexts.verifyAccepted(workflow);
    }

    @Bean
    @Primary
    AssetUnitCatalog localEthereumWalletTransferAssetUnitCatalog(
            LocalEthereumProperties properties) {
        AssetUnit unit = new AssetUnit(
                properties.assetId(), properties.unitId(), properties.unitVersion(),
                properties.decimals(), properties.maxAtomicUnits());
        return (assetId, unitId, version) -> assetId.equals(unit.assetId())
                && unitId.equals(unit.unitId()) && version == unit.version()
                ? java.util.Optional.of(unit) : java.util.Optional.empty();
    }

    @Bean
    WalletTransferAcceptanceService walletTransferAcceptanceService(
            WalletTransferRepository transfers,
            AssetUnitCatalog assets,
            WalletIdentityRegistry wallets,
            ClockPort clock,
            IdGenerator operationIds,
            TransferIdentityGenerator transferIds,
            LocalEthereumProperties properties) {
        return new WalletTransferAcceptanceService(
                transfers, assets, wallets, clock, operationIds, transferIds,
                new WalletTransferAcceptanceService.Policy(
                        properties.contractAddress(), CONTRACT_VERSION, POLICY_VERSION));
    }
}
