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

import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowContextResolver;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Participant-scoped acceptance and read boundary for user-held workflows. */
public final class UsdzelleWorkflowApplicationService {

    private final UsdzelleWorkflowRepository workflows;
    private final UsdzelleWorkflowContextResolver resolver;
    private final UsdzelleWorkflowIdentityGenerator ids;

    public UsdzelleWorkflowApplicationService(
            UsdzelleWorkflowRepository workflows,
            UsdzelleWorkflowContextResolver resolver,
            UsdzelleWorkflowIdentityGenerator ids) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public UsdzelleWorkflowAcceptance accept(
            UsdzelleWorkflow.Kind kind,
            ParticipantScope participant,
            AcceptanceRequest request,
            IdempotencyKey key) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(key, "key");
        ValidatedRequest validated = validate(request);
        String requestDigest = digest(
                "usdzelle-workflow-request-v1", kind.name(), participant.tenantId(),
                participant.participantId(), validated.amount().toCanonicalString(),
                request.currency(), validated.bankAccount().value(),
                validated.network().map(Enum::name).orElse(""));
        return workflows.accept(participant, kind, key, requestDigest, () -> create(
                kind, participant, request, key, validated, requestDigest));
    }

    public UsdzelleWorkflow find(
            UsdzelleWorkflow.Id workflowId, ParticipantScope participant) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(participant, "participant");
        return workflows.findById(workflowId, participant)
                .orElseThrow(UsdzelleWorkflowNotFoundException::new);
    }

    private UsdzelleWorkflow create(
            UsdzelleWorkflow.Kind kind,
            ParticipantScope participant,
            AcceptanceRequest request,
            IdempotencyKey key,
            ValidatedRequest validated,
            String requestDigest) {
        UsdzelleWorkflowContextResolver.Resolution resolved = resolver.resolve(
                kind, participant, validated.bankAccount(), request.currency(),
                validated.network());
        TokenQuantity tokenQuantity;
        try {
            tokenQuantity = TokenQuantity.parse(
                    validated.amount().toCanonicalString(), resolved.assetUnit());
        } catch (IllegalArgumentException failure) {
            throw new UnsupportedTransferConfigurationException();
        }
        String commandDigest = digest(
                "usdzelle-workflow-context-v1", requestDigest,
                resolved.assetUnit().assetId(), resolved.assetUnit().unitId(),
                Integer.toString(resolved.assetUnit().version()),
                Integer.toString(resolved.assetUnit().scale()),
                resolved.bankId().value(), resolved.bankAccountId().value(),
                resolved.userWallet().value(), resolved.userWalletMetadataVersion(),
                resolved.adminWallet().value(), resolved.adminWalletMetadataVersion(),
                resolved.network().name(), resolved.contractReference(),
                resolved.payoutPolicyVersion(),
                resolved.workflowVersion(), resolved.conversionPolicyVersion(),
                resolved.accountingPolicyVersion(), resolved.feePolicyVersion(),
                resolved.finalityPolicyVersion(), resolved.reconciliationPolicyVersion());
        UsdzelleWorkflow.Id workflowId = ids.nextWorkflowId();
        int stepCount = kind == UsdzelleWorkflow.Kind.ACQUISITION ? 5 : 6;
        List<UsdzelleWorkflow.StepId> stepIds = new ArrayList<>(stepCount);
        for (int index = 0; index < stepCount; index++) {
            stepIds.add(ids.nextStepId());
        }
        UsdzelleWorkflow.AcceptedContext context = new UsdzelleWorkflow.AcceptedContext(
                resolved.workflowVersion(), validated.amount(), tokenQuantity,
                resolved.bankId(), resolved.bankAccountId(), resolved.userWallet(),
                resolved.userWalletMetadataVersion(), resolved.adminWallet(),
                resolved.adminWalletMetadataVersion(), resolved.network(),
                resolved.contractReference(), resolved.payoutPolicyVersion(),
                resolved.conversionPolicyVersion(),
                resolved.accountingPolicyVersion(), resolved.feePolicyVersion(),
                resolved.finalityPolicyVersion(), resolved.reconciliationPolicyVersion(),
                key.sha256(), commandDigest,
                resolved.acceptedAt().truncatedTo(ChronoUnit.MICROS));
        return UsdzelleWorkflow.accepted(
                workflowId, kind,
                new UsdzelleWorkflow.Participant(
                        participant.tenantId(), participant.participantId()),
                context, stepIds, ids.nextTransitionId(),
                new EvidenceRef("participant:usdzelle:"
                        + kind.name().toLowerCase(java.util.Locale.ROOT)
                        + ":accepted:" + workflowId.value()));
    }

    private static ValidatedRequest validate(AcceptanceRequest request) {
        try {
            UsdCents amount = UsdCents.parsePositive(request.amount(), request.currency());
            BankAccountReference bankAccount =
                    new BankAccountReference(request.bankAccountReference());
            Optional<SettlementNetwork> network = request.settlementNetwork() == null
                    ? Optional.empty()
                    : Optional.of(SettlementNetwork.valueOf(request.settlementNetwork()));
            return new ValidatedRequest(amount, bankAccount, network);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static String digest(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                for (String field : fields) {
                    byte[] encoded = Objects.requireNonNull(field, "canonical field")
                            .getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("canonical command encoding failed", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record AcceptanceRequest(
            String amount,
            String currency,
            String bankAccountReference,
            String settlementNetwork) {
        public AcceptanceRequest {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(bankAccountReference, "bankAccountReference");
        }
    }

    private record ValidatedRequest(
            UsdCents amount,
            BankAccountReference bankAccount,
            Optional<SettlementNetwork> network) {
    }
}
