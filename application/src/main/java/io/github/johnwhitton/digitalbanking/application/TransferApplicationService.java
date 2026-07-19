package io.github.johnwhitton.digitalbanking.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommand;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.command.TransferCommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionResolver;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.application.port.TransferRouteCatalog;
import io.github.johnwhitton.digitalbanking.application.port.WalletRoleResolver;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferParticipant;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

/** Transport-neutral transfer acceptance and participant-scoped read use cases. */
public final class TransferApplicationService {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z][A-Z0-9]{2,11}");

    private final TransferRepository transfers;
    private final TransferRouteCatalog routes;
    private final WalletRoleResolver wallets;
    private final ClockPort clock;
    private final TransferIdentityGenerator ids;
    private final SettlementInstructionResolver settlementInstructions;
    private final SettlementTransferIdentityGenerator settlementIds;

    public TransferApplicationService(
            TransferRepository transfers,
            TransferRouteCatalog routes,
            WalletRoleResolver wallets,
            ClockPort clock,
            TransferIdentityGenerator ids) {
        this(transfers, routes, wallets, clock, ids,
                (sender, source, destination, currency, network, acceptedAt) ->
                        Optional.empty(),
                new SettlementTransferIdentityGenerator() {
                    @Override
                    public SettlementTransfer.BoundaryId nextBoundaryId() {
                        return new SettlementTransfer.BoundaryId(
                                java.util.UUID.randomUUID());
                    }

                    @Override
                    public SettlementTransfer.TransitionId nextTransitionId() {
                        return new SettlementTransfer.TransitionId(
                                java.util.UUID.randomUUID());
                    }
                });
    }

    public TransferApplicationService(
            TransferRepository transfers,
            TransferRouteCatalog routes,
            WalletRoleResolver wallets,
            ClockPort clock,
            TransferIdentityGenerator ids,
            SettlementInstructionResolver settlementInstructions,
            SettlementTransferIdentityGenerator settlementIds) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.settlementInstructions = Objects.requireNonNull(
                settlementInstructions, "settlementInstructions");
        this.settlementIds = Objects.requireNonNull(
                settlementIds, "settlementIds");
    }

    public TransferAcceptance accept(
            ParticipantScope participant,
            AcceptanceRequest request,
            IdempotencyKey key) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        BankAccountReference source;
        BankAccountReference destination;
        Optional<SettlementNetwork> requestedNetwork;
        CanonicalCommand requestCommand;
        try {
            source = new BankAccountReference(request.sourceBankAccount());
            destination =
                    new BankAccountReference(request.destinationBankAccount());
            if (source.equals(destination)) {
                throw new IllegalArgumentException(
                        "source and destination bank accounts must differ");
            }
            if (!CURRENCY.matcher(request.currency()).matches()) {
                throw new IllegalArgumentException(
                        "currency must be a safe uppercase identifier");
            }
            requestedNetwork = parseNetwork(request.settlementNetwork());
            requestCommand = TransferCommandCanonicalizer.request(
                    participant, request.amount(), request.currency(), source, destination,
                    requestedNetwork);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
        return transfers.accept(
                participant, key, requestCommand.metadata(), () -> create(
                        participant, request, key, source, destination,
                        requestedNetwork, requestCommand));
    }

    public Transfer find(TransferId transferId, ParticipantScope participant) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(participant, "participant");
        return transfers.findById(transferId, participant)
                .orElseThrow(TransferNotFoundException::new);
    }

    public TransferAcceptance findAcceptance(
            TransferId transferId, ParticipantScope participant) {
        Transfer transfer = find(transferId, participant);
        return new TransferAcceptance(
                transfer, transfers.findSettlementById(transferId, participant), false);
    }

    private TransferAcceptancePlan create(
            ParticipantScope participant,
            AcceptanceRequest request,
            IdempotencyKey key,
            BankAccountReference source,
            BankAccountReference destination,
            Optional<SettlementNetwork> requestedNetwork,
            CanonicalCommand requestCommand) {
        TransferRouteCatalog.Route route = routes.find(request.currency(), requestedNetwork)
                .orElseThrow(UnsupportedTransferConfigurationException::new);
        if (!route.currency().equals(request.currency())
                || requestedNetwork.filter(value -> value != route.settlementNetwork())
                        .isPresent()) {
            throw new IllegalStateException("transfer route catalog returned mismatched context");
        }
        TokenQuantity quantity = parseQuantity(request.amount(), route.assetUnit());
        java.time.Instant acceptedAt = clock.now().truncatedTo(ChronoUnit.MICROS);
        Optional<SettlementInstructionResolver.Resolution> settlementResolution =
                settlementInstructions.resolve(
                        participant, source, destination, request.currency(),
                        route.settlementNetwork(), acceptedAt);
        if (settlementInstructions.required() && settlementResolution.isEmpty()) {
            throw new UnsupportedTransferConfigurationException();
        }
        WalletRoleResolver.Resolution resolution = settlementResolution
                .map(value -> new WalletRoleResolver.Resolution(
                        value.sender().wallet(), value.recipient().wallet(),
                        value.workflowVersion() + ':'
                                + value.sender().instructionVersion() + ':'
                                + value.recipient().instructionVersion()))
                .orElseGet(() -> wallets.resolve(participant, route));
        CanonicalCommand resolvedCommand = TransferCommandCanonicalizer.resolved(
                participant, quantity.toCanonicalString(), source, destination,
                route, resolution);
        TransferId transferId = ids.nextTransferId();
        List<TransferEffect.Id> effectIds = new ArrayList<>(5);
        for (int index = 0; index < 5; index++) {
            effectIds.add(ids.nextEffectId());
        }
        TransferAcceptanceContext context = new TransferAcceptanceContext(
                source, destination, resolution.senderWallet(), resolution.recipientWallet(),
                route.settlementNetwork(), route.currency(), route.routeVersion(),
                resolution.policyVersion(), requestCommand.canonicalizationVersion(),
                requestCommand.sha256(), resolvedCommand.canonicalizationVersion(),
                resolvedCommand.sha256(), key.sha256());
        Transfer transfer = Transfer.accepted(
                transferId,
                new TransferParticipant(participant.tenantId(), participant.participantId()),
                context, quantity, effectIds, ids.nextTransitionId(), acceptedAt,
                new EvidenceRef("participant:transfer:acceptance:" + transferId));
        Optional<SettlementTransfer> settlement = settlementResolution.map(value -> {
            List<SettlementTransfer.BoundaryId> boundaryIds = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) {
                boundaryIds.add(settlementIds.nextBoundaryId());
            }
            String commandDigest = digest(
                    resolvedCommand.sha256(), value.workflowVersion(),
                    value.sender().instructionId(),
                    value.sender().instructionVersion(),
                    value.sender().participant().tenantId(),
                    value.sender().participant().participantId(),
                    value.sender().bankId().value(),
                    value.sender().bankAccountId().value(),
                    value.sender().bankAccountReference().value(),
                    value.sender().wallet().value(),
                    value.sender().walletMetadataVersion(),
                    value.recipient().instructionId(),
                    value.recipient().instructionVersion(),
                    value.recipient().participant().tenantId(),
                    value.recipient().participant().participantId(),
                    value.recipient().bankId().value(),
                    value.recipient().bankAccountId().value(),
                    value.recipient().bankAccountReference().value(),
                    value.recipient().wallet().value(),
                    value.recipient().walletMetadataVersion(),
                    value.adminWallet().value(),
                    value.adminWalletMetadataVersion(),
                    route.settlementNetwork().name(),
                    value.contractReference(), value.payoutPolicyVersion(),
                    value.conversionPolicyVersion(),
                    value.accountingPolicyVersion(), value.feePolicyVersion(),
                    value.finalityPolicyVersion(),
                    value.reconciliationPolicyVersion());
            SettlementTransfer.AcceptedContext settlementContext =
                    new SettlementTransfer.AcceptedContext(
                            value.workflowVersion(),
                            UsdCents.parsePositive(
                                    request.amount(), request.currency()), quantity,
                            value.sender(), value.recipient(),
                            value.adminWallet(), value.adminWalletMetadataVersion(),
                            route.settlementNetwork(), value.contractReference(),
                            value.payoutPolicyVersion(),
                            value.conversionPolicyVersion(),
                            value.accountingPolicyVersion(),
                            value.feePolicyVersion(),
                            value.finalityPolicyVersion(),
                            value.reconciliationPolicyVersion(),
                            key.sha256(), commandDigest, acceptedAt);
            return SettlementTransfer.accepted(
                    transferId, settlementContext, boundaryIds,
                    settlementIds.nextTransitionId(),
                    new EvidenceRef(
                            "participant:settlement-transfer:accepted:" + transferId));
        });
        return new TransferAcceptancePlan(transfer, settlement);
    }

    private static Optional<SettlementNetwork> parseNetwork(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(SettlementNetwork.valueOf(value));
    }

    private static TokenQuantity parseQuantity(String amount, AssetUnit unit) {
        try {
            return TokenQuantity.parse(amount, unit);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static String digest(String... fields) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(1);
                for (String field : fields) {
                    byte[] encoded = Objects.requireNonNull(field, "canonical field")
                            .getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "settlement command encoding failed", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record AcceptanceRequest(
            String amount,
            String currency,
            String sourceBankAccount,
            String destinationBankAccount,
            String settlementNetwork) {

        public AcceptanceRequest {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(sourceBankAccount, "sourceBankAccount");
            Objects.requireNonNull(destinationBankAccount, "destinationBankAccount");
        }
    }
}
