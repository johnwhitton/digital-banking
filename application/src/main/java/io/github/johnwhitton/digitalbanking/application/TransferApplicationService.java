package io.github.johnwhitton.digitalbanking.application;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommand;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.command.TransferCommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.application.port.TransferRouteCatalog;
import io.github.johnwhitton.digitalbanking.application.port.WalletRoleResolver;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferParticipant;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;

/** Transport-neutral transfer acceptance and participant-scoped read use cases. */
public final class TransferApplicationService {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z][A-Z0-9]{2,11}");

    private final TransferRepository transfers;
    private final TransferRouteCatalog routes;
    private final WalletRoleResolver wallets;
    private final ClockPort clock;
    private final TransferIdentityGenerator ids;

    public TransferApplicationService(
            TransferRepository transfers,
            TransferRouteCatalog routes,
            WalletRoleResolver wallets,
            ClockPort clock,
            TransferIdentityGenerator ids) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
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

    private Transfer create(
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
        WalletRoleResolver.Resolution resolution = wallets.resolve(participant, route);
        CanonicalCommand resolved = TransferCommandCanonicalizer.resolved(
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
                requestCommand.sha256(), resolved.canonicalizationVersion(),
                resolved.sha256(), key.sha256());
        return Transfer.accepted(
                transferId,
                new TransferParticipant(participant.tenantId(), participant.participantId()),
                context, quantity, effectIds, ids.nextTransitionId(),
                clock.now().truncatedTo(ChronoUnit.MICROS),
                new EvidenceRef("participant:transfer:acceptance:" + transferId));
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
