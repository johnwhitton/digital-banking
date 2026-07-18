package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleChainEvidencePort;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowContextResolver;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Local POC bridge from one durable parent step to existing child authorities. */
public final class LocalUsdzelleWorkflowStepExecutor
        implements UsdzelleWorkflowStepExecutor {

    private final MockBankApplicationService banks;
    private final AccountingApplicationService accounting;
    private final TokenOperationApplicationService tokens;
    private final OperationRepository operations;
    private final WalletTransferAcceptanceService walletAcceptance;
    private final WalletTransferRepository walletTransfers;
    private final UsdzelleChainEvidencePort chainEvidence;
    private final UsdzelleWorkflowContextResolver contextResolver;

    public LocalUsdzelleWorkflowStepExecutor(
            MockBankApplicationService banks,
            AccountingApplicationService accounting,
            TokenOperationApplicationService tokens,
            OperationRepository operations,
            WalletTransferAcceptanceService walletAcceptance,
            WalletTransferRepository walletTransfers,
            UsdzelleChainEvidencePort chainEvidence,
            UsdzelleWorkflowContextResolver contextResolver) {
        this.banks = Objects.requireNonNull(banks, "banks");
        this.accounting = Objects.requireNonNull(accounting, "accounting");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.walletAcceptance = Objects.requireNonNull(
                walletAcceptance, "walletAcceptance");
        this.walletTransfers = Objects.requireNonNull(walletTransfers, "walletTransfers");
        this.chainEvidence = Objects.requireNonNull(chainEvidence, "chainEvidence");
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
    }

    @Override
    public Result execute(UsdzelleWorkflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        contextResolver.verifyAccepted(workflow);
        return switch (workflow.currentStep().kind()) {
            case WITHDRAWAL -> bank(workflow, BankOperation.Kind.WITHDRAWAL);
            case RESERVE_FUNDING_POST -> bankAccounting(
                    workflow, ReserveAccounting.PostingType.RESERVE_FUNDING);
            case MINT -> token(workflow, OperationKind.MINT);
            case MINT_ACCOUNTING_POST -> chainAccounting(
                    workflow, UsdzelleChainEvidencePort.Effect.MINT,
                    ReserveAccounting.PostingType.MINT_CONFIRMED);
            case CUSTODY_TRANSFER -> custody(workflow);
            case CUSTODY_ACCOUNTING_POST -> chainAccounting(
                    workflow, UsdzelleChainEvidencePort.Effect.REDEMPTION_CUSTODY,
                    ReserveAccounting.PostingType.REDEMPTION_CUSTODY_CONFIRMED);
            case PAYOUT -> bank(workflow, BankOperation.Kind.DEPOSIT);
            case PAYOUT_ACCOUNTING_POST -> bankAccounting(
                    workflow, ReserveAccounting.PostingType.BANK_PAYOUT_CONFIRMED);
            case BURN -> token(workflow, OperationKind.BURN);
            case RECONCILIATION -> reconciliation();
        };
    }

    private Result bank(UsdzelleWorkflow workflow, BankOperation.Kind kind) {
        ParticipantScope participant = participant(workflow);
        Optional<UsdzelleWorkflow.ChildReference> retained =
                workflow.currentStep().childReference();
        if (retained.isPresent()) {
            BankOperation operation = banks.findOperation(
                    participant, new BankOperation.Id(
                            java.util.UUID.fromString(retained.orElseThrow().value())));
            return bankResult(operation);
        }
        MockBankPort.BankResponse response = banks.execute(
                participant, workflow.context().bankId(),
                workflow.context().bankAccountId(), kind,
                new MockBankApplicationService.Request(
                        workflow.context().usdAmount().toCanonicalString(), "USD"),
                key(workflow, kind.name().toLowerCase(java.util.Locale.ROOT)));
        UsdzelleWorkflow.ChildReference child = child(response.operationId().value());
        return switch (response.status()) {
            case SUCCEEDED -> new Confirmed(
                    Optional.of(child), evidence("bank", response.evidenceId().value()));
            case REJECTED -> new RejectedNoEffect(
                    evidence("bank-rejected", response.evidenceId().value()));
            case UNKNOWN -> new Unknown(
                    child, evidence("bank-unknown", response.operationId().value()));
        };
    }

    private Result bankResult(BankOperation operation) {
        return switch (operation.status()) {
            case SUCCEEDED -> new Confirmed(
                    Optional.of(child(operation.id().value())),
                    evidence("bank", operation.evidenceId().value()));
            case REJECTED -> new RejectedNoEffect(
                    evidence("bank-rejected", operation.evidenceId().value()));
        };
    }

    private Result bankAccounting(
            UsdzelleWorkflow workflow, ReserveAccounting.PostingType posting) {
        UsdzelleWorkflow.Step source = previous(workflow);
        BankOperation operation = banks.findOperation(
                participant(workflow), new BankOperation.Id(java.util.UUID.fromString(
                        source.childReference().orElseThrow().value())));
        var result = accounting.post(
                new ReserveAccounting.EvidenceIdentity(
                        operation.evidenceId().value().toString()), posting);
        return new Confirmed(Optional.empty(),
                evidence("accounting", result.journalId().value()));
    }

    private Result token(UsdzelleWorkflow workflow, OperationKind kind) {
        Optional<UsdzelleWorkflow.ChildReference> retained =
                workflow.currentStep().childReference();
        if (retained.isEmpty()) {
            OperationAcceptance accepted = tokens.accept(
                    kind, participant(workflow),
                    new TokenOperationApplicationService.AcceptanceRequest(
                            1, workflow.context().tokenQuantity().unit().assetId(),
                            workflow.context().tokenQuantity().unit().unitId(),
                            workflow.context().tokenQuantity().unit().version(),
                            workflow.context().tokenQuantity().toCanonicalString(),
                            correlation(workflow, kind.name().toLowerCase(
                                    java.util.Locale.ROOT))),
                    key(workflow, kind.name().toLowerCase(java.util.Locale.ROOT)));
            if (kind == OperationKind.BURN) {
                OperationId custody = OperationId.from(workflow.steps().stream()
                        .filter(step -> step.kind()
                                == UsdzelleWorkflow.StepKind.CUSTODY_TRANSFER)
                        .findFirst().orElseThrow()
                        .childReference().orElseThrow().value());
                chainEvidence.bindBurn(
                        workflow, custody, accepted.operation().operationId());
            }
            return tokenResult(workflow, kind, accepted.operation(), true);
        }
        var operation = operations.findById(
                        OperationId.from(retained.orElseThrow().value()))
                .orElseThrow(() -> new IllegalStateException(
                        "workflow token child is unavailable"));
        return tokenResult(workflow, kind, operation, false);
    }

    private Result tokenResult(
            UsdzelleWorkflow workflow,
            OperationKind expectedKind,
            io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation operation,
            boolean justAccepted) {
        if (operation.kind() != expectedKind) {
            return new ManualReview(evidence(
                    "token-kind-conflict", operation.operationId().value()));
        }
        if (justAccepted) {
            return new Dispatched(
                    child(operation.operationId().value()),
                    evidence("token-accepted", operation.operationId().value()));
        }
        if (operation.state() == OperationState.COMPLETED) {
            if (expectedKind == OperationKind.BURN) {
                ReserveAccounting.EvidenceIdentity confirmed = chainEvidence.register(
                        UsdzelleChainEvidencePort.Effect.BURN,
                        workflow, operation.operationId());
                accounting.post(confirmed, ReserveAccounting.PostingType.BURN_CONFIRMED);
                return new Confirmed(
                        Optional.of(child(operation.operationId().value())),
                        new EvidenceRef(confirmed.value()));
            }
            return new Confirmed(
                    Optional.of(child(operation.operationId().value())),
                    operation.evidenceReferences().getLast());
        }
        if (operation.state() == OperationState.MANUAL_REVIEW
                || operation.state() == OperationState.REJECTED) {
            return new ManualReview(evidence(
                    "token-unsafe", operation.operationId().value()));
        }
        if (operation.state() == OperationState.FAILED_NO_EFFECT) {
            return new RejectedNoEffect(evidence(
                    "token-no-effect", operation.operationId().value()));
        }
        return new Pending(evidence("token-pending", operation.operationId().value()));
    }

    private Result custody(UsdzelleWorkflow workflow) {
        Optional<UsdzelleWorkflow.ChildReference> retained =
                workflow.currentStep().childReference();
        WalletTransferOperation operation;
        boolean accepted = retained.isEmpty();
        if (accepted) {
            operation = walletAcceptance.acceptRedemptionCustody(
                    participant(workflow), key(workflow, "custody"),
                    new WalletTransferAcceptanceService.Request(
                            workflow.context().tokenQuantity().toCanonicalString(),
                            workflow.context().tokenQuantity().unit().assetId(),
                            workflow.context().tokenQuantity().unit().unitId(),
                            workflow.context().tokenQuantity().unit().version(),
                            workflow.context().userWallet(),
                            workflow.context().adminWallet())).operation();
        } else {
            operation = walletTransfers.findById(
                            OperationId.from(retained.orElseThrow().value()))
                    .orElseThrow(() -> new IllegalStateException(
                            "workflow custody child is unavailable"));
        }
        if (accepted) {
            return new Dispatched(
                    child(operation.operationId().value()),
                    evidence("custody-accepted", operation.operationId().value()));
        }
        return switch (operation.status()) {
            case COMPLETED -> {
                yield new Confirmed(
                        Optional.of(child(operation.operationId().value())),
                        operation.evidence().getLast());
            }
            case MANUAL_REVIEW -> new ManualReview(
                    evidence("custody-unsafe", operation.operationId().value()));
            case FAILED_NO_EFFECT -> new RejectedNoEffect(
                    evidence("custody-no-effect", operation.operationId().value()));
            default -> new Pending(
                    evidence("custody-pending", operation.operationId().value()));
        };
    }

    private Result chainAccounting(
            UsdzelleWorkflow workflow,
            UsdzelleChainEvidencePort.Effect effect,
            ReserveAccounting.PostingType posting) {
        OperationId child = OperationId.from(
                previous(workflow).childReference().orElseThrow().value());
        ReserveAccounting.EvidenceIdentity evidence =
                chainEvidence.register(effect, workflow, child);
        var result = accounting.post(evidence, posting);
        return new Confirmed(Optional.empty(),
                evidence("accounting", result.journalId().value()));
    }

    private Result reconciliation() {
        ReserveAccounting.Reconciliation reconciliation = accounting.reconcile();
        return new Reconciled(
                reconciliation.status(),
                evidence("reconciliation", reconciliation.resultId().value()));
    }

    private static ParticipantScope participant(UsdzelleWorkflow workflow) {
        return new ParticipantScope(
                workflow.participant().tenantId(), workflow.participant().participantId());
    }

    private static UsdzelleWorkflow.Step previous(UsdzelleWorkflow workflow) {
        return workflow.steps().get(workflow.currentStep().sequence() - 2);
    }

    private static IdempotencyKey key(UsdzelleWorkflow workflow, String step) {
        return new IdempotencyKey("usdzelle:" + workflow.id().value() + ":" + step);
    }

    private static String correlation(UsdzelleWorkflow workflow, String step) {
        return "usdzelle:" + workflow.id().value() + ":" + step;
    }

    private static UsdzelleWorkflow.ChildReference child(java.util.UUID value) {
        return new UsdzelleWorkflow.ChildReference(value.toString());
    }

    private static EvidenceRef evidence(String category, java.util.UUID value) {
        return new EvidenceRef("internal:usdzelle:" + category + ":" + value);
    }
}
