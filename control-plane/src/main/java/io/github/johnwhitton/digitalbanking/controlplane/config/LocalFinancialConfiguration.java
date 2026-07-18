package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.AccountingApplicationService;
import io.github.johnwhitton.digitalbanking.application.MockBankApplicationService;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.AccountingIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.BankIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.application.port.ReserveAccountingPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresMockBankAdapter;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresReserveAccountingAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-demo")
@EnableConfigurationProperties(LocalFinancialProperties.class)
public class LocalFinancialConfiguration {

    @Bean
    LocalFinancialMetrics localFinancialMetrics(MeterRegistry registry) {
        return new LocalFinancialMetrics(registry);
    }

    @Bean
    @DependsOn("flywayInitializer")
    MockBankPort localMockBankPort(
            DataSource dataSource, LocalFinancialProperties properties) {
        PostgresMockBankAdapter bank = new PostgresMockBankAdapter(
                dataSource, properties.inquiryTimeout());
        bank.initialize(new MockBankPort.Fixture(
                new SyntheticBankAccount.FixtureVersion(properties.fixtureVersion()),
                properties.banks().stream().map(configured ->
                        new MockBankPort.BankFixture(
                                new SyntheticBankAccount.BankId(configured.bankId()),
                                configured.enabled())).toList(),
                properties.accounts().stream().map(configured ->
                        new MockBankPort.AccountFixture(
                                new SyntheticBankAccount.BankId(configured.bankId()),
                                new SyntheticBankAccount.AccountId(configured.accountId()),
                                new ParticipantScope(
                                        configured.tenantId(), configured.participantId()),
                                UsdCents.parseNonNegative(
                                        configured.initialBalance(), configured.currency()),
                                configured.enabled())).toList(),
                Instant.EPOCH));
        return bank;
    }

    @Bean
    BankIdentityGenerator bankIdentityGenerator() {
        return new BankIdentityGenerator() {
            @Override
            public BankOperation.Id nextOperationId() {
                return new BankOperation.Id(UUID.randomUUID());
            }

            @Override
            public BankOperation.EvidenceId nextEvidenceId() {
                return new BankOperation.EvidenceId(UUID.randomUUID());
            }
        };
    }

    @Bean
    MockBankApplicationService mockBankApplicationService(
            MockBankPort bank,
            ClockPort clock,
            BankIdentityGenerator ids,
            LocalFinancialProperties properties) {
        return new MockBankApplicationService(
                bank, clock, ids,
                new BankOperation.PolicyVersion(properties.bankPolicyVersion()),
                properties.maximumPreEffectAttempts());
    }

    @Bean
    ReserveAccountingPort reserveAccountingPort(
            DataSource dataSource, LocalFinancialMetrics metrics) {
        return metrics.metered(new PostgresReserveAccountingAdapter(dataSource));
    }

    @Bean
    AccountingIdentityGenerator accountingIdentityGenerator() {
        return new AccountingIdentityGenerator() {
            @Override
            public ReserveAccounting.JournalId nextJournalId() {
                return new ReserveAccounting.JournalId(UUID.randomUUID());
            }

            @Override
            public ReserveAccounting.JournalLineId nextJournalLineId() {
                return new ReserveAccounting.JournalLineId(UUID.randomUUID());
            }

            @Override
            public ReserveAccounting.ReconciliationRunId nextReconciliationRunId() {
                return new ReserveAccounting.ReconciliationRunId(UUID.randomUUID());
            }

            @Override
            public ReserveAccounting.ReconciliationResultId nextReconciliationResultId() {
                return new ReserveAccounting.ReconciliationResultId(UUID.randomUUID());
            }
        };
    }

    @Bean
    AccountingApplicationService accountingApplicationService(
            ReserveAccountingPort accounting,
            ClockPort clock,
            AccountingIdentityGenerator ids,
            LocalFinancialProperties properties) {
        return new AccountingApplicationService(
                accounting, clock, ids, new ReserveAccountingPort.EvidencePolicy(
                        new ReserveAccounting.PolicyVersion(
                                properties.accountingPolicyVersion()),
                        properties.bankPolicyVersion(),
                        properties.mintEvidencePolicyVersion(),
                        properties.custodyEvidencePolicyVersion(),
                        properties.burnEvidencePolicyVersion(),
                        properties.chainAssetId(), properties.settlementNetwork(),
                        properties.contractReference(),
                        properties.maximumObservationAge()));
    }
}
