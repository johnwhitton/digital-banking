package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.AccountingApplicationService;
import io.github.johnwhitton.digitalbanking.application.LocalUsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.application.LocalSettlementTransferStepExecutor;
import io.github.johnwhitton.digitalbanking.application.MockBankApplicationService;
import io.github.johnwhitton.digitalbanking.application.RegisteredSettlementInstructionResolver;
import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationApplicationService;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowApplicationService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.SettlementTransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.UsdzelleWorkflowAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionRegistry;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionResolver;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferRepository;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferStepExecutor;
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
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSettlementInstructionRegistry;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSettlementTransferRepository;
import io.github.johnwhitton.digitalbanking.solana.sava.PostgresSavaUsdzelleChainEvidenceAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;

/** Explicit local-only composition for Phase 6B user-held workflows. */
@Configuration(proxyBeanMethods = false)
@Profile("local-demo & !local-signer & (local-ethereum | local-solana)")
@EnableConfigurationProperties({
        UsdzelleWorkflowProperties.class,
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
    SettlementInstructionRegistry settlementInstructionRegistry(DataSource dataSource) {
        return new PostgresSettlementInstructionRegistry(dataSource);
    }

    @Bean
    @Primary
    @Profile("local-ethereum")
    SettlementInstructionResolver localEthereumSettlementInstructionResolver(
            SettlementInstructionRegistry instructions,
            WalletIdentityRegistry wallets,
            MockBankApplicationService banks,
            UsdzelleWorkflowProperties workflowProperties,
            LocalEthereumProperties ethereum,
            LocalFinancialProperties finance,
            ClockPort clock) {
        return new RegisteredSettlementInstructionResolver(
                instructions, wallets, banks,
                new RegisteredSettlementInstructionResolver.Policy(
                        ADMIN, "phase-6c-v1", ethereum.contractAddress(),
                        versions(workflowProperties.payoutPolicyVersion(),
                                finance.bankPolicyVersion()),
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
                                finance.accountingPolicyVersion())),
                clock);
    }

    @Bean
    @Primary
    @Profile("local-solana")
    SettlementInstructionResolver localSolanaSettlementInstructionResolver(
            SettlementInstructionRegistry instructions,
            WalletIdentityRegistry wallets,
            MockBankApplicationService banks,
            UsdzelleWorkflowProperties workflowProperties,
            LocalSolanaProperties solana,
            LocalFinancialProperties finance,
            ClockPort clock) {
        return new RegisteredSettlementInstructionResolver(
                instructions, wallets, banks,
                new RegisteredSettlementInstructionResolver.Policy(
                        ADMIN, "phase-6c-v1", solana.mintAddress(),
                        versions(workflowProperties.payoutPolicyVersion(),
                                finance.bankPolicyVersion()),
                        workflowProperties.conversionPolicyVersion(),
                        versions(finance.accountingPolicyVersion(),
                                finance.mintEvidencePolicyVersion(),
                                finance.custodyEvidencePolicyVersion(),
                                finance.burnEvidencePolicyVersion()),
                        versions(workflowProperties.feePolicyVersion(),
                                solana.policyVersion()),
                        versions(workflowProperties.finalityPolicyVersion(),
                                solana.policyVersion()),
                        versions(workflowProperties.reconciliationPolicyVersion(),
                                finance.accountingPolicyVersion())),
                clock);
    }

    @Bean
    SettlementTransferRepository settlementTransferRepository(DataSource dataSource) {
        return new PostgresSettlementTransferRepository(dataSource);
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
    @Profile("local-ethereum")
    UsdzelleWorkflowContextResolver localEthereumUsdzelleWorkflowContextResolver(
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
    @Profile("local-solana")
    UsdzelleWorkflowContextResolver localSolanaUsdzelleWorkflowContextResolver(
            UsdzelleWorkflowProperties workflowProperties,
            LocalSolanaProperties solana,
            LocalFinancialProperties finance,
            WalletIdentityRegistry wallets,
            MockBankApplicationService banks,
            ClockPort clock) {
        AssetUnit unit = new AssetUnit(
                solana.assetId(), solana.unitId(), solana.unitVersion(),
                solana.decimals(), solana.maxAtomicUnits());
        return (kind, participant, bankReference, currency, requestedNetwork) -> {
            if (!"USD".equals(currency)
                    || requestedNetwork.filter(network ->
                            network != SettlementNetwork.SOLANA).isPresent()) {
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
                    || user.network() != SettlementNetwork.SOLANA
                    || admin.network() != SettlementNetwork.SOLANA
                    || (kind == UsdzelleWorkflow.Kind.ACQUISITION
                        && !user.normalizedAddress().equals(solana.destinationOwner()))) {
                throw new IllegalArgumentException(
                        "workflow wallet authority mapping is invalid");
            }
            return new UsdzelleWorkflowContextResolver.Resolution(
                    unit, bankId, accountId, userReference,
                    user.registryVersion() + ':' + user.keyVersion(),
                    ADMIN, admin.registryVersion() + ':' + admin.keyVersion(),
                    SettlementNetwork.SOLANA, solana.mintAddress(),
                    versions(workflowProperties.payoutPolicyVersion(),
                            finance.bankPolicyVersion()),
                    workflowProperties.workflowVersion(),
                    workflowProperties.conversionPolicyVersion(),
                    versions(finance.accountingPolicyVersion(),
                            finance.mintEvidencePolicyVersion(),
                            finance.custodyEvidencePolicyVersion(),
                            finance.burnEvidencePolicyVersion()),
                    versions(workflowProperties.feePolicyVersion(),
                            solana.policyVersion()),
                    versions(workflowProperties.finalityPolicyVersion(),
                            solana.policyVersion()),
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
    @Profile("local-ethereum")
    UsdzelleChainEvidencePort localEthereumUsdzelleChainEvidencePort(
            DataSource dataSource,
            LocalEthereumProperties ethereum,
            UsdzelleWorkflowProperties workflows,
            WalletIdentityRegistry wallets) {
        WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN);
        java.util.Map<String,
                PostgresWeb3jUsdzelleChainEvidenceAdapter.UserConfiguration> users =
                workflows.participants().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                UsdzelleWorkflowProperties.ParticipantMapping
                                        ::userWalletReference,
                                mapping -> {
                                    WalletIdentityRegistry.WalletIdentity user =
                                            wallets.resolve(new WalletReference(
                                                    mapping.userWalletReference()));
                                    return new PostgresWeb3jUsdzelleChainEvidenceAdapter
                                            .UserConfiguration(
                                                    user.normalizedAddress(),
                                                    user.reference().value(),
                                                    user.registryVersion() + ':'
                                                            + user.keyVersion());
                                }));
        return new PostgresWeb3jUsdzelleChainEvidenceAdapter(
                dataSource, ethereum.rpcUrl(), ethereum.composeEnvironment(),
                new PostgresWeb3jUsdzelleChainEvidenceAdapter.Configuration(
                        ethereum.contractAddress(), users, admin.normalizedAddress(),
                        ADMIN.value(),
                        admin.registryVersion() + ':' + admin.keyVersion()),
                Clock.systemUTC());
    }

    @Bean
    @Profile("local-solana")
    UsdzelleChainEvidencePort localSolanaUsdzelleChainEvidencePort(
            DataSource dataSource,
            LocalSolanaProperties solana,
            UsdzelleWorkflowProperties workflows,
            WalletIdentityRegistry wallets) {
        WalletIdentityRegistry.WalletIdentity admin = wallets.resolve(ADMIN);
        java.util.Map<String,
                PostgresSavaUsdzelleChainEvidenceAdapter.UserConfiguration> users =
                workflows.participants().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                UsdzelleWorkflowProperties.ParticipantMapping
                                        ::userWalletReference,
                                mapping -> {
                                    WalletIdentityRegistry.WalletIdentity user =
                                            wallets.resolve(new WalletReference(
                                                    mapping.userWalletReference()));
                                    return new PostgresSavaUsdzelleChainEvidenceAdapter
                                            .UserConfiguration(
                                                    user.normalizedAddress(),
                                                    mapping.userWalletReference(),
                                                    user.registryVersion() + ':'
                                                            + user.keyVersion());
                                }));
        return new PostgresSavaUsdzelleChainEvidenceAdapter(
                dataSource,
                new PostgresSavaUsdzelleChainEvidenceAdapter.Configuration(
                        solana.clusterIdentity(), solana.mintAddress(), users,
                        admin.normalizedAddress(), ADMIN.value(),
                        admin.registryVersion() + ':' + admin.keyVersion(),
                        solana.policyVersion()),
                Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    @Profile("local-ethereum")
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
    @Profile("local-ethereum")
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

    @Bean
    SettlementTransferStepExecutor settlementTransferStepExecutor(
            UsdzelleWorkflowApplicationService workflowAcceptance,
            UsdzelleWorkflowRepository workflows,
            WalletTransferAcceptanceService transferAcceptance,
            WalletTransferRepository walletTransfers,
            AccountingApplicationService accounting,
            SettlementInstructionResolver instructions,
            UsdzelleWorkflowMetrics metrics,
            ClockPort clock) {
        return metrics.metered(new LocalSettlementTransferStepExecutor(
                workflowAcceptance, workflows, transferAcceptance,
                walletTransfers, accounting, instructions), clock);
    }

    @Bean
    SettlementTransferAcceptedDeliveryHandler settlementTransferAcceptedDeliveryHandler(
            SettlementTransferRepository settlements,
            SettlementTransferStepExecutor steps,
            ClockPort clock,
            SettlementTransferIdentityGenerator ids) {
        return new SettlementTransferAcceptedDeliveryHandler(
                settlements, steps, clock, ids);
    }

    private static String versions(String... values) {
        return String.join(":", values);
    }
}
