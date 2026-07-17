package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.net.URI;

import io.github.johnwhitton.digitalbanking.application.InvalidRequestException;
import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.TokenOperationApplicationService;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/v1/token-operations")
public final class TokenOperationController {

    private static final String IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}";
    private static final String QUANTITY = "^(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$";

    private final TokenOperationApplicationService operations;

    public TokenOperationController(TokenOperationApplicationService operations) {
        this.operations = operations;
    }

    @PostMapping("/mints")
    public ResponseEntity<TokenOperationResponse> acceptMint(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AcceptanceRequest request) {
        return accept(OperationKind.MINT, participant, idempotencyKey, request);
    }

    @PostMapping("/burns")
    public ResponseEntity<TokenOperationResponse> acceptBurn(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AcceptanceRequest request) {
        return accept(OperationKind.BURN, participant, idempotencyKey, request);
    }

    @GetMapping("/{operationId}")
    public TokenOperationResponse find(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String operationId) {
        return TokenOperationResponse.from(operations.find(
                parseOperationId(operationId), requireParticipant(participant).scope()));
    }

    private ResponseEntity<TokenOperationResponse> accept(
            OperationKind kind,
            ParticipantPrincipal participant,
            String idempotencyKey,
            AcceptanceRequest request) {
        OperationAcceptance accepted = operations.accept(
                kind,
                requireParticipant(participant).scope(),
                request.toApplicationRequest(),
                parseIdempotencyKey(idempotencyKey));
        TokenOperationResponse response = TokenOperationResponse.from(accepted.operation());
        return ResponseEntity.accepted()
                .location(URI.create("/v1/token-operations/" + response.operationId()))
                .body(response);
    }

    private static ParticipantPrincipal requireParticipant(ParticipantPrincipal principal) {
        if (principal == null) {
            throw new UnauthenticatedPrincipalException();
        }
        return principal;
    }

    private static OperationId parseOperationId(String value) {
        try {
            return OperationId.from(value);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static IdempotencyKey parseIdempotencyKey(String value) {
        try {
            return IdempotencyKey.of(value);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    public record AcceptanceRequest(
            @Positive int contractVersion,
            @NotBlank @Size(max = 64) @Pattern(regexp = IDENTIFIER) String assetId,
            @NotBlank @Size(max = 64) @Pattern(regexp = IDENTIFIER) String unitId,
            @Positive int unitVersion,
            @NotBlank @Size(max = 512) @Pattern(regexp = QUANTITY) String quantity,
            @NotBlank @Size(max = 256) String businessCorrelation) {

        private TokenOperationApplicationService.AcceptanceRequest toApplicationRequest() {
            return new TokenOperationApplicationService.AcceptanceRequest(
                    contractVersion, assetId, unitId, unitVersion,
                    quantity, businessCorrelation);
        }
    }
}
