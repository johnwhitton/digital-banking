package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;

/** Deterministic synthetic test/local adapter. It is intentionally not registered as a bean. */
public final class SyntheticMockBankAdapter implements MockBankPort {

    private final Mode mode;
    private final ConcurrentHashMap<String, Applied> applied = new ConcurrentHashMap<>();

    public SyntheticMockBankAdapter(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override
    public Outcome request(Command command) {
        Objects.requireNonNull(command, "command");
        return switch (mode) {
            case APPLIED -> {
                String evidence = "synthetic-bank-evidence:"
                        + command.transferId() + ":" + command.effectId().value();
                Applied existing = applied.putIfAbsent(
                        command.idempotencyIdentity(), new Applied(command, evidence));
                if (existing == null) {
                    yield Outcome.applied(evidence);
                }
                yield existing.command().equals(command)
                        ? Outcome.alreadyApplied(existing.evidence())
                        : Outcome.rejectedNoEffect("idempotency-conflict");
            }
            case REJECTED_NO_EFFECT -> Outcome.rejectedNoEffect("synthetic-rejected");
            case RETRYABLE_NO_EFFECT -> Outcome.retryableNoEffect("synthetic-unavailable");
            case AMBIGUOUS -> Outcome.ambiguous(
                    "synthetic-bank-evidence:" + command.idempotencyIdentity(),
                    "synthetic-ambiguous");
        };
    }

    public enum Mode {
        APPLIED,
        REJECTED_NO_EFFECT,
        RETRYABLE_NO_EFFECT,
        AMBIGUOUS
    }

    private record Applied(Command command, String evidence) { }
}
