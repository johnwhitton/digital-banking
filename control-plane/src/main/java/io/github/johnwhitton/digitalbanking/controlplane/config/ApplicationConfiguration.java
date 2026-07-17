package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.TokenOperationApplicationService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.EvidenceReferencePort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationRepository;
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
}
