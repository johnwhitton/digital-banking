package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.net.URI;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.InvalidRequestException;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowAcceptance;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowApplicationService;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.controlplane.config.UsdzelleWorkflowMetrics;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local-demo & !local-signer & (local-ethereum | local-solana)")
@RequestMapping("/v1/usdzelle")
public final class UsdzelleWorkflowController {

    private static final String AMOUNT = "^(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$";
    private static final String BANK_REFERENCE =
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    private final UsdzelleWorkflowApplicationService workflows;
    private final UsdzelleWorkflowMetrics metrics;

    public UsdzelleWorkflowController(
            UsdzelleWorkflowApplicationService workflows,
            UsdzelleWorkflowMetrics metrics) {
        this.workflows = workflows;
        this.metrics = metrics;
    }

    @PostMapping("/acquisitions")
    public ResponseEntity<UsdzelleWorkflowResponse> acceptAcquisition(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AcceptanceRequest request) {
        return accept(UsdzelleWorkflow.Kind.ACQUISITION, "acquisitions",
                participant, idempotencyKey, request);
    }

    @GetMapping("/acquisitions/{workflowId}")
    public UsdzelleWorkflowResponse findAcquisition(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String workflowId) {
        return find(UsdzelleWorkflow.Kind.ACQUISITION, participant, workflowId);
    }

    @PostMapping("/redemptions")
    public ResponseEntity<UsdzelleWorkflowResponse> acceptRedemption(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AcceptanceRequest request) {
        return accept(UsdzelleWorkflow.Kind.REDEMPTION, "redemptions",
                participant, idempotencyKey, request);
    }

    @GetMapping("/redemptions/{workflowId}")
    public UsdzelleWorkflowResponse findRedemption(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String workflowId) {
        return find(UsdzelleWorkflow.Kind.REDEMPTION, participant, workflowId);
    }

    private ResponseEntity<UsdzelleWorkflowResponse> accept(
            UsdzelleWorkflow.Kind kind,
            String path,
            ParticipantPrincipal principal,
            String idempotencyKey,
            AcceptanceRequest request) {
        UsdzelleWorkflowAcceptance accepted = workflows.accept(
                kind, requireParticipant(principal).scope(), request.toApplication(),
                key(idempotencyKey));
        metrics.accepted(kind, accepted.replayed());
        UsdzelleWorkflowResponse response =
                UsdzelleWorkflowResponse.from(accepted.workflow());
        return ResponseEntity.accepted()
                .location(URI.create("/v1/usdzelle/" + path + "/" + response.workflowId()))
                .body(response);
    }

    private UsdzelleWorkflowResponse find(
            UsdzelleWorkflow.Kind expected,
            ParticipantPrincipal principal,
            String workflowId) {
        UsdzelleWorkflow workflow = workflows.find(id(workflowId),
                requireParticipant(principal).scope());
        if (workflow.kind() != expected) {
            throw new io.github.johnwhitton.digitalbanking.application
                    .UsdzelleWorkflowNotFoundException();
        }
        return UsdzelleWorkflowResponse.from(workflow);
    }

    private static ParticipantPrincipal requireParticipant(ParticipantPrincipal participant) {
        if (participant == null) {
            throw new UnauthenticatedPrincipalException();
        }
        return participant;
    }

    private static IdempotencyKey key(String value) {
        try {
            return new IdempotencyKey(value);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static UsdzelleWorkflow.Id id(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("workflow ID is not canonical");
            }
            return new UsdzelleWorkflow.Id(parsed);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    public record AcceptanceRequest(
            @NotBlank @Size(max = 512) @Pattern(regexp = AMOUNT) String amount,
            @NotBlank @Pattern(regexp = "USD") String currency,
            @NotBlank @Size(max = 128) @Pattern(regexp = BANK_REFERENCE)
            String bankAccountReference,
            @Pattern(regexp = "^(ETHEREUM|SOLANA)$") String settlementNetwork) {

        UsdzelleWorkflowApplicationService.AcceptanceRequest toApplication() {
            return new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                    amount, currency, bankAccountReference, settlementNetwork);
        }
    }
}
