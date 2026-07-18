package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Validated synthetic-bank and reserve policy for the explicit local profile. */
@ConfigurationProperties("digital-banking.local-finance")
public record LocalFinancialProperties(
        boolean enabled,
        String fixtureVersion,
        int maximumPreEffectAttempts,
        Duration inquiryTimeout,
        String bankPolicyVersion,
        String accountingPolicyVersion,
        String mintEvidencePolicyVersion,
        String custodyEvidencePolicyVersion,
        String burnEvidencePolicyVersion,
        String chainAssetId,
        String settlementNetwork,
        String contractReference,
        Duration maximumObservationAge,
        List<Bank> banks,
        List<Account> accounts) {

    public LocalFinancialProperties {
        requireText(fixtureVersion, "fixtureVersion");
        requireText(bankPolicyVersion, "bankPolicyVersion");
        requireText(accountingPolicyVersion, "accountingPolicyVersion");
        requireText(mintEvidencePolicyVersion, "mintEvidencePolicyVersion");
        requireText(custodyEvidencePolicyVersion, "custodyEvidencePolicyVersion");
        requireText(burnEvidencePolicyVersion, "burnEvidencePolicyVersion");
        requireText(chainAssetId, "chainAssetId");
        requireText(settlementNetwork, "settlementNetwork");
        requireText(contractReference, "contractReference");
        Objects.requireNonNull(inquiryTimeout, "inquiryTimeout");
        Objects.requireNonNull(maximumObservationAge, "maximumObservationAge");
        banks = banks == null ? List.of() : List.copyOf(banks);
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        if (!enabled || maximumPreEffectAttempts < 1 || maximumPreEffectAttempts > 3
                || inquiryTimeout.isNegative() || inquiryTimeout.isZero()
                || maximumObservationAge.isNegative() || maximumObservationAge.isZero()
                || banks.size() < 4 || accounts.size() < 2) {
            throw new IllegalArgumentException(
                    "local financial fixture and bounded policies are required");
        }
    }

    public record Bank(String bankId, boolean enabled) {
        public Bank {
            requireText(bankId, "bankId");
        }
    }

    public record Account(
            String bankId,
            String accountId,
            String tenantId,
            String participantId,
            String currency,
            String initialBalance,
            boolean enabled) {
        public Account {
            requireText(bankId, "bankId");
            requireText(accountId, "accountId");
            requireText(tenantId, "tenantId");
            requireText(participantId, "participantId");
            if (!"USD".equals(currency)) {
                throw new IllegalArgumentException("local financial account requires USD");
            }
            UsdCents.parseNonNegative(initialBalance, currency);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
