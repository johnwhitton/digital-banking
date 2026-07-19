package io.github.johnwhitton.digitalbanking.domain.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Durable child-oriented companion for one settlement-only transfer. */
public final class SettlementTransfer {

    private static final List<BoundaryKind> PLAN = List.of(
            BoundaryKind.SENDER_ACQUISITION,
            BoundaryKind.USER_TRANSFER,
            BoundaryKind.RECIPIENT_REDEMPTION,
            BoundaryKind.FINAL_RECONCILIATION);

    private final TransferId transferId;
    private final AcceptedContext context;
    private final Status status;
    private final long version;
    private final List<Boundary> boundaries;
    private final List<Transition> transitions;
    private final Optional<ReserveAccounting.ReconciliationStatus> conclusion;

    private SettlementTransfer(
            TransferId transferId,
            AcceptedContext context,
            Status status,
            long version,
            List<Boundary> boundaries,
            List<Transition> transitions,
            Optional<ReserveAccounting.ReconciliationStatus> conclusion) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.context = Objects.requireNonNull(context, "context");
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("settlement transfer version is invalid");
        }
        this.version = version;
        this.boundaries = List.copyOf(boundaries);
        this.transitions = List.copyOf(transitions);
        this.conclusion = Objects.requireNonNull(conclusion, "conclusion");
        validatePlan();
    }

    public static SettlementTransfer accepted(
            TransferId transferId,
            AcceptedContext context,
            List<BoundaryId> boundaryIds,
            TransitionId transitionId,
            EvidenceRef evidence) {
        Objects.requireNonNull(boundaryIds, "boundaryIds");
        if (boundaryIds.size() != PLAN.size()
                || new HashSet<>(boundaryIds).size() != PLAN.size()) {
            throw new IllegalArgumentException(
                    "four distinct settlement boundary identities are required");
        }
        List<Boundary> boundaries = new ArrayList<>(PLAN.size());
        for (int index = 0; index < PLAN.size(); index++) {
            boundaries.add(new Boundary(
                    boundaryIds.get(index), index + 1, PLAN.get(index),
                    index == 0 ? BoundaryStatus.ELIGIBLE : BoundaryStatus.PENDING,
                    Optional.empty(), Optional.empty()));
        }
        Transition acceptance = new Transition(
                transitionId, 0, Status.ACCEPTED, Status.ACCEPTED,
                Optional.empty(), evidence, context.acceptedAt());
        return new SettlementTransfer(
                transferId, context, Status.ACCEPTED, 0,
                boundaries, List.of(acceptance), Optional.empty());
    }

    public static SettlementTransfer rehydrate(
            TransferId transferId,
            AcceptedContext context,
            Status status,
            long version,
            List<Boundary> boundaries,
            List<Transition> transitions,
            Optional<ReserveAccounting.ReconciliationStatus> conclusion) {
        return new SettlementTransfer(
                transferId, context, status, version, boundaries, transitions, conclusion);
    }

    public Boundary currentBoundary() {
        return boundaries.stream()
                .filter(boundary -> boundary.status() != BoundaryStatus.COMPLETED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "settlement transfer has no current boundary"));
    }

    public SettlementTransfer beginCurrent(
            long expectedVersion,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt) {
        requireMutable(expectedVersion, recordedAt);
        Boundary current = currentBoundary();
        if (current.status() != BoundaryStatus.ELIGIBLE) {
            throw new IllegalStateException("current settlement boundary is not eligible");
        }
        return changed(
                current.withStatus(BoundaryStatus.ACTIVE), pending(current.kind()),
                transitionId, evidence, recordedAt, conclusion);
    }

    public SettlementTransfer attachCurrentChild(
            long expectedVersion,
            ChildReference child,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt) {
        requireMutable(expectedVersion, recordedAt);
        Boundary current = currentBoundary();
        if (!external(current.kind()) || current.status() != BoundaryStatus.ACTIVE
                || current.child().isPresent()) {
            throw new IllegalStateException(
                    "only an active external boundary can attach one child");
        }
        return changed(
                current.withOutcome(
                        BoundaryStatus.ACTIVE, Optional.of(child), Optional.of(evidence)),
                status, transitionId, evidence, recordedAt, conclusion);
    }

    public SettlementTransfer markCurrentUnknown(
            long expectedVersion,
            ChildReference child,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt) {
        requireMutable(expectedVersion, recordedAt);
        Boundary current = currentBoundary();
        if (!external(current.kind()) || current.status() != BoundaryStatus.ACTIVE) {
            throw new IllegalStateException(
                    "only an active external boundary can become unknown");
        }
        if (current.child().isPresent() && !current.child().equals(Optional.of(child))) {
            throw new IllegalStateException("retained child identity cannot change");
        }
        return changed(
                current.withOutcome(
                        BoundaryStatus.UNKNOWN, Optional.of(child), Optional.of(evidence)),
                unknown(current.kind()), transitionId, evidence, recordedAt, conclusion);
    }

    public SettlementTransfer confirmCurrent(
            long expectedVersion,
            Optional<ChildReference> child,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt) {
        requireMutable(expectedVersion, recordedAt);
        Boundary current = currentBoundary();
        if (current.kind() == BoundaryKind.FINAL_RECONCILIATION
                || (current.status() != BoundaryStatus.ACTIVE
                    && current.status() != BoundaryStatus.UNKNOWN)) {
            throw new IllegalStateException("current settlement boundary cannot be confirmed");
        }
        if (child.isEmpty()
                || (current.child().isPresent() && !current.child().equals(child))) {
            throw new IllegalStateException("confirmed child identity is missing or changed");
        }
        Boundary completed = current.withOutcome(
                BoundaryStatus.COMPLETED, child, Optional.of(evidence));
        List<Boundary> changed = replace(completed);
        if (completed.sequence() < changed.size()) {
            Boundary next = changed.get(completed.sequence());
            changed.set(completed.sequence(), next.withStatus(BoundaryStatus.ELIGIBLE));
        }
        return changed(
                changed, completed, completed(current.kind()), transitionId,
                evidence, recordedAt, conclusion);
    }

    public SettlementTransfer failCurrentNoEffect(
            long expectedVersion,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt) {
        requireMutable(expectedVersion, recordedAt);
        Boundary current = currentBoundary();
        if (!external(current.kind())
                || (current.status() != BoundaryStatus.ACTIVE
                    && current.status() != BoundaryStatus.UNKNOWN)) {
            throw new IllegalStateException(
                    "only active or unknown external settlement work can fail");
        }
        boolean priorEffect = boundaries.stream()
                .filter(boundary -> boundary.sequence() < current.sequence())
                .anyMatch(boundary -> boundary.status() == BoundaryStatus.COMPLETED);
        return changed(
                current.withOutcome(
                        priorEffect ? BoundaryStatus.MANUAL_REVIEW
                                : BoundaryStatus.FAILED_NO_EFFECT,
                        current.child(), Optional.of(evidence)),
                priorEffect ? Status.MANUAL_REVIEW : Status.FAILED_NO_EFFECT,
                transitionId, evidence, recordedAt, conclusion);
    }

    public SettlementTransfer requireManualReview(
            long expectedVersion,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt) {
        requireMutable(expectedVersion, recordedAt);
        Boundary current = currentBoundary();
        if (current.status() != BoundaryStatus.ACTIVE
                && current.status() != BoundaryStatus.UNKNOWN) {
            throw new IllegalStateException(
                    "only active or unknown settlement work can require review");
        }
        return changed(
                current.withOutcome(
                        BoundaryStatus.MANUAL_REVIEW,
                        current.child(), Optional.of(evidence)),
                Status.MANUAL_REVIEW, transitionId, evidence, recordedAt, conclusion);
    }

    public SettlementTransfer recordReconciliation(
            long expectedVersion,
            ReserveAccounting.ReconciliationStatus reconciliation,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt) {
        requireMutable(expectedVersion, recordedAt);
        Boundary current = currentBoundary();
        if (current.kind() != BoundaryKind.FINAL_RECONCILIATION
                || current.status() != BoundaryStatus.ACTIVE) {
            throw new IllegalStateException("final reconciliation is not active");
        }
        boolean reconciled = reconciliation
                == ReserveAccounting.ReconciliationStatus.RECONCILED;
        Boundary changed = current.withOutcome(
                reconciled ? BoundaryStatus.COMPLETED : BoundaryStatus.MANUAL_REVIEW,
                Optional.empty(), Optional.of(evidence));
        return changed(
                changed,
                reconciled ? Status.COMPLETED : Status.MANUAL_REVIEW,
                transitionId, evidence, recordedAt, Optional.of(reconciliation));
    }

    private SettlementTransfer changed(
            Boundary changedBoundary,
            Status target,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt,
            Optional<ReserveAccounting.ReconciliationStatus> nextConclusion) {
        return changed(
                replace(changedBoundary), changedBoundary, target,
                transitionId, evidence, recordedAt, nextConclusion);
    }

    private SettlementTransfer changed(
            List<Boundary> changedBoundaries,
            Boundary changedBoundary,
            Status target,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant recordedAt,
            Optional<ReserveAccounting.ReconciliationStatus> nextConclusion) {
        long nextVersion = version + 1;
        List<Transition> changedTransitions = new ArrayList<>(transitions);
        changedTransitions.add(new Transition(
                transitionId, nextVersion, status, target,
                Optional.of(changedBoundary.id()), evidence, recordedAt));
        return new SettlementTransfer(
                transferId, context, target, nextVersion,
                changedBoundaries, changedTransitions, nextConclusion);
    }

    private List<Boundary> replace(Boundary changedBoundary) {
        List<Boundary> changed = new ArrayList<>(boundaries);
        changed.set(changedBoundary.sequence() - 1, changedBoundary);
        return changed;
    }

    private void requireMutable(long expectedVersion, Instant recordedAt) {
        if (expectedVersion != version) {
            throw new IllegalStateException("settlement transfer version conflict");
        }
        if (status == Status.COMPLETED || status == Status.FAILED_NO_EFFECT
                || status == Status.MANUAL_REVIEW) {
            throw new IllegalStateException("settlement transfer is terminal");
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.isBefore(transitions.getLast().recordedAt())) {
            throw new IllegalArgumentException(
                    "settlement transition time is not monotonic");
        }
    }

    private void validatePlan() {
        if (boundaries.size() != PLAN.size() || transitions.isEmpty()
                || transitions.getLast().version() != version
                || transitions.getLast().to() != status) {
            throw new IllegalArgumentException(
                    "settlement transfer history or plan is incomplete");
        }
        if (status == Status.COMPLETED
                && conclusion.orElse(null)
                        != ReserveAccounting.ReconciliationStatus.RECONCILED) {
            throw new IllegalArgumentException(
                    "completed settlement transfer requires reconciliation");
        }
        if (conclusion.isPresent()
                && status != Status.COMPLETED && status != Status.MANUAL_REVIEW) {
            throw new IllegalArgumentException(
                    "settlement conclusion requires a terminal parent");
        }
        boolean foundCurrent = false;
        for (int index = 0; index < boundaries.size(); index++) {
            Boundary boundary = boundaries.get(index);
            if (boundary.sequence() != index + 1 || boundary.kind() != PLAN.get(index)) {
                throw new IllegalArgumentException("settlement boundary order is invalid");
            }
            if (boundary.status() != BoundaryStatus.COMPLETED) {
                if (foundCurrent && boundary.status() != BoundaryStatus.PENDING) {
                    throw new IllegalArgumentException(
                            "later settlement boundary became eligible early");
                }
                foundCurrent = true;
            } else if (foundCurrent) {
                throw new IllegalArgumentException(
                        "settlement completed a later boundary early");
            }
        }
    }

    private static boolean external(BoundaryKind kind) {
        return kind != BoundaryKind.FINAL_RECONCILIATION;
    }

    private static Status pending(BoundaryKind kind) {
        return switch (kind) {
            case SENDER_ACQUISITION -> Status.SENDER_ACQUISITION_PENDING;
            case USER_TRANSFER -> Status.USER_TRANSFER_PENDING;
            case RECIPIENT_REDEMPTION -> Status.RECIPIENT_REDEMPTION_PENDING;
            case FINAL_RECONCILIATION -> Status.FINAL_RECONCILIATION_PENDING;
        };
    }

    private static Status unknown(BoundaryKind kind) {
        return switch (kind) {
            case SENDER_ACQUISITION -> Status.SENDER_ACQUISITION_UNKNOWN;
            case USER_TRANSFER -> Status.USER_TRANSFER_SUBMISSION_UNKNOWN;
            case RECIPIENT_REDEMPTION -> Status.RECIPIENT_REDEMPTION_UNKNOWN;
            case FINAL_RECONCILIATION -> throw new IllegalStateException(
                    "internal reconciliation cannot be unknown");
        };
    }

    private static Status completed(BoundaryKind kind) {
        return switch (kind) {
            case SENDER_ACQUISITION -> Status.SENDER_ACQUISITION_COMPLETED;
            case USER_TRANSFER -> Status.USER_TRANSFER_COMPLETED;
            case RECIPIENT_REDEMPTION -> Status.RECIPIENT_REDEMPTION_COMPLETED;
            case FINAL_RECONCILIATION -> throw new IllegalStateException(
                    "reconciliation requires an explicit conclusion");
        };
    }

    public TransferId transferId() {
        return transferId;
    }

    public AcceptedContext context() {
        return context;
    }

    public Status status() {
        return status;
    }

    public long version() {
        return version;
    }

    public List<Boundary> boundaries() {
        return boundaries;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    public Optional<ReserveAccounting.ReconciliationStatus> conclusion() {
        return conclusion;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SettlementTransfer that
                && transferId.equals(that.transferId)
                && context.equals(that.context)
                && status == that.status
                && version == that.version
                && boundaries.equals(that.boundaries)
                && transitions.equals(that.transitions)
                && conclusion.equals(that.conclusion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                transferId, context, status, version,
                boundaries, transitions, conclusion);
    }

    public record Participant(String tenantId, String participantId) {
        private static final Pattern VALUE = Pattern.compile(
                "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public Participant {
            if (tenantId == null || !VALUE.matcher(tenantId).matches()
                    || participantId == null || !VALUE.matcher(participantId).matches()) {
                throw new IllegalArgumentException(
                        "settlement participant identity is invalid");
            }
        }
    }

    public record RouteSnapshot(
            String instructionId,
            String instructionVersion,
            Participant participant,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId bankAccountId,
            BankAccountReference bankAccountReference,
            WalletReference wallet,
            String walletMetadataVersion,
            InstructionMode mode) {
        public RouteSnapshot {
            instructionId = requireVersion(instructionId, "instructionId");
            instructionVersion = requireVersion(
                    instructionVersion, "instructionVersion");
            Objects.requireNonNull(participant, "participant");
            Objects.requireNonNull(bankId, "bankId");
            Objects.requireNonNull(bankAccountId, "bankAccountId");
            Objects.requireNonNull(bankAccountReference, "bankAccountReference");
            Objects.requireNonNull(wallet, "wallet");
            walletMetadataVersion = requireVersion(
                    walletMetadataVersion, "walletMetadataVersion");
            Objects.requireNonNull(mode, "mode");
        }
    }

    public record AcceptedContext(
            String workflowVersion,
            UsdCents usdAmount,
            TokenQuantity tokenQuantity,
            RouteSnapshot sender,
            RouteSnapshot recipient,
            WalletReference adminWallet,
            String adminWalletMetadataVersion,
            SettlementNetwork network,
            String contractReference,
            String payoutPolicyVersion,
            String conversionPolicyVersion,
            String accountingPolicyVersion,
            String feePolicyVersion,
            String finalityPolicyVersion,
            String reconciliationPolicyVersion,
            String idempotencyKeyDigest,
            String commandDigest,
            Instant acceptedAt) {
        private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

        public AcceptedContext {
            workflowVersion = requireVersion(workflowVersion, "workflowVersion");
            Objects.requireNonNull(usdAmount, "usdAmount");
            Objects.requireNonNull(tokenQuantity, "tokenQuantity");
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(adminWallet, "adminWallet");
            adminWalletMetadataVersion = requireVersion(
                    adminWalletMetadataVersion, "adminWalletMetadataVersion");
            Objects.requireNonNull(network, "network");
            contractReference = requireVersion(contractReference, "contractReference");
            payoutPolicyVersion = requireVersion(
                    payoutPolicyVersion, "payoutPolicyVersion");
            conversionPolicyVersion = requireVersion(
                    conversionPolicyVersion, "conversionPolicyVersion");
            accountingPolicyVersion = requireVersion(
                    accountingPolicyVersion, "accountingPolicyVersion");
            feePolicyVersion = requireVersion(feePolicyVersion, "feePolicyVersion");
            finalityPolicyVersion = requireVersion(
                    finalityPolicyVersion, "finalityPolicyVersion");
            reconciliationPolicyVersion = requireVersion(
                    reconciliationPolicyVersion, "reconciliationPolicyVersion");
            if (idempotencyKeyDigest == null
                    || !DIGEST.matcher(idempotencyKeyDigest).matches()
                    || commandDigest == null || !DIGEST.matcher(commandDigest).matches()) {
                throw new IllegalArgumentException("settlement transfer digest is invalid");
            }
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            if (usdAmount.value().signum() <= 0
                    || !usdAmount.value().equals(tokenQuantity.atomicUnits())
                    || sender.mode() != InstructionMode.ACQUISITION
                    || recipient.mode() != InstructionMode.AUTO_REDEEM
                    || sender.participant().equals(recipient.participant())
                    || sender.bankAccountReference().equals(
                            recipient.bankAccountReference())
                    || sender.wallet().equals(recipient.wallet())
                    || sender.wallet().equals(adminWallet)
                    || recipient.wallet().equals(adminWallet)) {
                throw new IllegalArgumentException(
                        "settlement route and exact quantity context is invalid");
            }
        }
    }

    public record Boundary(
            BoundaryId id,
            int sequence,
            BoundaryKind kind,
            BoundaryStatus status,
            Optional<ChildReference> child,
            Optional<EvidenceRef> evidence) {
        public Boundary {
            Objects.requireNonNull(id, "id");
            if (sequence < 1 || sequence > PLAN.size()) {
                throw new IllegalArgumentException("settlement boundary sequence is invalid");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            child = Objects.requireNonNull(child, "child");
            evidence = Objects.requireNonNull(evidence, "evidence");
            if (kind == BoundaryKind.FINAL_RECONCILIATION && child.isPresent()) {
                throw new IllegalArgumentException(
                        "final reconciliation cannot retain a child operation");
            }
        }

        private Boundary withStatus(BoundaryStatus target) {
            return new Boundary(id, sequence, kind, target, child, evidence);
        }

        private Boundary withOutcome(
                BoundaryStatus target,
                Optional<ChildReference> nextChild,
                Optional<EvidenceRef> nextEvidence) {
            return new Boundary(
                    id, sequence, kind, target, nextChild, nextEvidence);
        }
    }

    public record Transition(
            TransitionId id,
            long version,
            Status from,
            Status to,
            Optional<BoundaryId> boundaryId,
            EvidenceRef evidence,
            Instant recordedAt) {
        public Transition {
            Objects.requireNonNull(id, "id");
            if (version < 0) {
                throw new IllegalArgumentException(
                        "settlement transition version is invalid");
            }
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            boundaryId = Objects.requireNonNull(boundaryId, "boundaryId");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }

    public record BoundaryId(UUID value) {
        public BoundaryId {
            Objects.requireNonNull(value, "boundaryId");
        }
    }

    public record TransitionId(UUID value) {
        public TransitionId {
            Objects.requireNonNull(value, "transitionId");
        }
    }

    public record ChildReference(String value) {
        private static final Pattern VALUE = Pattern.compile(
                "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public ChildReference {
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "settlement child reference is invalid");
            }
        }
    }

    private static String requireVersion(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    public enum InstructionMode {
        ACQUISITION,
        AUTO_REDEEM
    }

    public enum BoundaryKind {
        SENDER_ACQUISITION,
        USER_TRANSFER,
        RECIPIENT_REDEMPTION,
        FINAL_RECONCILIATION
    }

    public enum BoundaryStatus {
        PENDING,
        ELIGIBLE,
        ACTIVE,
        UNKNOWN,
        COMPLETED,
        FAILED_NO_EFFECT,
        MANUAL_REVIEW
    }

    public enum Status {
        ACCEPTED,
        SENDER_ACQUISITION_PENDING,
        SENDER_ACQUISITION_UNKNOWN,
        SENDER_ACQUISITION_COMPLETED,
        USER_TRANSFER_PENDING,
        USER_TRANSFER_SUBMISSION_UNKNOWN,
        USER_TRANSFER_COMPLETED,
        RECIPIENT_REDEMPTION_PENDING,
        RECIPIENT_REDEMPTION_UNKNOWN,
        RECIPIENT_REDEMPTION_COMPLETED,
        FINAL_RECONCILIATION_PENDING,
        COMPLETED,
        FAILED_NO_EFFECT,
        MANUAL_REVIEW
    }
}
