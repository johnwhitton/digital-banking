package io.github.johnwhitton.digitalbanking.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommand;
import io.github.johnwhitton.digitalbanking.application.command.CommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.TokenOperationCommand;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.EvidenceReferencePort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;

/** Framework-free command acceptance and lifecycle coordination. */
public final class TokenOperationService {

    private final OperationRepository operations;
    private final ClockPort clock;
    private final IdGenerator ids;
    private final EvidenceReferencePort evidence;

    public TokenOperationService(
            OperationRepository operations,
            ClockPort clock,
            IdGenerator ids,
            EvidenceReferencePort evidence) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    public OperationAcceptance accept(
            TokenOperationCommand command,
            IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        CanonicalCommand canonical = CommandCanonicalizer.canonicalize(command);
        return operations.accept(
                command.idempotencyScope(), idempotencyKey, canonical.metadata(),
                () -> TokenOperation.requested(
                        ids.nextOperationId(),
                        new OperationAcceptanceContext(
                                command.participantScope().tenantId(),
                                command.participantScope().participantId(),
                                command.idempotencyScope().resource().name(),
                                idempotencyKey.sha256(),
                                command.contractVersion(),
                                canonical.canonicalizationVersion(),
                                canonical.sha256(),
                                command.businessCorrelation()),
                        command.kind(), command.quantity(), now(),
                        evidence.registerAcceptance(canonical, command.participantScope())));
    }

    public TokenOperation transition(
            OperationId operationId,
            long expectedVersion,
            OperationState target,
            String actor,
            String reason,
            EvidenceRef evidenceReference) {
        TokenOperation current = operations.findById(operationId)
                .orElseThrow(() -> new IllegalArgumentException("operation was not found"));
        TokenOperation changed = current.transition(
                expectedVersion, target, actor, reason, now(), List.of(evidenceReference));
        operations.save(changed, expectedVersion);
        return changed;
    }

    private Instant now() {
        return Objects.requireNonNull(clock.now(), "clock result")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
