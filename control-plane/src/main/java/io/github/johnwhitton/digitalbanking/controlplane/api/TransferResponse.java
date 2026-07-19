package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

/** Minimized participant-facing transfer projection; custody policy remains server-internal. */
public record TransferResponse(
        String transferId,
        String amount,
        String currency,
        String sourceBankAccountReference,
        String destinationBankAccountReference,
        String settlementNetwork,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<EffectView> effects,
        FinalityHistories finalities,
        List<String> evidenceReferences,
        SettlementOrchestration settlementOrchestration) {

    private static final String PARTICIPANT_SAFE_PREFIX = "participant:";

    public static TransferResponse from(Transfer transfer) {
        return from(transfer, Optional.empty());
    }

    public static TransferResponse from(
            Transfer transfer, Optional<SettlementTransfer> settlement) {
        var context = transfer.acceptanceContext();
        return new TransferResponse(
                transfer.transferId().toString(),
                transfer.quantity().toCanonicalString(), context.currency(),
                context.sourceBankAccount().value(), context.destinationBankAccount().value(),
                context.settlementNetwork().name(), transfer.status().name(),
                transfer.version(), transfer.createdAt(),
                transfer.transitions().getLast().occurredAt(),
                transfer.effects().stream().map(EffectView::from).toList(),
                new FinalityHistories(
                        finality(transfer, FinalityType.BLOCKCHAIN),
                        finality(transfer, FinalityType.LEGAL),
                        finality(transfer, FinalityType.CUSTOMER_VISIBLE),
                        finality(transfer, FinalityType.ACCOUNTING)),
                participantEvidence(transfer.transitions().stream()
                        .flatMap(value -> value.evidenceReferences().stream()).toList()),
                settlement.map(SettlementOrchestration::from).orElse(null));
    }

    private static List<FinalityView> finality(Transfer transfer, FinalityType type) {
        return transfer.finalityHistory(type).stream().map(FinalityView::from).toList();
    }

    private static List<String> participantEvidence(List<EvidenceRef> evidence) {
        return evidence.stream().map(EvidenceRef::value)
                .filter(value -> value.startsWith(PARTICIPANT_SAFE_PREFIX)).toList();
    }

    public record EffectView(
            String effectId,
            int sequence,
            String kind,
            String status,
            int attemptCount,
            List<String> evidenceReferences) {

        private static EffectView from(TransferEffect effect) {
            return new EffectView(
                    effect.effectId().value().toString(), effect.sequence(),
                    effect.kind().name(), effect.status().name(), effect.attempts().size(),
                    participantEvidence(effect.evidenceReferences()));
        }
    }

    public record FinalityHistories(
            List<FinalityView> blockchain,
            List<FinalityView> legal,
            List<FinalityView> customerVisible,
            List<FinalityView> accounting) { }

    public record FinalityView(
            String status,
            Instant updatedAt,
            List<String> evidenceReferences) {

        private static FinalityView from(FinalityRecord finality) {
            return new FinalityView(
                    finality.status().name(), finality.updatedAt(),
                    participantEvidence(finality.evidenceRefs()));
        }
    }

    public record SettlementOrchestration(
            String status,
            long version,
            String senderAcquisitionStatus,
            String userTransferStatus,
            String recipientRedemptionStatus,
            String bankStatus,
            String blockchainStatus,
            String accountingStatus,
            String reconciliationStatus,
            boolean manualReviewRequired) {

        private static SettlementOrchestration from(SettlementTransfer transfer) {
            return new SettlementOrchestration(
                    transfer.status().name(), transfer.version(),
                    boundary(transfer,
                            SettlementTransfer.BoundaryKind.SENDER_ACQUISITION),
                    boundary(transfer,
                            SettlementTransfer.BoundaryKind.USER_TRANSFER),
                    boundary(transfer,
                            SettlementTransfer.BoundaryKind.RECIPIENT_REDEMPTION),
                    dimension(transfer, completed(transfer,
                            SettlementTransfer.BoundaryKind.SENDER_ACQUISITION)
                            && completed(transfer,
                                    SettlementTransfer.BoundaryKind
                                            .RECIPIENT_REDEMPTION)),
                    dimension(transfer, completed(transfer,
                            SettlementTransfer.BoundaryKind.SENDER_ACQUISITION)
                            && completed(transfer,
                                    SettlementTransfer.BoundaryKind.USER_TRANSFER)
                            && completed(transfer,
                                    SettlementTransfer.BoundaryKind
                                            .RECIPIENT_REDEMPTION)),
                    dimension(transfer,
                            transfer.status() == SettlementTransfer.Status.COMPLETED),
                    transfer.conclusion().map(Enum::name).orElseGet(() -> boundary(
                            transfer,
                            SettlementTransfer.BoundaryKind.FINAL_RECONCILIATION)),
                    transfer.status() == SettlementTransfer.Status.MANUAL_REVIEW);
        }

        private static String boundary(
                SettlementTransfer transfer, SettlementTransfer.BoundaryKind kind) {
            return transfer.boundaries().stream()
                    .filter(value -> value.kind() == kind)
                    .findFirst().orElseThrow().status().name();
        }

        private static boolean completed(
                SettlementTransfer transfer, SettlementTransfer.BoundaryKind kind) {
            return SettlementTransfer.BoundaryStatus.COMPLETED.name()
                    .equals(boundary(transfer, kind));
        }

        private static String dimension(
                SettlementTransfer transfer, boolean completed) {
            if (completed) {
                return "COMPLETED";
            }
            return switch (transfer.status()) {
                case MANUAL_REVIEW -> "MANUAL_REVIEW";
                case FAILED_NO_EFFECT -> "FAILED_NO_EFFECT";
                default -> "PENDING";
            };
        }
    }
}
