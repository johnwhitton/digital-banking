package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.TokenOperationApplicationService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationService;
import io.github.johnwhitton.digitalbanking.application.TransferApplicationService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.EvidenceReferencePort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.application.port.TransferRouteCatalog;
import io.github.johnwhitton.digitalbanking.application.port.WalletRoleResolver;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresTransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {

    @Bean
    OperationRepository operationRepository(DataSource dataSource) {
        return new PostgresOperationRepository(dataSource);
    }

    @Bean
    OperationDeliveryQueue operationDeliveryQueue(DataSource dataSource) {
        return new PostgresOperationDeliveryQueue(dataSource);
    }

    @Bean
    TransferRepository transferRepository(DataSource dataSource) {
        return new PostgresTransferRepository(dataSource);
    }

    @Bean
    AssetUnitCatalog assetUnitCatalog() {
        return (assetId, unitId, version) -> Optional.empty();
    }

    @Bean
    ClockPort clockPort() {
        return Instant::now;
    }

    @Bean
    IdGenerator idGenerator() {
        return new IdGenerator() {
            @Override
            public OperationId nextOperationId() {
                return new OperationId(UUID.randomUUID());
            }

            @Override
            public AttemptId nextAttemptId() {
                return new AttemptId(UUID.randomUUID());
            }
        };
    }

    @Bean
    EvidenceReferencePort evidenceReferencePort() {
        return (canonical, participant) ->
                new EvidenceRef("participant:acceptance:" + UUID.randomUUID());
    }

    @Bean
    TokenOperationService tokenOperationService(
            OperationRepository operations,
            ClockPort clock,
            IdGenerator ids,
            EvidenceReferencePort evidence) {
        return new TokenOperationService(operations, clock, ids, evidence);
    }

    @Bean
    TokenOperationApplicationService tokenOperationApplicationService(
            TokenOperationService lifecycle,
            OperationRepository operations,
            AssetUnitCatalog assets) {
        return new TokenOperationApplicationService(lifecycle, operations, assets, 1);
    }

    @Bean
    TransferRouteCatalog transferRouteCatalog(
            @Value("${digital-banking.transfer.default-network}") String configuredDefault) {
        SettlementNetwork defaultNetwork = SettlementNetwork.valueOf(configuredDefault);
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
        return (currency, requested) -> "USD".equals(currency)
                ? Optional.of(new TransferRouteCatalog.Route(
                        currency, requested.orElse(defaultNetwork), unit, "reference-route-v1"))
                : Optional.empty();
    }

    @Bean
    WalletRoleResolver walletRoleResolver() {
        return (participant, route) -> {
            String network = route.settlementNetwork().name()
                    .toLowerCase(java.util.Locale.ROOT);
            return new WalletRoleResolver.Resolution(
                    new WalletReference("synthetic-wallet:institution-sender-" + network),
                    new WalletReference("synthetic-wallet:institution-recipient-" + network),
                    "reference-wallet-policy-v1");
        };
    }

    @Bean
    TransferIdentityGenerator transferIdentityGenerator() {
        return new TransferIdentityGenerator() {
            @Override
            public TransferId nextTransferId() {
                return new TransferId(UUID.randomUUID());
            }

            @Override
            public TransferEffect.Id nextEffectId() {
                return new TransferEffect.Id(UUID.randomUUID());
            }

            @Override
            public TransferTransition.Id nextTransitionId() {
                return new TransferTransition.Id(UUID.randomUUID());
            }
        };
    }

    @Bean
    TransferApplicationService transferApplicationService(
            TransferRepository transfers,
            TransferRouteCatalog routes,
            WalletRoleResolver wallets,
            ClockPort clock,
            TransferIdentityGenerator ids) {
        return new TransferApplicationService(transfers, routes, wallets, clock, ids);
    }
}
