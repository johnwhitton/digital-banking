package io.github.johnwhitton.digitalbanking.domain.accounting;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Server-owned synthetic bank account state; values never identify a real account. */
public record SyntheticBankAccount(
        BankId bankId,
        AccountId accountId,
        Owner owner,
        UsdCents balance,
        boolean enabled,
        long version,
        FixtureVersion fixtureVersion,
        UsdCents initialBalance,
        Instant createdAt,
        Instant updatedAt) {

    private static final Pattern ID = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,63}");
    private static final Pattern OWNER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern VERSION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public SyntheticBankAccount {
        Objects.requireNonNull(bankId, "bankId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(fixtureVersion, "fixtureVersion");
        Objects.requireNonNull(initialBalance, "initialBalance");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("synthetic bank account state is invalid");
        }
    }

    public record BankId(String value) {
        public BankId {
            value = require(value, ID, "bank identity");
        }
    }

    public record AccountId(String value) {
        public AccountId {
            value = require(value, ID, "account identity");
        }
    }

    public record Owner(String tenantId, String participantId) {
        public Owner {
            tenantId = require(tenantId, OWNER, "tenant identity");
            participantId = require(participantId, OWNER, "participant identity");
        }
    }

    public record FixtureVersion(String value) {
        public FixtureVersion {
            value = require(value, VERSION, "fixture version");
        }
    }

    private static String require(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
