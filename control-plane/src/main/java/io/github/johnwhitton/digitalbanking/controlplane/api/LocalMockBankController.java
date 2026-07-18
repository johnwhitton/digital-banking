package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.net.URI;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.InvalidRequestException;
import io.github.johnwhitton.digitalbanking.application.MockBankApplicationService;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.controlplane.config.LocalFinancialMetrics;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit local-profile HTTP boundary for synthetic bank effects only. */
@RestController
@Profile("local-demo")
@RequestMapping("/local/v1/mock-banks")
public final class LocalMockBankController {

    private static final String AMOUNT =
            "^(?:[1-9][0-9]{0,15}|(?:0|[1-9][0-9]{0,15})\\.(?:[1-9]|[0-9][1-9]))$";

    private final MockBankApplicationService banks;
    private final LocalFinancialMetrics metrics;

    public LocalMockBankController(
            MockBankApplicationService banks, LocalFinancialMetrics metrics) {
        this.banks = Objects.requireNonNull(banks, "banks");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @PostMapping("/{bankId}/accounts/{accountId}/withdrawals")
    public ResponseEntity<OperationResponse> withdraw(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String bankId,
            @PathVariable String accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MoneyRequest request) {
        return execute(participant, bankId, accountId, idempotencyKey, request,
                BankOperation.Kind.WITHDRAWAL);
    }

    @PostMapping("/{bankId}/accounts/{accountId}/deposits")
    public ResponseEntity<OperationResponse> deposit(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String bankId,
            @PathVariable String accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MoneyRequest request) {
        return execute(participant, bankId, accountId, idempotencyKey, request,
                BankOperation.Kind.DEPOSIT);
    }

    @GetMapping("/{bankId}/accounts/{accountId}")
    public AccountResponse account(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String bankId,
            @PathVariable String accountId) {
        return AccountResponse.from(banks.findAccount(
                requireParticipant(participant).scope(), bank(bankId), account(accountId)));
    }

    @GetMapping("/operations/{operationId}")
    public OperationResponse operation(
            @AuthenticationPrincipal ParticipantPrincipal participant,
            @PathVariable String operationId) {
        return OperationResponse.from(banks.findOperation(
                requireParticipant(participant).scope(), operationId(operationId)));
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public Resource openApi() {
        return new ClassPathResource("openapi/local-mock-banks-v1.yaml");
    }

    private ResponseEntity<OperationResponse> execute(
            ParticipantPrincipal participant,
            String bankId,
            String accountId,
            String idempotencyKey,
            MoneyRequest request,
            BankOperation.Kind kind) {
        MockBankPort.BankResponse outcome = banks.execute(
                requireParticipant(participant).scope(), bank(bankId), account(accountId),
                kind, new MockBankApplicationService.Request(
                        request.amount(), request.currency()), key(idempotencyKey));
        metrics.bank(kind, outcome.status().name());
        OperationResponse response = OperationResponse.from(outcome);
        if (outcome.status() == MockBankPort.ResponseStatus.UNKNOWN) {
            return ResponseEntity.accepted()
                    .location(URI.create("/local/v1/mock-banks/operations/"
                            + response.operationId()))
                    .body(response);
        }
        return ResponseEntity.ok(response);
    }

    private static ParticipantPrincipal requireParticipant(ParticipantPrincipal value) {
        if (value == null) {
            throw new UnauthenticatedPrincipalException();
        }
        return value;
    }

    private static SyntheticBankAccount.BankId bank(String value) {
        try {
            return new SyntheticBankAccount.BankId(value);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static SyntheticBankAccount.AccountId account(String value) {
        try {
            return new SyntheticBankAccount.AccountId(value);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static BankOperation.Id operationId(String value) {
        try {
            return new BankOperation.Id(java.util.UUID.fromString(value));
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    private static IdempotencyKey key(String value) {
        try {
            return IdempotencyKey.of(value);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
    }

    public record MoneyRequest(
            @NotBlank @Size(max = 19) @Pattern(regexp = AMOUNT) String amount,
            @NotBlank @Pattern(regexp = "USD") String currency) { }

    public record AccountResponse(
            String bankId,
            String accountId,
            String currency,
            String balance,
            boolean enabled,
            long version) {
        static AccountResponse from(SyntheticBankAccount value) {
            return new AccountResponse(
                    value.bankId().value(), value.accountId().value(), "USD",
                    value.balance().toCanonicalString(), value.enabled(), value.version());
        }
    }

    public record OperationResponse(
            String operationId,
            String status,
            String evidenceId,
            String balanceAfter,
            boolean replayed) {
        static OperationResponse from(MockBankPort.BankResponse value) {
            return new OperationResponse(
                    value.operationId().value().toString(), value.status().name(),
                    value.evidenceId() == null ? null
                            : value.evidenceId().value().toString(),
                    value.balanceAfter() == null ? null
                            : value.balanceAfter().toCanonicalString(),
                    value.replayed());
        }

        static OperationResponse from(BankOperation value) {
            return new OperationResponse(
                    value.id().value().toString(), value.status().name(),
                    value.evidenceId().value().toString(),
                    value.balanceAfter() == null ? null
                            : value.balanceAfter().toCanonicalString(), false);
        }
    }
}
