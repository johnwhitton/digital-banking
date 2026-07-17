package io.github.johnwhitton.digitalbanking.application.command;

import java.math.BigInteger;
import java.util.List;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandCanonicalizerTest {

    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");
    private static final AssetUnit UNIT = new AssetUnit(
            "USDC", "USD", 7, 6, new BigInteger("1000000000000"));

    @Test
    void producesStableVersionedGoldenDigest() {
        MintCommand command = new MintCommand(
                3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "corr-001");

        CanonicalCommand canonical = CommandCanonicalizer.canonicalize(command);

        assertEquals(1, canonical.canonicalizationVersion());
        assertEquals("64936676e367bfa9932e2be90a89f4c37052777b3fe186789e75d82a20574103",
                canonical.sha256());
        assertEquals(canonical, CommandCanonicalizer.canonicalize(command));
    }

    @Test
    void digestBindsEveryEffectRelevantFieldIndependentOfTransportOrdering() {
        TokenOperationCommand baseline = new MintCommand(
                3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "corr-001");
        String digest = CommandCanonicalizer.canonicalize(baseline).sha256();

        AssetUnit nextUnitVersion = new AssetUnit(
                "USDC", "USD", 8, 6, new BigInteger("1000000000000"));
        List<TokenOperationCommand> changed = List.of(
                new BurnCommand(3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "corr-001"),
                new MintCommand(3, new ParticipantScope("tenant-b", "participant-a"),
                        TokenQuantity.parse("12.34", UNIT), "corr-001"),
                new MintCommand(3, new ParticipantScope("tenant-a", "participant-b"),
                        TokenQuantity.parse("12.34", UNIT), "corr-001"),
                new MintCommand(3, PARTICIPANT,
                        TokenQuantity.parse("12.34", nextUnitVersion), "corr-001"),
                new MintCommand(3, PARTICIPANT, TokenQuantity.parse("12.35", UNIT), "corr-001"),
                new MintCommand(3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "corr-002"),
                new MintCommand(4, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "corr-001"));

        for (TokenOperationCommand command : changed) {
            assertNotEquals(digest, CommandCanonicalizer.canonicalize(command).sha256());
        }
    }

    @Test
    void normalizesBusinessCorrelationToUnicodeNfcBeforeHashing() {
        MintCommand composed = new MintCommand(
                3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "caf\u00e9-001");
        MintCommand decomposed = new MintCommand(
                3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "cafe\u0301-001");

        assertEquals("caf\u00e9-001", decomposed.businessCorrelation());
        assertEquals(
                CommandCanonicalizer.canonicalize(composed),
                CommandCanonicalizer.canonicalize(decomposed));
    }

    @Test
    void rejectsMalformedUnicodeInsteadOfReplacingItDuringUtf8Encoding() {
        assertThrows(IllegalArgumentException.class, () -> new MintCommand(
                3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "\uD800"));

        MintCommand questionMark = new MintCommand(
                3, PARTICIPANT, TokenQuantity.parse("12.34", UNIT), "?");
        assertEquals("?", questionMark.businessCorrelation());
    }

    @Test
    void idempotencyKeyNeverRendersItsRawValue() {
        IdempotencyKey key = IdempotencyKey.of("opaque-client-key-123");

        assertEquals("[REDACTED]", key.toString());
        assertNotEquals(key.value(), key.toString());
    }

    @Test
    void rejectsMalformedUnicodeIdempotencyKeysBeforeAuditHashing() {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.of("\uD800"));
        assertEquals("?", IdempotencyKey.of("?").value());
    }

    @Test
    void rejectsUnsafeParticipantIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new ParticipantScope("tenant a", "participant-a"));
    }
}
