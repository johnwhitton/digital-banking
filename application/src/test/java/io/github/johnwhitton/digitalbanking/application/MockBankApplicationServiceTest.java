package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.BankIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MockBankApplicationServiceTest {

    private static final Instant TIME = Instant.parse("2026-07-18T12:00:00Z");
    private static final ParticipantScope OWNER =
            new ParticipantScope("local-demo", "USER_1");
    private static final SyntheticBankAccount.BankId BANK =
            new SyntheticBankAccount.BankId("BANK_1");
    private static final SyntheticBankAccount.AccountId ACCOUNT =
            new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT");

    @Test
    void retriesOnlyExplicitPreEffectFailureWithTheSameDurableCommand() {
        FakeBank bank = new FakeBank();
        bank.preEffectFailures.set(1);
        MockBankApplicationService service = service(bank, 2);

        MockBankPort.BankResponse response = service.execute(
                OWNER, BANK, ACCOUNT, BankOperation.Kind.WITHDRAWAL,
                new MockBankApplicationService.Request("1", "USD"),
                IdempotencyKey.of("do-not-retain-raw-key"));

        assertEquals(MockBankPort.ResponseStatus.SUCCEEDED, response.status());
        assertEquals(2, bank.calls.get());
        assertSame(bank.firstCommand, bank.lastCommand);
        assertFalse(bank.lastCommand.idempotencyKeyDigest()
                .contains("do-not-retain-raw-key"));
    }

    @Test
    void unknownResponseIsNotRetriedAndCallerValidationFailsBeforeEffect() {
        FakeBank bank = new FakeBank();
        bank.unknown = true;
        MockBankApplicationService service = service(bank, 2);

        assertEquals(MockBankPort.ResponseStatus.UNKNOWN, service.execute(
                OWNER, BANK, ACCOUNT, BankOperation.Kind.DEPOSIT,
                new MockBankApplicationService.Request("1", "USD"),
                IdempotencyKey.of("unknown-key")).status());
        assertEquals(1, bank.calls.get());
        assertThrows(InvalidRequestException.class, () -> service.execute(
                OWNER, BANK, ACCOUNT, BankOperation.Kind.DEPOSIT,
                new MockBankApplicationService.Request("0", "USD"),
                IdempotencyKey.of("zero-key")));
        assertThrows(InvalidRequestException.class, () -> service.execute(
                OWNER, BANK, ACCOUNT, BankOperation.Kind.DEPOSIT,
                new MockBankApplicationService.Request("1", "EUR"),
                IdempotencyKey.of("currency-key")));
        assertThrows(MockBankNotFoundException.class, () -> service.findAccount(
                new ParticipantScope("local-demo", "USER_2"), BANK, ACCOUNT));
        assertEquals(1, bank.calls.get());
    }

    private static MockBankApplicationService service(FakeBank bank, int attempts) {
        BankIdentityGenerator ids = new BankIdentityGenerator() {
            @Override
            public BankOperation.Id nextOperationId() {
                return new BankOperation.Id(new UUID(1, 1));
            }

            @Override
            public BankOperation.EvidenceId nextEvidenceId() {
                return new BankOperation.EvidenceId(new UUID(2, 2));
            }
        };
        return new MockBankApplicationService(
                bank, () -> TIME, ids,
                new BankOperation.PolicyVersion("bank-policy-v1"), attempts);
    }

    private static final class FakeBank implements MockBankPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger preEffectFailures = new AtomicInteger();
        private BankCommand firstCommand;
        private BankCommand lastCommand;
        private boolean unknown;

        @Override
        public Outcome request(Command command) {
            return Outcome.rejectedNoEffect("unused-phase-6a");
        }

        @Override
        public Optional<SyntheticBankAccount> findAccount(
                SyntheticBankAccount.BankId bankId,
                SyntheticBankAccount.AccountId accountId,
                ParticipantScope participant) {
            if (!OWNER.equals(participant)) {
                return Optional.empty();
            }
            return Optional.of(new SyntheticBankAccount(
                    BANK, ACCOUNT,
                    new SyntheticBankAccount.Owner(
                            OWNER.tenantId(), OWNER.participantId()),
                    new UsdCents(BigInteger.valueOf(10_000)), true, 0,
                    new SyntheticBankAccount.FixtureVersion("fixture-v1"),
                    new UsdCents(BigInteger.valueOf(10_000)), TIME, TIME));
        }

        @Override
        public BankResponse execute(BankCommand command) {
            calls.incrementAndGet();
            if (firstCommand == null) {
                firstCommand = command;
            }
            lastCommand = command;
            if (preEffectFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new BankPreEffectFailureException("safe-no-effect");
            }
            if (unknown) {
                return new BankResponse(command.operationId(), ResponseStatus.UNKNOWN,
                        null, null, false, "response-unknown");
            }
            return new BankResponse(
                    command.operationId(), ResponseStatus.SUCCEEDED,
                    command.evidenceId(), UsdCents.ZERO, false, null);
        }
    }
}
