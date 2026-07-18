package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;

public interface BankIdentityGenerator {

    BankOperation.Id nextOperationId();

    BankOperation.EvidenceId nextEvidenceId();
}
