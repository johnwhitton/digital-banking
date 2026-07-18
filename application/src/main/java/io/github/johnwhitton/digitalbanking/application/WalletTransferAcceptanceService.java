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
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
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
            String digest = canonicalDigest(participant, request, "WALLET_TRANSFER");
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
                    WalletTransferOperation.Purpose.USER_TRANSFER,
                    WalletTransferOperation.WalletSnapshot.from(source, request.source()),
                    WalletTransferOperation.WalletSnapshot.from(
                            destination, request.destination()),
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

    public WalletTransferRepository.Acceptance acceptRedemption(
            TokenOperation burn,
            WalletReference sourceReference,
            WalletReference adminRedemptionReference) {
        Objects.requireNonNull(burn, "burn");
        Objects.requireNonNull(sourceReference, "sourceReference");
        Objects.requireNonNull(adminRedemptionReference, "adminRedemptionReference");
        if (burn.kind() != OperationKind.BURN) {
            throw new InvalidRequestException(
                    new IllegalArgumentException("redemption requires an accepted burn"));
        }
        try {
            ParticipantScope participant = new ParticipantScope(
                    burn.acceptanceContext().tenantId(),
                    burn.acceptanceContext().participantId());
            IdempotencyKey key = new IdempotencyKey(
                    "redemption:" + burn.operationId().value());
            String digest = redemptionDigest(
                    burn, sourceReference, adminRedemptionReference);
            var retained = transfers.findByIdempotency(participant, key.sha256());
            if (retained.isPresent()) {
                WalletTransferOperation existing = retained.orElseThrow();
                if (existing.purpose()
                                != WalletTransferOperation.Purpose.REDEMPTION_CUSTODY
                        || existing.commandVersion() != COMMAND_VERSION
                        || !existing.commandDigest().equals(digest)) {
                    throw new IdempotencyConflictException();
                }
                return new WalletTransferRepository.Acceptance(existing, true);
            }
            WalletIdentityRegistry.WalletIdentity source =
                    wallets.resolve(sourceReference);
            WalletIdentityRegistry.WalletIdentity destination =
                    wallets.resolve(adminRedemptionReference);
            validateResolution(sourceReference, source);
            validateRedemptionDestination(
                    adminRedemptionReference, destination);
            if (source.normalizedAddress().equals(destination.normalizedAddress())) {
                throw new IllegalArgumentException(
                        "redemption source and ADMIN destination must differ");
            }
            var operationId = operationIds.nextOperationId();
            var acceptedAt = clock.now().truncatedTo(ChronoUnit.MICROS);
            WalletTransferOperation proposed = new WalletTransferOperation(
                    operationId, transferIds.nextTransferId(), transferIds.nextEffectId(),
                    participant, key.sha256(), COMMAND_VERSION, digest, burn.quantity(),
                    WalletTransferOperation.Purpose.REDEMPTION_CUSTODY,
                    WalletTransferOperation.WalletSnapshot.from(source, sourceReference),
                    WalletTransferOperation.WalletSnapshot.from(
                            destination, adminRedemptionReference),
                    SettlementNetwork.ETHEREUM, policy.contractAddress(),
                    policy.contractVersion(), policy.finalityPolicyVersion(),
                    operationIds.nextAttemptId(), WalletTransferOperation.Status.ACCEPTED,
                    0, acceptedAt, acceptedAt,
                    java.util.List.of(new EvidenceRef(
                            "internal:redemption-custody:accepted:" + operationId)),
                    WalletTransferOperation.initialFinalities());
            return transfers.acceptRedemption(proposed, burn.operationId());
        } catch (IdempotencyConflictException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    public WalletTransferRepository.Acceptance acceptRedemptionCustody(
            ParticipantScope participant, IdempotencyKey key, Request request) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");
        try {
            String digest = canonicalDigest(
                    participant, request, "REDEMPTION_CUSTODY");
            var retained = transfers.findByIdempotency(participant, key.sha256());
            if (retained.isPresent()) {
                WalletTransferOperation existing = retained.orElseThrow();
                if (existing.purpose()
                                != WalletTransferOperation.Purpose.REDEMPTION_CUSTODY
                        || existing.commandVersion() != COMMAND_VERSION
                        || !existing.commandDigest().equals(digest)) {
                    throw new IdempotencyConflictException();
                }
                return new WalletTransferRepository.Acceptance(existing, true);
            }
            var unit = assets.find(
                            request.assetId(), request.unitId(), request.unitVersion())
                    .orElseThrow(UnknownAssetUnitException::new);
            TokenQuantity quantity = TokenQuantity.parse(request.amount(), unit);
            WalletIdentityRegistry.WalletIdentity source = wallets.resolve(request.source());
            WalletIdentityRegistry.WalletIdentity destination =
                    wallets.resolve(request.destination());
            validateResolution(request.source(), source);
            validateRedemptionDestination(request.destination(), destination);
            if (source.normalizedAddress().equals(destination.normalizedAddress())) {
                throw new IllegalArgumentException(
                        "redemption source and ADMIN destination must differ");
            }
            var operationId = operationIds.nextOperationId();
            var acceptedAt = clock.now().truncatedTo(ChronoUnit.MICROS);
            WalletTransferOperation proposed = new WalletTransferOperation(
                    operationId, transferIds.nextTransferId(), transferIds.nextEffectId(),
                    participant, key.sha256(), COMMAND_VERSION, digest, quantity,
                    WalletTransferOperation.Purpose.REDEMPTION_CUSTODY,
                    WalletTransferOperation.WalletSnapshot.from(source, request.source()),
                    WalletTransferOperation.WalletSnapshot.from(
                            destination, request.destination()),
                    SettlementNetwork.ETHEREUM, policy.contractAddress(),
                    policy.contractVersion(), policy.finalityPolicyVersion(),
                    operationIds.nextAttemptId(), WalletTransferOperation.Status.ACCEPTED,
                    0, acceptedAt, acceptedAt,
                    java.util.List.of(new EvidenceRef(
                            "internal:workflow-redemption-custody:accepted:" + operationId)),
                    WalletTransferOperation.initialFinalities());
            return transfers.acceptRedemptionCustody(proposed);
        } catch (IdempotencyConflictException | UnknownAssetUnitException failure) {
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

    private static void validateRedemptionDestination(
            WalletReference requested,
            WalletIdentityRegistry.WalletIdentity resolved) {
        if ((!resolved.reference().equals(requested)
                        && !resolved.aliases().contains(requested))
                || resolved.ownerCategory() != WalletIdentityRegistry.OwnerCategory.ADMIN
                || resolved.network() != SettlementNetwork.ETHEREUM
                || resolved.status() != WalletIdentityRegistry.Status.ENABLED
                || !resolved.allowedPurposes().contains(
                        WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY)
                || !EVM_ADDRESS.matcher(resolved.normalizedAddress()).matches()) {
            throw new IllegalArgumentException(
                    "wallet registry returned an unauthorized redemption identity");
        }
    }

    private static String canonicalDigest(
            ParticipantScope participant,
            Request request,
            String purpose) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(COMMAND_VERSION);
                write(output, purpose);
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

    private static String redemptionDigest(
            TokenOperation burn,
            WalletReference source,
            WalletReference destination) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(COMMAND_VERSION);
                write(output, "REDEMPTION_CUSTODY");
                write(output, burn.operationId().toString());
                write(output, burn.acceptanceContext().commandDigest());
                write(output, burn.quantity().unit().assetId());
                write(output, burn.quantity().unit().unitId());
                write(output, Integer.toString(burn.quantity().unit().version()));
                write(output, burn.quantity().atomicUnits().toString());
                write(output, source.value());
                write(output, destination.value());
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "redemption custody canonicalization failed", failure);
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
