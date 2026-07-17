package io.github.johnwhitton.digitalbanking.application;

import java.util.HashMap;
import java.util.Map;

import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;

/** Deterministic provider fixture. It is intentionally unavailable to production sources. */
final class SyntheticSigner implements SignerPort {

    private final Map<ProviderRequestId, String> commands = new HashMap<>();
    private Scenario scenario = Scenario.SIGNED;
    private Scenario inquiryScenario = Scenario.AMBIGUOUS;
    private int evmCalls;
    private int solanaCalls;
    private int inquiryCalls;

    void scenario(Scenario value) {
        scenario = value;
    }

    void inquiryScenario(Scenario value) {
        inquiryScenario = value;
    }

    int evmCalls() {
        return evmCalls;
    }

    int solanaCalls() {
        return solanaCalls;
    }

    int inquiryCalls() {
        return inquiryCalls;
    }

    @Override
    public ProviderResult signEvmDigest(EvmDigestCommand command) {
        evmCalls++;
        return respond(command.context(), scenario);
    }

    @Override
    public ProviderResult signSolanaMessage(SolanaMessageCommand command) {
        solanaCalls++;
        return respond(command.context(), scenario);
    }

    @Override
    public ProviderResult inquire(Inquiry command) {
        inquiryCalls++;
        return respond(command.context(), inquiryScenario);
    }

    private ProviderResult respond(ProviderContext context, Scenario selected) {
        String commandDigest = context.request().requestDigest();
        String previous = commands.putIfAbsent(context.providerRequestId(), commandDigest);
        if (previous != null && !previous.equals(commandDigest)) {
            return new Conflict(
                    "provider-identity-conflict",
                    new EvidenceRef("synthetic:provider:conflict"));
        }
        return switch (selected) {
            case SIGNED -> new Signed(
                    new byte[] {83, 89, 78, 84, 72, 69, 84, 73, 67},
                    "SYNTHETIC_SIGNATURE_V1",
                    new EvidenceRef("synthetic:provider:signed"),
                    SigningRequest.EvidenceOrigin.SYNTHETIC_TEST);
            case DENIED -> new Denied(
                    "synthetic-denied", new EvidenceRef("synthetic:provider:denied"));
            case RETRYABLE_NO_SIGNATURE -> new RetryableNoSignature(
                    "synthetic-retryable-no-signature",
                    new EvidenceRef("synthetic:provider:no-signature"));
            case AMBIGUOUS -> new Ambiguous(
                    new EvidenceRef("synthetic:provider:ambiguous"));
            case INFRASTRUCTURE_FAILURE ->
                    throw new IllegalStateException(
                            "synthetic provider infrastructure failure");
        };
    }

    enum Scenario {
        SIGNED,
        DENIED,
        RETRYABLE_NO_SIGNATURE,
        AMBIGUOUS,
        INFRASTRUCTURE_FAILURE
    }
}
