package io.github.johnwhitton.digitalbanking.application.port;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Resolves immutable, non-secret, server-owned wallet identity metadata. */
public interface WalletIdentityRegistry {

    WalletIdentity resolve(WalletReference reference);

    List<WalletIdentity> identities();

    record WalletIdentity(
            WalletReference reference,
            Set<WalletReference> aliases,
            OwnerCategory ownerCategory,
            SettlementNetwork network,
            String normalizedAddress,
            KeyAlias keyReference,
            String registryVersion,
            String keyVersion,
            Set<Purpose> allowedPurposes,
            Status status) {

        public WalletIdentity {
            Objects.requireNonNull(reference, "reference");
            aliases = Set.copyOf(Objects.requireNonNull(aliases, "aliases"));
            Objects.requireNonNull(ownerCategory, "ownerCategory");
            Objects.requireNonNull(network, "network");
            normalizedAddress = requireText(normalizedAddress, "normalizedAddress", 256);
            Objects.requireNonNull(keyReference, "keyReference");
            registryVersion = requireText(registryVersion, "registryVersion", 128);
            keyVersion = requireText(keyVersion, "keyVersion", 128);
            allowedPurposes = Set.copyOf(
                    Objects.requireNonNull(allowedPurposes, "allowedPurposes"));
            Objects.requireNonNull(status, "status");
            if (aliases.contains(reference)) {
                throw new IllegalArgumentException(
                        "wallet aliases must not repeat the primary reference");
            }
            if (allowedPurposes.isEmpty()) {
                throw new IllegalArgumentException("wallet signing purposes are required");
            }
        }

        private static String requireText(String value, String field, int maximum) {
            if (value == null || value.isBlank() || value.length() > maximum) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
            }
            return value;
        }
    }

    enum OwnerCategory {
        ADMIN,
        BANK_SETTLEMENT,
        USER_CUSTODY
    }

    enum Purpose {
        CONTRACT_ADMIN,
        CONTRACT_DEPLOYMENT,
        ROLE_ADMINISTRATION,
        MINT_AUTHORITY,
        BURN_AUTHORITY,
        REDEMPTION_CUSTODY,
        BANK_SETTLEMENT_TRANSFER,
        USER_CUSTODY_TRANSFER
    }

    enum Status {
        ENABLED,
        DISABLED
    }
}
