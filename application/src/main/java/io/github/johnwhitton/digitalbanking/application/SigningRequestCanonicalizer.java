package io.github.johnwhitton.digitalbanking.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;

/** Version 1 length-prefixed canonical signing-intent and resolved-key encoding. */
final class SigningRequestCanonicalizer {

    static final int VERSION = 1;

    private SigningRequestCanonicalizer() {
    }

    static String intent(
            SigningAuthorityService.Request request,
            SigningRequest.PayloadIdentity payload) {
        Encoder encoder = new Encoder("SIGNING_INTENT");
        encoder.text(request.requestId().value().toString());
        encoder.text(request.correlation().operationId().value().toString());
        encoder.text(request.correlation().operationAttemptId().value().toString());
        encoder.text(request.correlation().transferId()
                .map(value -> value.value().toString()).orElse("NONE"));
        encoder.text(request.correlation().effectId()
                .map(value -> value.value().toString()).orElse("NONE"));
        encoder.text(request.lineage()
                .map(value -> value.requestId().value().toString()).orElse("NONE"));
        encoder.text(request.lineage()
                .map(value -> value.attemptId().value().toString()).orElse("NONE"));
        encoder.text(request.lineage()
                .map(value -> value.authorizationEvidence().value()).orElse("NONE"));
        encoder.text(request.action().name());
        encoder.text(request.network().name());
        encoder.text(request.quantity().unit().assetId());
        encoder.text(request.quantity().unit().unitId());
        encoder.text(Integer.toString(request.quantity().unit().version()));
        encoder.text(Integer.toString(request.quantity().unit().scale()));
        encoder.text(request.quantity().unit().maxAtomicUnits().toString());
        encoder.text(request.quantity().toCanonicalString());
        encoder.text(request.sourceReference());
        encoder.text(request.destinationReference());
        encoder.text(request.nativeActionIdentity());
        encoder.text(request.lifetimeContextDigest());
        encoder.text(request.feeLimit());
        encoder.text(request.nativeConstraintDigest());
        encoder.text(request.keyAlias().value());
        encoder.text(request.keyRole().name());
        encoder.text(request.mode().name());
        encoder.text(request.algorithm().name());
        encoder.text(payload.sha256());
        encoder.text(Integer.toString(payload.length()));
        encoder.text(payload.encoding().name());
        encoder.text(request.policyVersion());
        encoder.text(Integer.toString(request.approvalEvidence().size()));
        for (EvidenceRef evidence : request.approvalEvidence()) {
            encoder.text(evidence.value());
        }
        encoder.text(request.issuedAt().toString());
        encoder.text(request.expiresAt().toString());
        return encoder.digest();
    }

    static String resolved(String intentDigest, SigningRequest.KeyContext key) {
        Encoder encoder = new Encoder("SIGNING_REQUEST_RESOLVED");
        encoder.text(intentDigest);
        encoder.text(key.alias().value());
        encoder.text(key.registryVersion());
        encoder.text(key.keyVersion().orElse("NONE"));
        encoder.text(key.role().name());
        encoder.text(key.algorithm().name());
        encoder.text(key.network().name());
        encoder.text(key.status().name());
        key.allowedRoles().stream().sorted(Comparator.comparing(Enum::name))
                .forEach(value -> encoder.text(value.name()));
        key.allowedAlgorithms().stream().sorted(Comparator.comparing(Enum::name))
                .forEach(value -> encoder.text(value.name()));
        key.allowedNetworks().stream().sorted(Comparator.comparing(Enum::name))
                .forEach(value -> encoder.text(value.name()));
        encoder.text(key.validFrom().toString());
        encoder.text(key.expiresAt().map(Object::toString).orElse("NONE"));
        return encoder.digest();
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static final class Encoder {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream output = new DataOutputStream(buffer);

        private Encoder(String kind) {
            try {
                output.writeInt(VERSION);
                text(kind);
            } catch (IOException failure) {
                throw new IllegalStateException("canonical signing encoding failed", failure);
            }
        }

        private void text(String value) {
            try {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                output.writeInt(bytes.length);
                output.write(bytes);
            } catch (IOException failure) {
                throw new IllegalStateException("canonical signing encoding failed", failure);
            }
        }

        private String digest() {
            try {
                output.flush();
                return sha256(buffer.toByteArray());
            } catch (IOException failure) {
                throw new IllegalStateException("canonical signing encoding failed", failure);
            }
        }
    }
}
