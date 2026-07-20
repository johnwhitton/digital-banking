package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry.OwnerCategory;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry.Purpose;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalConfiguredSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-demo & !local-solana & !local-signer")
@EnableConfigurationProperties(LocalDemoProperties.class)
public class LocalDemoConfiguration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LocalDemoConfiguration.class);

    private static final Map<String, RequiredWallet> REQUIRED = Map.ofEntries(
            Map.entry("CONTRACT_OWNER", new RequiredWallet(
                    Set.of("CONTRACT_DEPLOYER"), OwnerCategory.ADMIN,
                    Set.of(Purpose.CONTRACT_ADMIN, Purpose.CONTRACT_DEPLOYMENT,
                            Purpose.ROLE_ADMINISTRATION))),
            Map.entry("ADMIN", new RequiredWallet(
                    Set.of("ADMIN_REDEMPTION"), OwnerCategory.ADMIN,
                    Set.of(Purpose.MINT_AUTHORITY, Purpose.BURN_AUTHORITY,
                            Purpose.REDEMPTION_CUSTODY))),
            Map.entry("BANK_1_SETTLEMENT", bank()),
            Map.entry("BANK_2_SETTLEMENT", bank()),
            Map.entry("BANK_3_SETTLEMENT", bank()),
            Map.entry("BANK_4_SETTLEMENT", bank()),
            Map.entry("USER_WALLET_1", user()),
            Map.entry("USER_WALLET_2", user()),
            Map.entry("USER_WALLET_3", user()),
            Map.entry("USER_WALLET_4", user()));

    @Bean(destroyMethod = "close")
    LocalConfiguredSigner localConfiguredSigner(LocalDemoProperties properties) {
        try {
            validateRequired(properties.wallets());
            List<LocalConfiguredSigner.ConfiguredWallet> wallets = properties.wallets()
                    .stream().map(LocalDemoConfiguration::configured).toList();
            LocalConfiguredSigner signer = new LocalConfiguredSigner(
                    new LocalConfiguredSigner.Configuration(
                            properties.chainId(), wallets),
                    new SecureRandom());
            LOGGER.warn("LOCAL_CONFIGURED signer is active for local chain 31337 only; "
                    + "configured keys are POC-only and must never hold real value");
            return signer;
        } finally {
            properties.destroySecrets();
        }
    }

    @Bean
    SigningRequestRepository localDemoSigningRequestRepository(DataSource dataSource) {
        return new PostgresSigningRequestRepository(dataSource);
    }

    @Bean
    SigningAuthorizationPort localDemoSigningAuthorization() {
        return request -> new SigningAuthorizationPort.Authorized(
                new EvidenceRef("internal:local-demo-signer:authorization:"
                        + request.requestId().value()));
    }

    @Bean
    SigningIdentityGenerator localDemoSigningIdentityGenerator() {
        return new SigningIdentityGenerator() {
            @Override
            public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(UUID.randomUUID());
            }

            @Override
            public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-demo-provider:" + UUID.randomUUID());
            }
        };
    }

    @Bean
    SigningAuthorityService localDemoSigningAuthorityService(
            SigningRequestRepository requests,
            LocalConfiguredSigner signer,
            SigningAuthorizationPort authorization,
            SigningIdentityGenerator identities,
            ClockPort clock) {
        return new SigningAuthorityService(
                requests, signer, authorization, signer, identities, clock);
    }

    private static LocalConfiguredSigner.ConfiguredWallet configured(
            LocalDemoProperties.Wallet wallet) {
        return new LocalConfiguredSigner.ConfiguredWallet(
                reference(wallet.reference()),
                wallet.aliases().stream().map(LocalDemoConfiguration::reference)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                wallet.ownerCategory(), wallet.network(),
                new KeyAlias(wallet.keyReference()), wallet.privateKey().copy(),
                wallet.expectedAddress(), wallet.allowedPurposes(), wallet.enabled());
    }

    private static void validateRequired(List<LocalDemoProperties.Wallet> configured) {
        Map<String, LocalDemoProperties.Wallet> byReference = configured.stream()
                .collect(java.util.stream.Collectors.toMap(
                        LocalDemoProperties.Wallet::reference,
                        java.util.function.Function.identity(),
                        (first, duplicate) -> {
                            throw new IllegalArgumentException(
                                    "local demo wallet reference is duplicated");
                        }));
        for (Map.Entry<String, RequiredWallet> entry : REQUIRED.entrySet()) {
            LocalDemoProperties.Wallet wallet = byReference.get(entry.getKey());
            RequiredWallet required = entry.getValue();
            if (wallet == null || !wallet.enabled()
                    || wallet.network() != SettlementNetwork.ETHEREUM
                    || wallet.ownerCategory() != required.owner()
                    || !wallet.aliases().containsAll(required.aliases())
                    || !wallet.allowedPurposes().equals(required.purposes())
                    || !wallet.keyReference().equals("local-demo:" + entry.getKey())) {
                throw new IllegalArgumentException(
                        "local demo required wallet configuration is incomplete");
            }
        }
    }

    private static WalletReference reference(String value) {
        return new WalletReference("synthetic-wallet:" + value);
    }

    private static RequiredWallet bank() {
        return new RequiredWallet(
                Set.of(), OwnerCategory.BANK_SETTLEMENT,
                Set.of(Purpose.BANK_SETTLEMENT_TRANSFER));
    }

    private static RequiredWallet user() {
        return new RequiredWallet(
                Set.of(), OwnerCategory.USER_CUSTODY,
                Set.of(Purpose.USER_CUSTODY_TRANSFER));
    }

    private record RequiredWallet(
            Set<String> aliases, OwnerCategory owner, Set<Purpose> purposes) {
    }
}
