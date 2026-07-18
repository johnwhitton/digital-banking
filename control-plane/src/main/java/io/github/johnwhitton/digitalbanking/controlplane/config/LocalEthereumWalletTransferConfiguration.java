package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.WalletTransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.PostgresWalletTransferRepository;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jEthereumWalletTransferChainAdapter;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Local-only composition for the standalone user-wallet transfer slice. */
@Configuration(proxyBeanMethods = false)
@Profile("local-demo & local-ethereum & !local-signer")
@EnableConfigurationProperties(LocalEthereumProperties.class)
public class LocalEthereumWalletTransferConfiguration {

    static final String CONTRACT_VERSION = "local-usdzelle-v1";
    static final String POLICY_VERSION = "local-ethereum-wallet-transfer-v1";

    @Bean
    WalletTransferRepository walletTransferRepository(DataSource dataSource) {
        return new PostgresWalletTransferRepository(dataSource);
    }

    @Bean
    @Primary
    OperationDeliveryQueue localEthereumWalletTransferDeliveryQueue(DataSource dataSource) {
        return PostgresOperationDeliveryQueue.walletTransfersOnly(dataSource);
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
    OperationDeliveryHandler localEthereumWalletTransferDeliveryHandler(
            WalletTransferRepository transfers,
            Web3jEthereumWalletTransferChainAdapter chain,
            SigningAuthorityService signing,
            WalletIdentityRegistry wallets,
            ClockPort clock) {
        return new WalletTransferAcceptedDeliveryHandler(
                transfers, chain, signing, wallets, clock, Duration.ofMinutes(5));
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
