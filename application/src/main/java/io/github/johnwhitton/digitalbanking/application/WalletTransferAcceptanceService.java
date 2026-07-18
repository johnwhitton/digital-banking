package io.github.johnwhitton.digitalbanking.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Internal-only acceptance boundary for a server-selected wallet transfer. */
public final class WalletTransferAcceptanceService {

    private static final int COMMAND_VERSION = 1;
    private static final Pattern EVM_ADDRESS = Pattern.compile("0x[0-9a-f]{40}");

    private final WalletTransferRepository transfers;
    private final AssetUnitCatalog assets;
    private final WalletIdentityRegistry wallets;
    private final ClockPort clock;
    private final IdGenerator operationIds;
    private final TransferIdentityGenerator transferIds;
    private final Policy policy;

    public WalletTransferAcceptanceService(
            WalletTransferRepository transfers,
            AssetUnitCatalog assets,
            WalletIdentityRegistry wallets,
            ClockPort clock,
            IdGenerator operationIds,
            TransferIdentityGenerator transferIds,
            Policy policy) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.operationIds = Objects.requireNonNull(operationIds, "operationIds");
        this.transferIds = Objects.requireNonNull(transferIds, "transferIds");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public WalletTransferRepository.Acceptance accept(
            ParticipantScope participant, IdempotencyKey key, Request request) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");
        try {
            String digest = canonicalDigest(participant, request);
            var retained = transfers.findByIdempotency(participant, key.sha256());
            if (retained.isPresent()) {
                WalletTransferOperation existing = retained.orElseThrow();
                if (existing.commandVersion() != COMMAND_VERSION
                        || !existing.commandDigest().equals(digest)) {
                    throw new IdempotencyConflictException();
                }
                return new WalletTransferRepository.Acceptance(existing, true);
            }
            var unit = assets.find(request.assetId(), request.unitId(), request.unitVersion())
                    .orElseThrow(UnknownAssetUnitException::new);
            TokenQuantity quantity = TokenQuantity.parse(request.amount(), unit);
            WalletIdentityRegistry.WalletIdentity source = wallets.resolve(request.source());
            WalletIdentityRegistry.WalletIdentity destination =
                    wallets.resolve(request.destination());
            validateResolution(request.source(), source);
            validateResolution(request.destination(), destination);
            if (source.reference().equals(destination.reference())
                    || source.normalizedAddress().equals(destination.normalizedAddress())) {
                throw new IllegalArgumentException("source and destination wallets must differ");
            }
            var operationId = operationIds.nextOperationId();
            var acceptedAt = clock.now().truncatedTo(ChronoUnit.MICROS);
            WalletTransferOperation proposed = new WalletTransferOperation(
                    operationId, transferIds.nextTransferId(), transferIds.nextEffectId(),
                    participant, key.sha256(), COMMAND_VERSION, digest, quantity,
                    WalletTransferOperation.WalletSnapshot.from(source),
                    WalletTransferOperation.WalletSnapshot.from(destination),
                    SettlementNetwork.ETHEREUM, policy.contractAddress(),
                    policy.contractVersion(), policy.finalityPolicyVersion(),
                    operationIds.nextAttemptId(), WalletTransferOperation.Status.ACCEPTED,
                    0, acceptedAt, acceptedAt,
                    java.util.List.of(new EvidenceRef(
                            "internal:wallet-transfer:accepted:" + operationId)),
                    WalletTransferOperation.initialFinalities());
            return transfers.accept(proposed);
        } catch (UnknownAssetUnitException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static void validateResolution(
            WalletReference requested, WalletIdentityRegistry.WalletIdentity resolved) {
        if (!resolved.reference().equals(requested)
                || resolved.ownerCategory()
                        != WalletIdentityRegistry.OwnerCategory.USER_CUSTODY
                || resolved.network() != SettlementNetwork.ETHEREUM
                || resolved.status() != WalletIdentityRegistry.Status.ENABLED
                || !resolved.allowedPurposes().contains(
                        WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER)
                || !EVM_ADDRESS.matcher(resolved.normalizedAddress()).matches()) {
            throw new IllegalArgumentException(
                    "wallet registry returned an unauthorized transfer identity");
        }
    }

    private static String canonicalDigest(
            ParticipantScope participant,
            Request request) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(COMMAND_VERSION);
                write(output, "WALLET_TRANSFER");
                write(output, participant.tenantId());
                write(output, participant.participantId());
                write(output, request.amount());
                write(output, request.assetId());
                write(output, request.unitId());
                write(output, Integer.toString(request.unitVersion()));
                write(output, request.source().value());
                write(output, request.destination().value());
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException failure) {
            throw new IllegalStateException("wallet transfer canonicalization failed", failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    public record Request(
            String amount,
            String assetId,
            String unitId,
            int unitVersion,
            WalletReference source,
            WalletReference destination) {

        public Request {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
        }
    }

    public record Policy(
            String contractAddress,
            String contractVersion,
            String finalityPolicyVersion) {

        public Policy {
            requireText(contractAddress, "contractAddress");
            requireText(contractVersion, "contractVersion");
            requireText(finalityPolicyVersion, "finalityPolicyVersion");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
            }
        }
    }
}
