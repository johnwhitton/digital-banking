package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.time.Instant;
import java.util.List;

import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;

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
        List<String> evidenceReferences) {

    private static final String PARTICIPANT_SAFE_PREFIX = "participant:";

    public static TransferResponse from(Transfer transfer) {
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
                        .flatMap(value -> value.evidenceReferences().stream()).toList()));
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
}
