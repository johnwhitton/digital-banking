package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Trusted provider-neutral boundary for institution-controlled wallet role selection. */
@FunctionalInterface
public interface WalletRoleResolver {

    Resolution resolve(ParticipantScope participant, TransferRouteCatalog.Route route);

    record Resolution(
            WalletReference senderWallet,
            WalletReference recipientWallet,
            String policyVersion) {

        public Resolution {
            Objects.requireNonNull(senderWallet, "senderWallet");
            Objects.requireNonNull(recipientWallet, "recipientWallet");
            if (policyVersion == null || policyVersion.isBlank()
                    || policyVersion.length() > 128) {
                throw new IllegalArgumentException(
                        "policyVersion must contain 1-128 characters");
            }
        }
    }
}
