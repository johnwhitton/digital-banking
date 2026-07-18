package io.github.johnwhitton.digitalbanking.application;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Immutable application-owned context for one standalone wallet transfer. */
public record WalletTransferOperation(
        OperationId operationId,
        TransferId transferId,
        TransferEffect.Id effectId,
        ParticipantScope participant,
        String idempotencyKeyDigest,
        int commandVersion,
        String commandDigest,
        TokenQuantity quantity,
        Purpose purpose,
        WalletSnapshot source,
        WalletSnapshot destination,
        SettlementNetwork network,
        String contractAddress,
        String contractVersion,
        String finalityPolicyVersion,
        AttemptId attemptId,
        Status status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<EvidenceRef> evidence,
        Map<FinalityType, List<FinalityRecord>> finalityHistories) {

    public WalletTransferOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(effectId, "effectId");
        Objects.requireNonNull(participant, "participant");
        idempotencyKeyDigest = requireDigest(idempotencyKeyDigest, "idempotencyKeyDigest");
        if (commandVersion < 1) {
            throw new IllegalArgumentException("commandVersion must be positive");
        }
        commandDigest = requireDigest(commandDigest, "commandDigest");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(purpose, "purpose");
        source = Objects.requireNonNull(source, "source");
        destination = Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(network, "network");
        contractAddress = requireText(contractAddress, "contractAddress", 256);
        contractVersion = requireText(contractVersion, "contractVersion", 128);
        finalityPolicyVersion = requireText(
                finalityPolicyVersion, "finalityPolicyVersion", 128);
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede createdAt");
        }
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("wallet transfer evidence is required");
        }
        finalityHistories = copyFinalities(finalityHistories);
        if (source.reference().equals(destination.reference())
                || source.normalizedAddress().equals(destination.normalizedAddress())) {
            throw new IllegalArgumentException("wallet transfer identities must differ");
        }
        requireUserWallet(source, true);
        if (purpose == Purpose.USER_TRANSFER) {
            requireUserWallet(destination, false);
        } else {
            requireRedemptionWallet(destination);
        }
        if (source.network() != network || destination.network() != network) {
            throw new IllegalArgumentException("wallet network does not match transfer network");
        }
    }

    public WalletTransferOperation transition(
            long expectedVersion, Status next, EvidenceRef transitionEvidence, Instant occurredAt) {
        if (expectedVersion != version || !status.permits(next)) {
            throw new IllegalStateException("wallet transfer transition is not permitted");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (occurredAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("transition cannot precede acceptance");
        }
        return new WalletTransferOperation(
                operationId, transferId, effectId, participant, idempotencyKeyDigest,
                commandVersion, commandDigest, quantity, purpose, source, destination, network,
                contractAddress, contractVersion, finalityPolicyVersion, attemptId,
                next, version + 1, createdAt, occurredAt,
                append(evidence, transitionEvidence),
                finalityHistories);
    }

    public WalletTransferOperation reachBlockchainFinality(
            long expectedVersion, String authority, EvidenceRef finalityEvidence,
            Instant occurredAt) {
        if (status != Status.OBSERVING || expectedVersion != version) {
            throw new IllegalStateException("observation is not ready for finality");
        }
        Map<FinalityType, List<FinalityRecord>> changed =
                new EnumMap<>(finalityHistories);
        List<FinalityRecord> blockchain = changed.get(FinalityType.BLOCKCHAIN);
        FinalityRecord next = FinalityRecord.assessed(
                FinalityType.BLOCKCHAIN, FinalityStatus.REACHED, authority,
                finalityPolicyVersion, occurredAt, List.of(finalityEvidence));
        changed.put(FinalityType.BLOCKCHAIN, append(blockchain, next));
        return new WalletTransferOperation(
                operationId, transferId, effectId, participant, idempotencyKeyDigest,
                commandVersion, commandDigest, quantity, purpose, source, destination, network,
                contractAddress, contractVersion, finalityPolicyVersion, attemptId,
                Status.CHAIN_FINALITY_REACHED, version + 1, createdAt, occurredAt,
                append(evidence, finalityEvidence), changed);
    }

    public static Map<FinalityType, List<FinalityRecord>> initialFinalities() {
        Map<FinalityType, List<FinalityRecord>> values = new EnumMap<>(FinalityType.class);
        for (FinalityType type : FinalityType.values()) {
            values.put(type, List.of(FinalityRecord.notAssessed(type)));
        }
        return Map.copyOf(values);
    }

    private static void requireUserWallet(WalletSnapshot wallet, boolean source) {
        if (wallet.ownerCategory() != WalletIdentityRegistry.OwnerCategory.USER_CUSTODY
                || wallet.status() != WalletIdentityRegistry.Status.ENABLED
                || !wallet.allowedPurposes().contains(
                        WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER)) {
            throw new IllegalArgumentException(
                    (source ? "source" : "destination") + " must be an enabled user wallet");
        }
    }

    private static void requireRedemptionWallet(WalletSnapshot wallet) {
        if (wallet.ownerCategory() != WalletIdentityRegistry.OwnerCategory.ADMIN
                || wallet.status() != WalletIdentityRegistry.Status.ENABLED
                || !wallet.allowedPurposes().contains(
                        WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY)) {
            throw new IllegalArgumentException(
                    "destination must be the enabled ADMIN redemption wallet");
        }
    }

    private static Map<FinalityType, List<FinalityRecord>> copyFinalities(
            Map<FinalityType, List<FinalityRecord>> input) {
        Objects.requireNonNull(input, "finalityHistories");
        Map<FinalityType, List<FinalityRecord>> copy = new EnumMap<>(FinalityType.class);
        for (FinalityType type : FinalityType.values()) {
            List<FinalityRecord> history = List.copyOf(
                    Objects.requireNonNull(input.get(type), "finality history"));
            if (history.isEmpty() || history.getFirst().type() != type) {
                throw new IllegalArgumentException("finality histories are incomplete");
            }
            copy.put(type, history);
        }
        return Map.copyOf(copy);
    }

    private static <T> List<T> append(List<T> values, T value) {
        var changed = new java.util.ArrayList<>(values);
        changed.add(Objects.requireNonNull(value, "value"));
        return List.copyOf(changed);
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    private static String requireText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return value;
    }

    public record WalletSnapshot(
            WalletReference reference,
            WalletIdentityRegistry.OwnerCategory ownerCategory,
            SettlementNetwork network,
            String normalizedAddress,
            KeyAlias keyReference,
            String registryVersion,
            String keyVersion,
            java.util.Set<WalletIdentityRegistry.Purpose> allowedPurposes,
            WalletIdentityRegistry.Status status) {

        public WalletSnapshot {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(ownerCategory, "ownerCategory");
            Objects.requireNonNull(network, "network");
            normalizedAddress = requireText(normalizedAddress, "normalizedAddress", 256);
            Objects.requireNonNull(keyReference, "keyReference");
            registryVersion = requireText(registryVersion, "registryVersion", 128);
            keyVersion = requireText(keyVersion, "keyVersion", 128);
            allowedPurposes = java.util.Set.copyOf(allowedPurposes);
            Objects.requireNonNull(status, "status");
        }

        public static WalletSnapshot from(WalletIdentityRegistry.WalletIdentity identity) {
            return from(identity, identity.reference());
        }

        public static WalletSnapshot from(
                WalletIdentityRegistry.WalletIdentity identity,
                WalletReference resolvedReference) {
            if (!identity.reference().equals(resolvedReference)
                    && !identity.aliases().contains(resolvedReference)) {
                throw new IllegalArgumentException(
                        "resolved wallet reference is not a configured identity or alias");
            }
            return new WalletSnapshot(
                    resolvedReference, identity.ownerCategory(), identity.network(),
                    identity.normalizedAddress(), identity.keyReference(),
                    identity.registryVersion(), identity.keyVersion(),
                    identity.allowedPurposes(), identity.status());
        }
    }

    public enum Purpose {
        USER_TRANSFER,
        REDEMPTION_CUSTODY
    }

    public enum Status {
        ACCEPTED,
        SIGNING,
        SUBMISSION_PENDING,
        SUBMISSION_AMBIGUOUS,
        OBSERVING,
        CHAIN_FINALITY_REACHED,
        COMPLETED,
        MANUAL_REVIEW,
        FAILED_NO_EFFECT;

        boolean permits(Status next) {
            return switch (this) {
                case ACCEPTED -> next == SIGNING;
                case SIGNING -> next == SUBMISSION_PENDING || next == MANUAL_REVIEW;
                case SUBMISSION_PENDING -> next == OBSERVING
                        || next == SUBMISSION_AMBIGUOUS || next == FAILED_NO_EFFECT;
                case SUBMISSION_AMBIGUOUS -> next == OBSERVING
                        || next == MANUAL_REVIEW || next == FAILED_NO_EFFECT;
                case OBSERVING -> next == MANUAL_REVIEW;
                case CHAIN_FINALITY_REACHED -> next == COMPLETED;
                case COMPLETED, MANUAL_REVIEW, FAILED_NO_EFFECT -> false;
            };
        }
    }
}
