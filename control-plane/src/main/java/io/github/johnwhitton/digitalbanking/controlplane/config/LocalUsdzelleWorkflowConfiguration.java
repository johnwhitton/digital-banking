package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.AccountingApplicationService;
import io.github.johnwhitton.digitalbanking.application.LocalUsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.application.MockBankApplicationService;
import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationApplicationService;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowApplicationService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.UsdzelleWorkflowAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleChainEvidencePort;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowContextResolver;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.PostgresWeb3jUsdzelleChainEvidenceAdapter;
import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jEthereumMintChainAdapter;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresUsdzelleWorkflowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/** Explicit local-only composition for Phase 6B user-held workflows. */
@Configuration(proxyBeanMethods = false)
@Profile("local-demo & local-ethereum & !local-signer")
@EnableConfigurationProperties({
        UsdzelleWorkflowProperties.class,
        LocalEthereumProperties.class,
        LocalFinancialProperties.class})
public class LocalUsdzelleWorkflowConfiguration {

    private static final WalletReference ADMIN =
            new WalletReference("synthetic-wallet:ADMIN_REDEMPTION");

    @Bean
    UsdzelleWorkflowMetrics usdzelleWorkflowMetrics(MeterRegistry registry) {
        return new UsdzelleWorkflowMetrics(registry);
    }

    @Bean
    @DependsOn("flywayInitializer")
    UsdzelleWorkflowRepository usdzelleWorkflowRepository(DataSource dataSource) {
        return new PostgresUsdzelleWorkflowRepository(dataSource);
    }

    @Bean
    UsdzelleWorkflowIdentityGenerator usdzelleWorkflowIdentityGenerator() {
        return new UsdzelleWorkflowIdentityGenerator() {
            @Override
            public UsdzelleWorkflow.Id nextWorkflowId() {
                return new UsdzelleWorkflow.Id(UUID.randomUUID());
            }

            @Override
            public UsdzelleWorkflow.StepId nextStepId() {
                return new UsdzelleWorkflow.StepId(UUID.randomUUID());
            }

            @Override
            public UsdzelleWorkflow.TransitionId nextTransitionId() {
                return new UsdzelleWorkflow.TransitionId(UUID.randomUUID());
            }
        };
    }

