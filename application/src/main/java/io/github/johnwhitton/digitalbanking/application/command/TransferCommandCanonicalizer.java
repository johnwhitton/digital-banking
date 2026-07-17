package io.github.johnwhitton.digitalbanking.application.command;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.port.TransferRouteCatalog;
import io.github.johnwhitton.digitalbanking.application.port.WalletRoleResolver;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;

/** Versioned length-prefixed transfer request and resolved-context encoding. */
public final class TransferCommandCanonicalizer {

    private static final int VERSION = 1;

    private TransferCommandCanonicalizer() {
    }

    public static CanonicalCommand request(
            ParticipantScope participant,
            String amount,
            String currency,
            BankAccountReference source,
            BankAccountReference destination,
            Optional<SettlementNetwork> requestedNetwork) {
        return encode(
                "TRANSFER_REQUEST", participant.tenantId(), participant.participantId(),
                amount, currency, source.value(), destination.value(),
                requestedNetwork.map(Enum::name).orElse("DEFAULT"));
    }

    public static CanonicalCommand resolved(
            ParticipantScope participant,
            String amount,
            BankAccountReference source,
            BankAccountReference destination,
            TransferRouteCatalog.Route route,
            WalletRoleResolver.Resolution wallets) {
        return encode(
                "TRANSFER_RESOLVED", participant.tenantId(), participant.participantId(),
                amount, route.currency(), source.value(), destination.value(),
                route.settlementNetwork().name(), route.assetUnit().assetId(),
                route.assetUnit().unitId(), Integer.toString(route.assetUnit().version()),
                Integer.toString(route.assetUnit().scale()), route.routeVersion(),
                wallets.senderWallet().value(), wallets.recipientWallet().value(),
                wallets.policyVersion());
    }

    private static CanonicalCommand encode(String... values) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(VERSION);
                for (String value : values) {
                    writeText(output, value);
                }
            }
            byte[] bytes = buffer.toByteArray();
            return new CanonicalCommand(
                    VERSION, bytes, new CommandDigest(sha256(bytes)));
        } catch (IOException failure) {
            throw new IllegalStateException("canonical transfer encoding failed", failure);
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
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
