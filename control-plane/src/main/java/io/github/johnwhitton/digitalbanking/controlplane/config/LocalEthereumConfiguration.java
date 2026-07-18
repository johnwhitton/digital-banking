package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.delivery.MintAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.EthereumTransactionCodec;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jEthereumMintChainAdapter;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.signer.local.LocalEphemeralSigner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-ethereum & local-signer")
@EnableConfigurationProperties(LocalEthereumProperties.class)
public class LocalEthereumConfiguration {

    @Bean
    @Primary
    OperationDeliveryQueue localEthereumMintDeliveryQueue(DataSource dataSource) {
        return PostgresOperationDeliveryQueue.mintOnly(dataSource);
    }

    @Bean(destroyMethod = "close")
    Web3jEthereumMintChainAdapter localEthereumMintChainAdapter(
            DataSource dataSource,
            LocalEphemeralSigner signer,
            LocalEthereumProperties properties) {
        LocalEphemeralSigner.KeyMetadata evmKey = signer.keys().stream()
                .filter(key -> key.algorithm() == SigningRequest.Algorithm.SECP256K1)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("local EVM signing key is unavailable"));
        String signingAddress = new EthereumTransactionCodec()
                .addressFromPublicKey(signer.publicKey(evmKey.alias()));
        return Web3jEthereumMintChainAdapter.local(
                dataSource, properties.rpcUrl(),
                new Web3jEthereumMintChainAdapter.Configuration(
                        properties.chainId(), properties.contractAddress(),
                        properties.requiredMintRecipientAddress(), signingAddress,
                        evmKey.alias().value(), evmKey.keyVersion(),
                        properties.maxPriorityFeePerGas(), properties.maxFeePerGas(),
                        properties.gasLimit(), properties.confirmations(),
                        properties.assetId(), properties.unitId(), properties.unitVersion(),
                        properties.decimals(), properties.policyVersion()), Clock.systemUTC());
    }

    @Bean
    OperationDeliveryHandler localEthereumMintDeliveryHandler(
            OperationRepository operations,
            Web3jEthereumMintChainAdapter chain,
            SigningAuthorityService signing,
            LocalEphemeralSigner signer,
            ClockPort clock,
            IdGenerator ids,
            LocalEthereumProperties properties) {
        LocalEphemeralSigner.KeyMetadata evmKey = signer.keys().stream()
                .filter(key -> key.algorithm() == SigningRequest.Algorithm.SECP256K1)
                .findFirst().orElseThrow();
        String signingAddress = new EthereumTransactionCodec()
                .addressFromPublicKey(signer.publicKey(evmKey.alias()));
        return new MintAcceptedDeliveryHandler(
                operations, chain, signing, clock, ids,
                new MintAcceptedDeliveryHandler.Policy(
                        evmKey.alias(), signingAddress, Duration.ofMinutes(5),
                        properties.policyVersion()));
    }

    @Bean
    @Primary
    AssetUnitCatalog localEthereumAssetUnitCatalog(LocalEthereumProperties properties) {
        AssetUnit unit = new AssetUnit(
                properties.assetId(), properties.unitId(), properties.unitVersion(),
                properties.decimals(), properties.maxAtomicUnits());
        return (assetId, unitId, version) -> assetId.equals(unit.assetId())
                && unitId.equals(unit.unitId()) && version == unit.version()
                ? java.util.Optional.of(unit) : java.util.Optional.empty();
    }
}
