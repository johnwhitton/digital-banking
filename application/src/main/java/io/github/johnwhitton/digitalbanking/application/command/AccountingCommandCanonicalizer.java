package io.github.johnwhitton.digitalbanking.application.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.github.johnwhitton.digitalbanking.application.port.ReserveAccountingPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;

/** Stable canonical digest for one trusted evidence-posting request. */
public final class AccountingCommandCanonicalizer {

    private AccountingCommandCanonicalizer() {
    }

    public static String digest(
            ReserveAccounting.EvidenceIdentity evidence,
            ReserveAccounting.PostingType type,
            ReserveAccountingPort.EvidencePolicy policy) {
        String value = String.join("\n",
                "accounting-post-v1",
                evidence.value(),
                type.name(),
                policy.accountingPolicyVersion().value(),
                policy.bankEvidencePolicyVersion(),
                policy.mintEvidencePolicyVersion(),
                policy.custodyEvidencePolicyVersion(),
                policy.burnEvidencePolicyVersion(),
                policy.chainAssetId(),
                policy.settlementNetwork(),
                policy.contractReference(),
                policy.maximumObservationAge().toString());
        return sha256(value);
    }

    public static String reversalDigest(
            ReserveAccounting.JournalId originalJournalId,
            ReserveAccounting.EvidenceIdentity correctionEvidence,
            ReserveAccounting.PolicyVersion policyVersion) {
        return sha256(String.join("\n",
                "accounting-reversal-v1",
                originalJournalId.value().toString(),
                correctionEvidence.value(),
                policyVersion.value()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
