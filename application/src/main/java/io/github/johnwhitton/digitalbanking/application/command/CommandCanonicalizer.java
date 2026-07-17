package io.github.johnwhitton.digitalbanking.application.command;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;

/** Version 1 length-prefixed canonical command encoding. */
public final class CommandCanonicalizer {

    private static final int VERSION = 1;

    private CommandCanonicalizer() {
    }

    public static CanonicalCommand canonicalize(TokenOperationCommand command) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(VERSION);
                writeText(output, command.kind().name());
                writeText(output, command.participantScope().tenantId());
                writeText(output, command.participantScope().participantId());
                writeText(output, command.idempotencyScope().resource().name());
                AssetUnit unit = command.quantity().unit();
                writeText(output, unit.assetId());
                writeText(output, unit.unitId());
                writeText(output, Integer.toString(unit.version()));
                writeText(output, command.quantity().toCanonicalString());
                writeText(output, command.businessCorrelation());
                writeText(output, Integer.toString(command.contractVersion()));
            }
            byte[] canonical = buffer.toByteArray();
            return new CanonicalCommand(VERSION, canonical, new CommandDigest(sha256(canonical)));
        } catch (IOException exception) {
            throw new IllegalStateException("canonical command encoding failed", exception);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