    @Bean
    UsdzelleWorkflowContextResolver usdzelleWorkflowContextResolver(
            UsdzelleWorkflowProperties workflowProperties,
            LocalEthereumProperties ethereum,
            LocalFinancialProperties finance,
            WalletIdentityRegistry wallets,
            MockBankApplicationService banks,
            ClockPort clock) {
        AssetUnit unit = new AssetUnit(
                ethereum.assetId(), ethereum.unitId(), ethereum.unitVersion(),
                ethereum.decimals(), ethereum.maxAtomicUnits());
        return (kind, participant, bankReference, currency, requestedNetwork) -> {
            if (!"USD".equals(currency)
                    || requestedNetwork.filter(network ->
                            network != SettlementNetwork.ETHEREUM).isPresent()) {
                throw new IllegalArgumentException(
                        "workflow currency or network is unsupported");
            }
            UsdzelleWorkflowProperties.ParticipantMapping mapping =
                    workflowProperties.participants().stream()
                            .filter(candidate -> candidate.tenantId().equals(
                                            participant.tenantId())
                                    && candidate.participantId().equals(
                                            participant.participantId())
                                    && candidate.bankAccountReference().equals(
                                            bankReference.value()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "participant workflow mapping is unavailable"));
            SyntheticBankAccount.BankId bankId =
                    new SyntheticBankAccount.BankId(mapping.bankId());
            SyntheticBankAccount.AccountId accountId =
                    new SyntheticBankAccount.AccountId(mapping.bankAccountId());
            banks.findAccount(participant, bankId, accountId);
            WalletReference userReference =
                    new WalletReference(mapping.userWalletReference());
            WalletIdentityRegistry.WalletIdentity user = wallets.resolve(userReference);
            WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN);
            if (user.ownerCategory() != WalletIdentityRegistry.OwnerCategory.USER_CUSTODY
                    || admin.ownerCategory() != WalletIdentityRegistry.OwnerCategory.ADMIN
                    || user.network() != SettlementNetwork.ETHEREUM
                    || admin.network() != SettlementNetwork.ETHEREUM) {
                throw new IllegalArgumentException(
                        "workflow wallet authority mapping is invalid");
            }
            return new UsdzelleWorkflowContextResolver.Resolution(
                    unit, bankId, accountId, userReference,
                    user.registryVersion() + ':' + user.keyVersion(),
                    ADMIN, admin.registryVersion() + ':' + admin.keyVersion(),
                    SettlementNetwork.ETHEREUM, ethereum.contractAddress(),
                    versions(workflowProperties.payoutPolicyVersion(),
                            finance.bankPolicyVersion()),
                    workflowProperties.workflowVersion(),
                    workflowProperties.conversionPolicyVersion(),
                    versions(finance.accountingPolicyVersion(),
                            finance.mintEvidencePolicyVersion(),
                            finance.custodyEvidencePolicyVersion(),
                            finance.burnEvidencePolicyVersion()),
                    versions(workflowProperties.feePolicyVersion(),
                            ethereum.policyVersion()),
                    versions(workflowProperties.finalityPolicyVersion(),
                            ethereum.policyVersion(),
                            LocalEthereumWalletTransferConfiguration.POLICY_VERSION,
                            LocalEthereumWalletTransferConfiguration
                                    .REDEMPTION_POLICY_VERSION),
                    versions(workflowProperties.reconciliationPolicyVersion(),
                            finance.accountingPolicyVersion()), clock.now());
        };
    }

    @Bean
    UsdzelleWorkflowApplicationService usdzelleWorkflowApplicationService(
            UsdzelleWorkflowRepository workflows,
            UsdzelleWorkflowContextResolver resolver,
            UsdzelleWorkflowIdentityGenerator ids) {
        return new UsdzelleWorkflowApplicationService(workflows, resolver, ids);
    }

    @Bean(destroyMethod = "close")
    UsdzelleChainEvidencePort usdzelleChainEvidencePort(
            DataSource dataSource,
            LocalEthereumProperties ethereum,
            UsdzelleWorkflowProperties workflows,
            WalletIdentityRegistry wallets) {
        WalletIdentityRegistry.WalletIdentity user = wallets.resolve(new WalletReference(
                workflows.participants().getFirst().userWalletReference()));
        WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN);
        return new PostgresWeb3jUsdzelleChainEvidenceAdapter(
                dataSource, ethereum.rpcUrl(),
                new PostgresWeb3jUsdzelleChainEvidenceAdapter.Configuration(
                        ethereum.contractAddress(), user.normalizedAddress(),
                        admin.normalizedAddress(), user.reference().value(),
                        user.registryVersion() + ':' + user.keyVersion(),
                        ADMIN.value(),
                        admin.registryVersion() + ':' + admin.keyVersion()),
                Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    Web3jEthereumMintChainAdapter usdzelleMintChainAdapter(
            DataSource dataSource,
            WalletIdentityRegistry wallets,
            LocalEthereumProperties ethereum,
            UsdzelleWorkflowProperties workflowProperties) {
        WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN);
        WalletIdentityRegistry.WalletIdentity user = wallets.resolve(new WalletReference(
                workflowProperties.participants().getFirst().userWalletReference()));
        return Web3jEthereumMintChainAdapter.local(
                dataSource, ethereum.rpcUrl(),
                new Web3jEthereumMintChainAdapter.Configuration(
                        ethereum.chainId(), ethereum.contractAddress(),
                        user.normalizedAddress(), admin.normalizedAddress(),
                        admin.keyReference().value(), admin.keyVersion(),
                        ethereum.maxPriorityFeePerGas(), ethereum.maxFeePerGas(),
                        ethereum.gasLimit(), ethereum.confirmations(),
                        ethereum.assetId(), ethereum.unitId(), ethereum.unitVersion(),
                        ethereum.decimals(), ethereum.policyVersion()),
                Clock.systemUTC());
    }

    @Bean("localUsdzelleMintHandler")
    OperationDeliveryHandler localUsdzelleMintHandler(
            OperationRepository operations,
            Web3jEthereumMintChainAdapter chain,
            SigningAuthorityService signing,
            WalletIdentityRegistry wallets,
            ClockPort clock,
            IdGenerator ids,
            LocalEthereumProperties ethereum) {
        WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN);
        return new TokenOperationAcceptedDeliveryHandler(
                operations, chain, signing, clock, ids,
                new TokenOperationAcceptedDeliveryHandler.Policy(
                        admin.keyReference(), admin.normalizedAddress(),
                        Duration.ofMinutes(5), ethereum.policyVersion(),
                        io.github.johnwhitton.digitalbanking.domain.operation.OperationKind.MINT,
                        SigningRequest.Action.MINT,
                        SigningRequest.KeyRole.MINT_AUTHORITY));
    }

    @Bean
    UsdzelleWorkflowStepExecutor usdzelleWorkflowStepExecutor(
            MockBankApplicationService banks,
            AccountingApplicationService accounting,
            TokenOperationApplicationService tokens,
            OperationRepository operations,
            WalletTransferAcceptanceService walletAcceptance,
            WalletTransferRepository walletTransfers,
            UsdzelleChainEvidencePort chainEvidence,
            UsdzelleWorkflowContextResolver contextResolver,
            UsdzelleWorkflowMetrics metrics,
            ClockPort clock) {
        return metrics.metered(new LocalUsdzelleWorkflowStepExecutor(
                banks, accounting, tokens, operations, walletAcceptance,
                walletTransfers, chainEvidence, contextResolver), clock);
    }

    @Bean
    UsdzelleWorkflowAcceptedDeliveryHandler usdzelleWorkflowAcceptedDeliveryHandler(
            UsdzelleWorkflowRepository workflows,
            UsdzelleWorkflowStepExecutor steps,
            ClockPort clock,
            UsdzelleWorkflowIdentityGenerator ids) {
        return new UsdzelleWorkflowAcceptedDeliveryHandler(
                workflows, steps, clock, ids);
    }

    private static String versions(String... values) {
        return String.join(":", values);
    }
}
