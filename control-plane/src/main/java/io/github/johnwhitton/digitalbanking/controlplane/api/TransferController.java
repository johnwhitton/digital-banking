package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.net.URI;

import io.github.johnwhitton.digitalbanking.application.InvalidRequestException;
import io.github.johnwhitton.digitalbanking.application.TransferAcceptance;
import io.github.johnwhitton.digitalbanking.application.TransferApplicationService;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/v1/transfers")
public final class TransferController {

    private static final String AMOUNT = "^(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$";
    private static final String CURRENCY = "^[A-Z][A-Z0-9]{2,11}$";
    private static final String BANK_REFERENCE =
            "^synthetic-bank:[A-Za-z0-9][A-Za-z0-9._:-]{0,111}$";
    private static final String NETWORK = "^(ETHEREUM|SOLANA)$";

    private final TransferApplicationService transfers;

    public TransferController(TransferApplicationService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> accept(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AcceptanceRequest request) {
        TransferAcceptance accepted = transfers.accept(
                requireParticipant(participant).scope(), request.toApplicationRequest(),
                parseIdempotencyKey(idempotencyKey));
        TransferResponse response = TransferResponse.from(
                accepted.transfer(), accepted.settlement());
        return ResponseEntity.accepted()
                .location(URI.create("/v1/transfers/" + response.transferId()))
                .body(response);
    }

    @GetMapping("/{transferId}")
    public TransferResponse find(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String transferId) {
        io.github.johnwhitton.digitalbanking.application.TransferAcceptance accepted =
                transfers.findAcceptance(
                        parseTransferId(transferId),
                        requireParticipant(participant).scope());
        return TransferResponse.from(accepted.transfer(), accepted.settlement());
    }

    private static ParticipantPrincipal requireParticipant(ParticipantPrincipal principal) {
        if (principal == null) {
            throw new UnauthenticatedPrincipalException();
        }
        return principal;
    }

    private static TransferId parseTransferId(String value) {
        try {
            return TransferId.from(value);
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
            @NotBlank @Size(max = 512) @Pattern(regexp = AMOUNT) String amount,
            @NotBlank @Pattern(regexp = CURRENCY) String currency,
            @NotBlank @Pattern(regexp = BANK_REFERENCE) String sourceBankAccountReference,
            @NotBlank @Pattern(regexp = BANK_REFERENCE) String destinationBankAccountReference,
            @Pattern(regexp = NETWORK) String settlementNetwork) {

        private TransferApplicationService.AcceptanceRequest toApplicationRequest() {
            return new TransferApplicationService.AcceptanceRequest(
                    amount, currency, sourceBankAccountReference,
                    destinationBankAccountReference, settlementNetwork);
        }
    }
}
