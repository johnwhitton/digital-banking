package io.github.johnwhitton.digitalbanking.application;

import java.time.temporal.ChronoUnit;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.command.BankCommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommand;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.BankIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;

/** Participant-scoped use cases for executable local synthetic-bank operations. */
public final class MockBankApplicationService {

    private final MockBankPort bank;
    private final ClockPort clock;
    private final BankIdentityGenerator ids;
    private final BankOperation.PolicyVersion policyVersion;
    private final int maximumPreEffectAttempts;

    public MockBankApplicationService(
            MockBankPort bank,
            ClockPort clock,
            BankIdentityGenerator ids,
            BankOperation.PolicyVersion policyVersion,
            int maximumPreEffectAttempts) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        if (maximumPreEffectAttempts < 1 || maximumPreEffectAttempts > 3) {
            throw new IllegalArgumentException("bank pre-effect attempts must be 1-3");
        }
        this.maximumPreEffectAttempts = maximumPreEffectAttempts;
    }

    public MockBankPort.BankResponse execute(
            ParticipantScope participant,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId,
            BankOperation.Kind kind,
            Request request,
            IdempotencyKey key) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(request, "request");
        if (bank.findAccount(bankId, accountId, participant).isEmpty()) {
            throw new MockBankNotFoundException();
        }
        UsdCents amount;
        try {
            amount = UsdCents.parsePositive(request.amount(), request.currency());
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
        CanonicalCommand canonical = BankCommandCanonicalizer.encode(
                participant, bankId, accountId, kind, amount);
        BankOperation.Id operationId = ids.nextOperationId();
        MockBankPort.BankCommand command = new MockBankPort.BankCommand(
                operationId, ids.nextEvidenceId(), bankId, accountId, participant,
                amount, kind, key.sha256(), canonical.sha256(),
                new BankOperation.CorrelationId("local-bank:" + operationId.value()),
                policyVersion, clock.now().truncatedTo(ChronoUnit.MICROS));
        for (int attempt = 1; ; attempt++) {
            try {
                return bank.execute(command);
            } catch (BankPreEffectFailureException safeNoEffect) {
                if (attempt == maximumPreEffectAttempts) {
                    throw safeNoEffect;
                }
            }
        }
    }

    public SyntheticBankAccount findAccount(
            ParticipantScope participant,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId) {
        return bank.findAccount(bankId, accountId, participant)
                .orElseThrow(MockBankNotFoundException::new);
    }

    public BankOperation findOperation(
            ParticipantScope participant, BankOperation.Id operationId) {
        return bank.inquire(operationId, participant)
                .orElseThrow(MockBankNotFoundException::new);
    }

    public record Request(String amount, String currency) {
        public Request {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
        }
    }
}
