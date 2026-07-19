package io.github.johnwhitton.digitalbanking.application;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionResolver;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferStepExecutor;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Reuses Phase 6B and Phase 5C child authorities for one Phase 6C boundary. */
public final class LocalSettlementTransferStepExecutor
        implements SettlementTransferStepExecutor {

    private final UsdzelleWorkflowApplicationService workflowAcceptance;
    private final UsdzelleWorkflowRepository workflows;
    private final WalletTransferAcceptanceService transferAcceptance;
    private final WalletTransferRepository walletTransfers;
    private final AccountingApplicationService accounting;
    private final SettlementInstructionResolver instructions;

    public LocalSettlementTransferStepExecutor(
            UsdzelleWorkflowApplicationService workflowAcceptance,
            UsdzelleWorkflowRepository workflows,
            WalletTransferAcceptanceService transferAcceptance,
            WalletTransferRepository walletTransfers,
            AccountingApplicationService accounting,
            SettlementInstructionResolver instructions) {
        this.workflowAcceptance = Objects.requireNonNull(
                workflowAcceptance, "workflowAcceptance");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.transferAcceptance = Objects.requireNonNull(
                transferAcceptance, "transferAcceptance");
        this.walletTransfers = Objects.requireNonNull(
                walletTransfers, "walletTransfers");
        this.accounting = Objects.requireNonNull(accounting, "accounting");
        this.instructions = Objects.requireNonNull(instructions, "instructions");
    }

    @Override
    public Result execute(SettlementTransfer transfer) {
        Objects.requireNonNull(transfer, "transfer");
        try {
            instructions.verifyAccepted(transfer);
            return switch (transfer.currentBoundary().kind()) {
                case SENDER_ACQUISITION -> acquisition(transfer);
                case USER_TRANSFER -> walletTransfer(transfer);
                case RECIPIENT_REDEMPTION -> redemption(transfer);
                case FINAL_RECONCILIATION -> reconciliation(transfer);
            };
        } catch (IdempotencyConflictException | InvalidRequestException
                | UnsupportedTransferConfigurationException
                | IllegalStateException unsafe) {
            return new ManualReview(evidence(
                    "authority-or-child-conflict", transfer.transferId().value()));
        }
    }

    private Result acquisition(SettlementTransfer transfer) {
        Optional<SettlementTransfer.ChildReference> retained =
                transfer.currentBoundary().child();
        if (retained.isEmpty()) {
            SettlementTransfer.RouteSnapshot sender = transfer.context().sender();
            UsdzelleWorkflowAcceptance accepted = workflowAcceptance.accept(
                    UsdzelleWorkflow.Kind.ACQUISITION,
                    participant(sender.participant()),
                    workflowRequest(transfer, sender),
                    key(transfer, "sender-acquisition"));
            verifyWorkflow(transfer, accepted.workflow(), sender,
                    UsdzelleWorkflow.Kind.ACQUISITION);
            return new Dispatched(
                    child(accepted.workflow().id().value()),
                    evidence("sender-acquisition-accepted",
                            accepted.workflow().id().value()));
        }
        UsdzelleWorkflow child = workflow(retained.orElseThrow());
        verifyWorkflow(transfer, child, transfer.context().sender(),
                UsdzelleWorkflow.Kind.ACQUISITION);
        return workflowResult(child, "sender-acquisition");
    }

    private Result walletTransfer(SettlementTransfer transfer) {
        Optional<SettlementTransfer.ChildReference> retained =
                transfer.currentBoundary().child();
        WalletTransferOperation operation;
        if (retained.isEmpty()) {
            SettlementTransfer.AcceptedContext context = transfer.context();
            operation = transferAcceptance.accept(
                    participant(context.sender().participant()),
                    key(transfer, "user-transfer"),
                    new WalletTransferAcceptanceService.Request(
                            context.tokenQuantity().toCanonicalString(),
                            context.tokenQuantity().unit().assetId(),
                            context.tokenQuantity().unit().unitId(),
                            context.tokenQuantity().unit().version(),
                            context.sender().wallet(), context.recipient().wallet()))
                    .operation();
            verifyWalletTransfer(transfer, operation);
            return new Dispatched(
                    child(operation.operationId().value()),
                    evidence("user-transfer-accepted", operation.operationId().value()));
        }
        operation = walletTransfers.findById(OperationId.from(
                        retained.orElseThrow().value()))
                .orElseThrow(() -> new IllegalStateException(
                        "settlement wallet-transfer child is unavailable"));
        verifyWalletTransfer(transfer, operation);
        return switch (operation.status()) {
            case COMPLETED -> new Confirmed(
                    Optional.of(child(operation.operationId().value())),
                    operation.evidence().getLast());
            case SUBMISSION_AMBIGUOUS -> new Unknown(
                    child(operation.operationId().value()),
                    evidence("user-transfer-unknown", operation.operationId().value()));
            case MANUAL_REVIEW -> new ManualReview(evidence(
                    "user-transfer-manual-review", operation.operationId().value()));
            case FAILED_NO_EFFECT -> new RejectedNoEffect(evidence(
                    "user-transfer-no-effect", operation.operationId().value()));
            default -> new Pending(evidence(
                    "user-transfer-pending", operation.operationId().value()));
        };
    }

    private Result redemption(SettlementTransfer transfer) {
        Optional<SettlementTransfer.ChildReference> retained =
                transfer.currentBoundary().child();
        if (retained.isEmpty()) {
            SettlementTransfer.RouteSnapshot recipient = transfer.context().recipient();
            UsdzelleWorkflowAcceptance accepted = workflowAcceptance.accept(
                    UsdzelleWorkflow.Kind.REDEMPTION,
                    participant(recipient.participant()),
                    workflowRequest(transfer, recipient),
                    key(transfer, "recipient-redemption"));
            verifyWorkflow(transfer, accepted.workflow(), recipient,
                    UsdzelleWorkflow.Kind.REDEMPTION);
            return new Dispatched(
                    child(accepted.workflow().id().value()),
                    evidence("recipient-redemption-accepted",
                            accepted.workflow().id().value()));
        }
        UsdzelleWorkflow child = workflow(retained.orElseThrow());
        verifyWorkflow(transfer, child, transfer.context().recipient(),
                UsdzelleWorkflow.Kind.REDEMPTION);
        return workflowResult(child, "recipient-redemption");
    }

    private Result reconciliation(SettlementTransfer transfer) {
        SettlementTransfer.Boundary acquisition = boundary(
                transfer, SettlementTransfer.BoundaryKind.SENDER_ACQUISITION);
        SettlementTransfer.Boundary wallet = boundary(
                transfer, SettlementTransfer.BoundaryKind.USER_TRANSFER);
        SettlementTransfer.Boundary redemption = boundary(
                transfer, SettlementTransfer.BoundaryKind.RECIPIENT_REDEMPTION);
        if (acquisition.status() != SettlementTransfer.BoundaryStatus.COMPLETED
                || wallet.status() != SettlementTransfer.BoundaryStatus.COMPLETED
                || redemption.status() != SettlementTransfer.BoundaryStatus.COMPLETED) {
            throw new IllegalStateException(
                    "settlement children are not complete for reconciliation");
        }
        UsdzelleWorkflow acquisitionChild = workflow(
                acquisition.child().orElseThrow());
        UsdzelleWorkflow redemptionChild = workflow(
                redemption.child().orElseThrow());
        WalletTransferOperation walletChild = walletTransfers.findById(
                        OperationId.from(wallet.child().orElseThrow().value()))
                .orElseThrow(() -> new IllegalStateException(
                        "settlement wallet child is unavailable"));
        verifyWorkflow(transfer, acquisitionChild, transfer.context().sender(),
                UsdzelleWorkflow.Kind.ACQUISITION);
        verifyWorkflow(transfer, redemptionChild, transfer.context().recipient(),
                UsdzelleWorkflow.Kind.REDEMPTION);
        verifyWalletTransfer(transfer, walletChild);
        if (acquisitionChild.status() != UsdzelleWorkflow.Status.COMPLETED
                || redemptionChild.status() != UsdzelleWorkflow.Status.COMPLETED
                || walletChild.status() != WalletTransferOperation.Status.COMPLETED
                || acquisitionChild.reconciliationConclusion().orElse(null)
                        != ReserveAccounting.ReconciliationStatus.RECONCILED
                || redemptionChild.reconciliationConclusion().orElse(null)
                        != ReserveAccounting.ReconciliationStatus.RECONCILED) {
            throw new IllegalStateException(
                    "settlement child truth is not reconciled");
        }
        ReserveAccounting.Reconciliation result = accounting.reconcile();
        return new Reconciled(
                result.status(),
                evidence("final-reconciliation", result.resultId().value()));
    }

    private Result workflowResult(UsdzelleWorkflow workflow, String category) {
        SettlementTransfer.ChildReference child = child(workflow.id().value());
        if (workflow.status() == UsdzelleWorkflow.Status.COMPLETED) {
            if (workflow.reconciliationConclusion().orElse(null)
                    != ReserveAccounting.ReconciliationStatus.RECONCILED) {
                return new ManualReview(evidence(
                        category + "-not-reconciled", workflow.id().value()));
            }
            return new Confirmed(
                    Optional.of(child), workflow.transitions().getLast().evidence());
        }
        if (workflow.status() == UsdzelleWorkflow.Status.MANUAL_REVIEW) {
            return new ManualReview(evidence(
                    category + "-manual-review", workflow.id().value()));
        }
        if (workflow.status() == UsdzelleWorkflow.Status.FAILED_NO_EFFECT) {
            return new RejectedNoEffect(evidence(
                    category + "-no-effect", workflow.id().value()));
        }
        if (workflow.status().name().contains("UNKNOWN")) {
            return new Unknown(
                    child, evidence(category + "-unknown", workflow.id().value()));
        }
        return new Pending(evidence(category + "-pending", workflow.id().value()));
    }

    private UsdzelleWorkflow workflow(SettlementTransfer.ChildReference reference) {
        return workflows.findById(new UsdzelleWorkflow.Id(
                        java.util.UUID.fromString(reference.value())))
                .orElseThrow(() -> new IllegalStateException(
                        "settlement workflow child is unavailable"));
    }

    private static void verifyWorkflow(
            SettlementTransfer transfer,
            UsdzelleWorkflow child,
            SettlementTransfer.RouteSnapshot route,
            UsdzelleWorkflow.Kind kind) {
        SettlementTransfer.AcceptedContext parent = transfer.context();
        UsdzelleWorkflow.AcceptedContext context = child.context();
        if (child.kind() != kind
                || !child.participant().tenantId().equals(
                        route.participant().tenantId())
                || !child.participant().participantId().equals(
                        route.participant().participantId())
                || !context.usdAmount().equals(parent.usdAmount())
                || !context.tokenQuantity().equals(parent.tokenQuantity())
                || !context.bankId().equals(route.bankId())
                || !context.bankAccountId().equals(route.bankAccountId())
                || !context.userWallet().equals(route.wallet())
                || !context.userWalletMetadataVersion().equals(
                        route.walletMetadataVersion())
                || !context.adminWallet().equals(parent.adminWallet())
                || !context.adminWalletMetadataVersion().equals(
                        parent.adminWalletMetadataVersion())
                || context.network() != parent.network()
                || !context.contractReference().equals(parent.contractReference())
                || !context.payoutPolicyVersion().equals(
                        parent.payoutPolicyVersion())
                || !context.conversionPolicyVersion().equals(
                        parent.conversionPolicyVersion())
                || !context.accountingPolicyVersion().equals(
                        parent.accountingPolicyVersion())
                || !context.feePolicyVersion().equals(parent.feePolicyVersion())
                || !context.finalityPolicyVersion().equals(
                        parent.finalityPolicyVersion())
                || !context.reconciliationPolicyVersion().equals(
                        parent.reconciliationPolicyVersion())) {
            throw new IllegalStateException(
                    "settlement workflow child conflicts with accepted context");
        }
    }

    private static void verifyWalletTransfer(
            SettlementTransfer transfer, WalletTransferOperation child) {
        SettlementTransfer.AcceptedContext context = transfer.context();
        String sourceVersion = child.source().registryVersion()
                + ':' + child.source().keyVersion();
        String destinationVersion = child.destination().registryVersion()
                + ':' + child.destination().keyVersion();
        ParticipantScope sender = participant(context.sender().participant());
        if (child.purpose() != WalletTransferOperation.Purpose.USER_TRANSFER
                || !child.participant().equals(sender)
                || !child.quantity().equals(context.tokenQuantity())
                || !child.source().reference().equals(context.sender().wallet())
                || !child.destination().reference().equals(context.recipient().wallet())
                || !sourceVersion.equals(context.sender().walletMetadataVersion())
                || !destinationVersion.equals(
                        context.recipient().walletMetadataVersion())
                || child.network() != context.network()
                || !child.contractAddress().equals(context.contractReference())) {
            throw new IllegalStateException(
                    "settlement wallet child conflicts with accepted context");
        }
    }

    private static UsdzelleWorkflowApplicationService.AcceptanceRequest workflowRequest(
            SettlementTransfer transfer, SettlementTransfer.RouteSnapshot route) {
        return new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                transfer.context().usdAmount().toCanonicalString(), "USD",
                route.bankAccountReference().value(),
                transfer.context().network().name());
    }

    private static ParticipantScope participant(
            SettlementTransfer.Participant participant) {
        return new ParticipantScope(
                participant.tenantId(), participant.participantId());
    }

    private static IdempotencyKey key(
            SettlementTransfer transfer, String boundary) {
        return new IdempotencyKey(
                "settlement:" + transfer.transferId().value() + ':' + boundary);
    }

    private static SettlementTransfer.Boundary boundary(
            SettlementTransfer transfer, SettlementTransfer.BoundaryKind kind) {
        return transfer.boundaries().stream()
                .filter(value -> value.kind() == kind)
                .findFirst().orElseThrow();
    }

    private static SettlementTransfer.ChildReference child(java.util.UUID value) {
        return new SettlementTransfer.ChildReference(value.toString());
    }

    private static EvidenceRef evidence(String category, Object value) {
        return new EvidenceRef(
                "internal:settlement-transfer:"
                        + category.toLowerCase(Locale.ROOT) + ':' + value);
    }
}
