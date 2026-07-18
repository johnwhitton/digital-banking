package io.github.johnwhitton.digitalbanking.domain.workflow;

import java.time.Instant;
import java.util.ArrayList;
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
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Durable parent for one user-held USDZELLE acquisition or redemption. */
public final class UsdzelleWorkflow {

    private static final List<StepKind> ACQUISITION_STEPS = List.of(
            StepKind.WITHDRAWAL,
            StepKind.RESERVE_FUNDING_POST,
            StepKind.MINT,
            StepKind.MINT_ACCOUNTING_POST,
            StepKind.RECONCILIATION);
    private static final List<StepKind> REDEMPTION_STEPS = List.of(
            StepKind.CUSTODY_TRANSFER,
            StepKind.CUSTODY_ACCOUNTING_POST,
            StepKind.PAYOUT,
            StepKind.PAYOUT_ACCOUNTING_POST,
            StepKind.BURN,
            StepKind.RECONCILIATION);

    private final Id id;
    private final Kind kind;
    private final Participant participant;
    private final AcceptedContext context;
    private final Status status;
    private final long version;
    private final List<Step> steps;
    private final List<Transition> transitions;
    private final Optional<ReserveAccounting.ReconciliationStatus> reconciliationConclusion;

    private UsdzelleWorkflow(
            Id id,
            Kind kind,
            Participant participant,
            AcceptedContext context,
            Status status,
            long version,
            List<Step> steps,
            List<Transition> transitions,
            Optional<ReserveAccounting.ReconciliationStatus> reconciliationConclusion) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.participant = Objects.requireNonNull(participant, "participant");
        this.context = Objects.requireNonNull(context, "context");
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("workflow version is invalid");
        }
        this.version = version;
        this.steps = List.copyOf(steps);
        this.transitions = List.copyOf(transitions);
        this.reconciliationConclusion = Objects.requireNonNull(
                reconciliationConclusion, "reconciliationConclusion");
        validatePlan();
    }

    public static UsdzelleWorkflow accepted(
            Id id,
            Kind kind,
            Participant participant,
            AcceptedContext context,
            List<StepId> stepIds,
            TransitionId transitionId,
            EvidenceRef evidence) {
        Objects.requireNonNull(stepIds, "stepIds");
        List<StepKind> kinds = kinds(kind);
        if (stepIds.size() != kinds.size() || stepIds.stream().distinct().count() != kinds.size()) {
            throw new IllegalArgumentException("workflow step identities do not match the plan");
        }
        List<Step> steps = new ArrayList<>(kinds.size());
        for (int index = 0; index < kinds.size(); index++) {
            steps.add(new Step(
                    stepIds.get(index), index + 1, kinds.get(index),
                    index == 0 ? StepStatus.ELIGIBLE : StepStatus.PENDING,
                    Optional.empty(), Optional.empty()));
        }
        Transition acceptance = new Transition(
                transitionId, 0, Status.ACCEPTED, Status.ACCEPTED, Optional.empty(),
                evidence, context.acceptedAt());
        return new UsdzelleWorkflow(
                id, kind, participant, context, Status.ACCEPTED, 0, steps,
                List.of(acceptance), Optional.empty());
    }

    public static UsdzelleWorkflow rehydrate(
            Id id,
            Kind kind,
            Participant participant,
            AcceptedContext context,
            Status status,
            long version,
            List<Step> steps,
            List<Transition> transitions,
            Optional<ReserveAccounting.ReconciliationStatus> reconciliationConclusion) {
        return new UsdzelleWorkflow(
                id, kind, participant, context, status, version, steps, transitions,
                reconciliationConclusion);
    }

    public Id id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public Participant participant() {
        return participant;
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

    public List<Step> steps() {
        return steps;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    public Optional<ReserveAccounting.ReconciliationStatus> reconciliationConclusion() {
        return reconciliationConclusion;
    }

    public Step currentStep() {
        return steps.stream()
                .filter(step -> step.status() != StepStatus.COMPLETED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workflow has no current step"));
    }

    public UsdzelleWorkflow beginCurrent(
            long expectedVersion, TransitionId transitionId, EvidenceRef evidence, Instant at) {
        requireMutable(expectedVersion, at);
        Step current = currentStep();
        if (current.status() != StepStatus.ELIGIBLE) {
            throw new IllegalStateException("current workflow step is not eligible");
        }
        Status target = dispatchStatus(current.kind());
        return changed(current.withStatus(StepStatus.ACTIVE), target, transitionId, evidence, at);
    }

    public UsdzelleWorkflow markCurrentUnknown(
            long expectedVersion,
            ChildReference childReference,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at) {
        requireMutable(expectedVersion, at);
        Step current = currentStep();
        if (current.status() != StepStatus.ACTIVE || !isExternal(current.kind())) {
            throw new IllegalStateException("only an active external step can be unknown");
        }
        return changed(
                current.withOutcome(
                        StepStatus.UNKNOWN, Optional.of(childReference), Optional.of(evidence)),
                unknownStatus(current.kind()), transitionId, evidence, at);
    }

    public UsdzelleWorkflow attachCurrentChild(
            long expectedVersion,
            ChildReference childReference,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at) {
        requireMutable(expectedVersion, at);
        Step current = currentStep();
        if (current.status() != StepStatus.ACTIVE || !isExternal(current.kind())
                || current.childReference().isPresent()) {
            throw new IllegalStateException(
                    "only an active external step without a child can be dispatched");
        }
        return changed(
                current.withOutcome(
                        StepStatus.ACTIVE, Optional.of(childReference), Optional.of(evidence)),
                status, transitionId, evidence, at);
    }

    public UsdzelleWorkflow confirmCurrent(
            long expectedVersion,
            Optional<ChildReference> childReference,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at) {
        requireMutable(expectedVersion, at);
        Step current = currentStep();
        if (current.kind() == StepKind.RECONCILIATION
                || (current.status() != StepStatus.ACTIVE
                    && current.status() != StepStatus.UNKNOWN)) {
            throw new IllegalStateException("current workflow step cannot be confirmed");
        }
        if (isExternal(current.kind()) && childReference.isEmpty()) {
            throw new IllegalArgumentException("external step requires child identity");
        }
        if (current.childReference().isPresent()
                && !current.childReference().equals(childReference)) {
            throw new IllegalStateException("unknown child identity cannot be replaced");
        }
        Step completed = current.withOutcome(
                StepStatus.COMPLETED, childReference, Optional.of(evidence));
        Status target = confirmationStatus(current.kind());
        return changedAndMakeNextEligible(completed, target, transitionId, evidence, at);
    }

    public UsdzelleWorkflow recordReconciliation(
            long expectedVersion,
            ReserveAccounting.ReconciliationStatus conclusion,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at) {
        requireMutable(expectedVersion, at);
        Objects.requireNonNull(conclusion, "conclusion");
        Step current = currentStep();
        if (current.kind() != StepKind.RECONCILIATION
                || current.status() != StepStatus.ACTIVE) {
            throw new IllegalStateException("reconciliation is not active");
        }
        if (conclusion == ReserveAccounting.ReconciliationStatus.RECONCILED) {
            return changedWithConclusion(
                    current.withOutcome(
                            StepStatus.COMPLETED, Optional.empty(), Optional.of(evidence)),
                    Status.COMPLETED, transitionId, evidence, at, conclusion);
        }
        return changedWithConclusion(
                current.withOutcome(
                        StepStatus.MANUAL_REVIEW, Optional.empty(), Optional.of(evidence)),
                Status.MANUAL_REVIEW, transitionId, evidence, at, conclusion);
    }

    public UsdzelleWorkflow failCurrentNoEffect(
            long expectedVersion, TransitionId transitionId,
            EvidenceRef evidence, Instant at) {
        requireMutable(expectedVersion, at);
        Step current = currentStep();
        if (current.status() != StepStatus.ACTIVE) {
            throw new IllegalStateException("only an active step can fail without effect");
        }
        return changed(
                current.withOutcome(
                        StepStatus.FAILED_NO_EFFECT, current.childReference(),
                        Optional.of(evidence)),
                Status.FAILED_NO_EFFECT, transitionId, evidence, at);
    }

    public UsdzelleWorkflow requireManualReview(
            long expectedVersion, TransitionId transitionId,
            EvidenceRef evidence, Instant at) {
        requireMutable(expectedVersion, at);
        Step current = currentStep();
        if (current.status() != StepStatus.ACTIVE
                && current.status() != StepStatus.UNKNOWN) {
            throw new IllegalStateException("only current work can require manual review");
        }
        return changed(
                current.withOutcome(
                        StepStatus.MANUAL_REVIEW, current.childReference(),
                        Optional.of(evidence)),
                Status.MANUAL_REVIEW, transitionId, evidence, at);
    }

    private UsdzelleWorkflow changedAndMakeNextEligible(
            Step changedStep,
            Status target,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at) {
        List<Step> changed = replace(changedStep);
        int next = changedStep.sequence();
        if (next < changed.size()) {
            Step successor = changed.get(next);
            changed.set(next, successor.withStatus(StepStatus.ELIGIBLE));
        }
        return changed(changed, changedStep, target, transitionId, evidence, at);
    }

    private UsdzelleWorkflow changed(
            Step changedStep,
            Status target,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at) {
        return changed(replace(changedStep), changedStep, target, transitionId, evidence, at);
    }

    private UsdzelleWorkflow changedWithConclusion(
            Step changedStep,
            Status target,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at,
            ReserveAccounting.ReconciliationStatus conclusion) {
        long nextVersion = version + 1;
        List<Transition> changedTransitions = new ArrayList<>(transitions);
        changedTransitions.add(new Transition(
                transitionId, nextVersion, status, target, Optional.of(changedStep.id()),
                evidence, at));
        return new UsdzelleWorkflow(
                id, kind, participant, context, target, nextVersion,
                replace(changedStep), changedTransitions,
                Optional.of(conclusion));
    }

    private UsdzelleWorkflow changed(
            List<Step> changedSteps,
            Step changedStep,
            Status target,
            TransitionId transitionId,
            EvidenceRef evidence,
            Instant at) {
        long nextVersion = version + 1;
        List<Transition> changedTransitions = new ArrayList<>(transitions);
        changedTransitions.add(new Transition(
                transitionId, nextVersion, status, target, Optional.of(changedStep.id()),
                evidence, at));
        return new UsdzelleWorkflow(
                id, kind, participant, context, target, nextVersion,
                changedSteps, changedTransitions, reconciliationConclusion);
    }

    private List<Step> replace(Step changedStep) {
        List<Step> changed = new ArrayList<>(steps);
        changed.set(changedStep.sequence() - 1, changedStep);
        return changed;
    }

    private void requireMutable(long expectedVersion, Instant at) {
        if (expectedVersion != version) {
            throw new IllegalStateException("workflow version conflict");
        }
        if (status == Status.COMPLETED || status == Status.FAILED_NO_EFFECT
                || status == Status.MANUAL_REVIEW) {
            throw new IllegalStateException("workflow is terminal");
        }
        Objects.requireNonNull(at, "recordedAt");
        if (at.isBefore(transitions.getLast().recordedAt())) {
            throw new IllegalArgumentException("workflow transition time is not monotonic");
        }
    }

    private void validatePlan() {
        List<StepKind> expected = kinds(kind);
        if (steps.size() != expected.size() || transitions.isEmpty()
                || transitions.getLast().version() != version
                || transitions.getLast().to() != status) {
            throw new IllegalArgumentException("workflow history or plan is incomplete");
        }
        if (status == Status.COMPLETED
                && reconciliationConclusion.orElse(null)
                    != ReserveAccounting.ReconciliationStatus.RECONCILED) {
            throw new IllegalArgumentException(
                    "completed workflow requires a reconciled conclusion");
        }
        if (reconciliationConclusion.isPresent()
                && status != Status.COMPLETED && status != Status.MANUAL_REVIEW) {
            throw new IllegalArgumentException(
                    "reconciliation conclusion requires a terminal workflow");
        }
        boolean foundCurrent = false;
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            if (step.sequence() != index + 1 || step.kind() != expected.get(index)) {
                throw new IllegalArgumentException("workflow step order is invalid");
            }
            if (step.status() != StepStatus.COMPLETED) {
                if (foundCurrent && step.status() != StepStatus.PENDING) {
                    throw new IllegalArgumentException("later workflow step became eligible early");
                }
                foundCurrent = true;
            } else if (foundCurrent) {
                throw new IllegalArgumentException("workflow completed a later step early");
            }
        }
    }

    private static List<StepKind> kinds(Kind kind) {
        return Objects.requireNonNull(kind, "kind") == Kind.ACQUISITION
                ? ACQUISITION_STEPS : REDEMPTION_STEPS;
    }

    private static boolean isExternal(StepKind kind) {
        return switch (kind) {
            case WITHDRAWAL, MINT, CUSTODY_TRANSFER, PAYOUT, BURN -> true;
            case RESERVE_FUNDING_POST, MINT_ACCOUNTING_POST,
                    CUSTODY_ACCOUNTING_POST, PAYOUT_ACCOUNTING_POST,
                    RECONCILIATION -> false;
        };
    }

    private static Status dispatchStatus(StepKind kind) {
        return switch (kind) {
            case WITHDRAWAL -> Status.WITHDRAWAL_DISPATCH_PENDING;
            case RESERVE_FUNDING_POST -> Status.RESERVE_FUNDING_POST_PENDING;
            case MINT -> Status.MINT_DISPATCH_PENDING;
            case MINT_ACCOUNTING_POST -> Status.MINT_ACCOUNTING_POST_PENDING;
            case CUSTODY_TRANSFER -> Status.CUSTODY_TRANSFER_DISPATCH_PENDING;
            case CUSTODY_ACCOUNTING_POST -> Status.CUSTODY_ACCOUNTING_POST_PENDING;
            case PAYOUT -> Status.PAYOUT_DISPATCH_PENDING;
            case PAYOUT_ACCOUNTING_POST -> Status.PAYOUT_ACCOUNTING_POST_PENDING;
            case BURN -> Status.BURN_DISPATCH_PENDING;
            case RECONCILIATION -> Status.RECONCILIATION_PENDING;
        };
    }

    private static Status unknownStatus(StepKind kind) {
        return switch (kind) {
            case WITHDRAWAL -> Status.WITHDRAWAL_UNKNOWN;
            case MINT -> Status.MINT_SUBMISSION_UNKNOWN;
            case CUSTODY_TRANSFER -> Status.CUSTODY_SUBMISSION_UNKNOWN;
            case PAYOUT -> Status.PAYOUT_UNKNOWN;
            case BURN -> Status.BURN_SUBMISSION_UNKNOWN;
            default -> throw new IllegalStateException("internal step cannot be unknown");
        };
    }

    private static Status confirmationStatus(StepKind kind) {
        return switch (kind) {
            case WITHDRAWAL -> Status.WITHDRAWAL_CONFIRMED;
            case RESERVE_FUNDING_POST -> Status.RESERVE_FUNDED;
            case MINT -> Status.MINT_CONFIRMED;
            case MINT_ACCOUNTING_POST -> Status.MINT_ACCOUNTED;
            case CUSTODY_TRANSFER -> Status.CUSTODY_CONFIRMED;
            case CUSTODY_ACCOUNTING_POST -> Status.REDEMPTION_PAYABLE_RECORDED;
            case PAYOUT -> Status.PAYOUT_CONFIRMED;
            case PAYOUT_ACCOUNTING_POST -> Status.PAYOUT_ACCOUNTED;
            case BURN -> Status.BURN_CONFIRMED;
            case RECONCILIATION -> throw new IllegalStateException(
                    "reconciliation requires a conclusion");
        };
    }

    public record Id(UUID value) {
        public Id {
            Objects.requireNonNull(value, "workflow identity");
        }
    }

    public record StepId(UUID value) {
        public StepId {
            Objects.requireNonNull(value, "workflow step identity");
        }
    }

    public record TransitionId(UUID value) {
        public TransitionId {
            Objects.requireNonNull(value, "workflow transition identity");
        }
    }

    public record ChildReference(String value) {
        private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public ChildReference {
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("child reference is invalid");
            }
        }
    }

    public record Participant(String tenantId, String participantId) {
        private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public Participant {
            if (tenantId == null || !VALUE.matcher(tenantId).matches()
                    || participantId == null || !VALUE.matcher(participantId).matches()) {
                throw new IllegalArgumentException("workflow participant is invalid");
            }
        }
    }

    public record AcceptedContext(
            String workflowVersion,
            UsdCents usdAmount,
            TokenQuantity tokenQuantity,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId bankAccountId,
            WalletReference userWallet,
            String userWalletMetadataVersion,
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

        private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
        private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

        public AcceptedContext {
            requireVersion(workflowVersion, "workflowVersion");
            Objects.requireNonNull(usdAmount, "usdAmount");
            Objects.requireNonNull(tokenQuantity, "tokenQuantity");
            Objects.requireNonNull(bankId, "bankId");
            Objects.requireNonNull(bankAccountId, "bankAccountId");
            Objects.requireNonNull(userWallet, "userWallet");
            requireVersion(userWalletMetadataVersion, "userWalletMetadataVersion");
            Objects.requireNonNull(adminWallet, "adminWallet");
            requireVersion(adminWalletMetadataVersion, "adminWalletMetadataVersion");
            Objects.requireNonNull(network, "network");
            requireVersion(contractReference, "contractReference");
            requireVersion(payoutPolicyVersion, "payoutPolicyVersion");
            requireVersion(conversionPolicyVersion, "conversionPolicyVersion");
            requireVersion(accountingPolicyVersion, "accountingPolicyVersion");
            requireVersion(feePolicyVersion, "feePolicyVersion");
            requireVersion(finalityPolicyVersion, "finalityPolicyVersion");
            requireVersion(reconciliationPolicyVersion, "reconciliationPolicyVersion");
            if (idempotencyKeyDigest == null || !DIGEST.matcher(idempotencyKeyDigest).matches()
                    || commandDigest == null || !DIGEST.matcher(commandDigest).matches()) {
                throw new IllegalArgumentException("workflow digest is invalid");
            }
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            if (usdAmount.value().signum() <= 0
                    || !usdAmount.value().equals(tokenQuantity.atomicUnits())) {
                throw new IllegalArgumentException(
                        "workflow USD cents and token base units must match exactly");
            }
        }

        private static void requireVersion(String value, String name) {
            if (value == null || !VERSION.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
    }

    public record Step(
            StepId id,
            int sequence,
            StepKind kind,
            StepStatus status,
            Optional<ChildReference> childReference,
            Optional<EvidenceRef> evidenceReference) {
        public Step {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            childReference = Objects.requireNonNull(childReference, "childReference");
            evidenceReference = Objects.requireNonNull(evidenceReference, "evidenceReference");
            if (sequence < 1) {
                throw new IllegalArgumentException("workflow step sequence is invalid");
            }
        }

        private Step withStatus(StepStatus target) {
            return new Step(id, sequence, kind, target, childReference, evidenceReference);
        }

        private Step withOutcome(
                StepStatus target,
                Optional<ChildReference> child,
                Optional<EvidenceRef> evidence) {
            return new Step(id, sequence, kind, target, child, evidence);
        }
    }

    public record Transition(
            TransitionId id,
            long version,
            Status from,
            Status to,
            Optional<StepId> stepId,
            EvidenceRef evidence,
            Instant recordedAt) {
        public Transition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            stepId = Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(recordedAt, "recordedAt");
            if (version < 0) {
                throw new IllegalArgumentException("workflow transition version is invalid");
            }
        }
    }

    public enum Kind {
        ACQUISITION,
        REDEMPTION
    }

    public enum StepKind {
        WITHDRAWAL,
        RESERVE_FUNDING_POST,
        MINT,
        MINT_ACCOUNTING_POST,
        CUSTODY_TRANSFER,
        CUSTODY_ACCOUNTING_POST,
        PAYOUT,
        PAYOUT_ACCOUNTING_POST,
        BURN,
        RECONCILIATION
    }

    public enum StepStatus {
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
        WITHDRAWAL_DISPATCH_PENDING,
        WITHDRAWAL_UNKNOWN,
        WITHDRAWAL_CONFIRMED,
        RESERVE_FUNDING_POST_PENDING,
        RESERVE_FUNDED,
        MINT_DISPATCH_PENDING,
        MINT_SUBMISSION_UNKNOWN,
        MINT_CONFIRMED,
        MINT_ACCOUNTING_POST_PENDING,
        MINT_ACCOUNTED,
        CUSTODY_TRANSFER_DISPATCH_PENDING,
        CUSTODY_SUBMISSION_UNKNOWN,
        CUSTODY_CONFIRMED,
        CUSTODY_ACCOUNTING_POST_PENDING,
        REDEMPTION_PAYABLE_RECORDED,
        PAYOUT_DISPATCH_PENDING,
        PAYOUT_UNKNOWN,
        PAYOUT_CONFIRMED,
        PAYOUT_ACCOUNTING_POST_PENDING,
        PAYOUT_ACCOUNTED,
        BURN_DISPATCH_PENDING,
        BURN_SUBMISSION_UNKNOWN,
        BURN_CONFIRMED,
        RECONCILIATION_PENDING,
        COMPLETED,
        FAILED_NO_EFFECT,
        MANUAL_REVIEW
    }
}
