package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.time.Instant;
import java.util.List;

import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationTransition;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;

/** Safe participant-facing representation of a durable operation aggregate. */
public record TokenOperationResponse(
        String operationId,
        String kind,
        AssetView asset,
        String quantity,
        String state,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String businessCorrelation,
        List<AttemptView> attempts,
        List<TransitionView> transitions,
        FinalityHistories finalities,
        List<String> evidenceReferences) {

    private static final String PARTICIPANT_SAFE_EVIDENCE_PREFIX = "participant:";

    public static TokenOperationResponse from(TokenOperation operation) {
        Instant updatedAt = operation.createdAt();
        for (OperationAttempt attempt : operation.attempts()) {
            if (attempt.createdAt().isAfter(updatedAt)) {
                updatedAt = attempt.createdAt();
            }
        }
        for (OperationTransition transition : operation.transitions()) {
            if (transition.occurredAt().isAfter(updatedAt)) {
                updatedAt = transition.occurredAt();
            }
        }
        for (FinalityType type : FinalityType.values()) {
            for (FinalityRecord finality : operation.finalityHistory(type)) {
                if (finality.updatedAt().isAfter(updatedAt)) {
                    updatedAt = finality.updatedAt();
                }
            }
        }

        var unit = operation.quantity().unit();
        return new TokenOperationResponse(
                operation.operationId().toString(),
                operation.kind().name(),
                new AssetView(unit.assetId(), unit.unitId(), unit.version(), unit.scale()),
                operation.quantity().toCanonicalString(),
                operation.state().name(),
                operation.version(),
                operation.createdAt(),
                updatedAt,
                operation.acceptanceContext().businessCorrelation(),
                operation.attempts().stream().map(AttemptView::from).toList(),
                operation.transitions().stream().map(TransitionView::from).toList(),
                new FinalityHistories(
                        history(operation, FinalityType.BLOCKCHAIN),
                        history(operation, FinalityType.LEGAL),
                        history(operation, FinalityType.CUSTOMER_VISIBLE),
                        history(operation, FinalityType.ACCOUNTING)),
                participantSafeEvidence(operation.evidenceReferences()));
    }

    private static List<FinalityView> history(
            TokenOperation operation, FinalityType type) {
        return operation.finalityHistory(type).stream().map(FinalityView::from).toList();
    }

    private static List<String> participantSafeEvidence(
            List<EvidenceRef> evidence) {
        return evidence.stream()
                .map(reference -> reference.value())
                .filter(reference -> reference.startsWith(PARTICIPANT_SAFE_EVIDENCE_PREFIX))
                .toList();
    }

    public record AssetView(String assetId, String unitId, int unitVersion, int scale) {
    }

    public record AttemptView(
            String attemptId,
            String predecessor,
            Instant createdAt) {

        private static AttemptView from(OperationAttempt attempt) {
            return new AttemptView(
                    attempt.attemptId().toString(),
                    attempt.predecessor().map(Object::toString).orElse(null),
                    attempt.createdAt());
        }
    }

    public record TransitionView(
            long version,
            String from,
            String to,
            Instant occurredAt,
            List<String> evidenceReferences) {

        private static TransitionView from(OperationTransition transition) {
            return new TransitionView(
                    transition.version(), transition.from().name(), transition.to().name(),
                    transition.occurredAt(),
                    participantSafeEvidence(transition.evidenceRefs()));
        }
    }

    public record FinalityHistories(
            List<FinalityView> blockchain,
            List<FinalityView> legal,
            List<FinalityView> customerVisible,
            List<FinalityView> accounting) {
    }

    public record FinalityView(
            String status,
            Instant updatedAt,
            List<String> evidenceReferences) {

        private static FinalityView from(FinalityRecord finality) {
            return new FinalityView(
                    finality.status().name(), finality.updatedAt(),
                    participantSafeEvidence(finality.evidenceRefs()));
        }
    }
}
