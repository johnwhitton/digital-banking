package io.github.johnwhitton.digitalbanking.application.command;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;

/** Versioned canonical encoding for a synthetic bank balance command. */
public final class BankCommandCanonicalizer {

    private static final int VERSION = 1;

    private BankCommandCanonicalizer() {
    }

    public static CanonicalCommand encode(
            ParticipantScope participant,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId,
            BankOperation.Kind kind,
            UsdCents amount) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(VERSION);
                write(output, "SYNTHETIC_BANK_OPERATION");
                write(output, participant.tenantId());
                write(output, participant.participantId());
                write(output, bankId.value());
                write(output, accountId.value());
                write(output, kind.name());
                write(output, amount.toCanonicalString());
                write(output, "USD");
            }
            byte[] bytes = buffer.toByteArray();
            return new CanonicalCommand(
                    VERSION, bytes, new CommandDigest(sha256(bytes)));
        } catch (IOException failure) {
            throw new IllegalStateException("canonical bank encoding failed", failure);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
