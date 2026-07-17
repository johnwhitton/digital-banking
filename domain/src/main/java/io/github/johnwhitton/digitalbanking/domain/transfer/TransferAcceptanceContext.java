package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable configuration, custody, route, and idempotency context resolved at acceptance. */
public record TransferAcceptanceContext(
        BankAccountReference sourceBankAccount,
        BankAccountReference destinationBankAccount,
        WalletReference senderWallet,
        WalletReference recipientWallet,
        SettlementNetwork settlementNetwork,
        String currency,
        String routeVersion,
        String walletPolicyVersion,
        int requestCanonicalizationVersion,
        String requestDigest,
        int resolvedCanonicalizationVersion,
        String resolvedDigest,
        String idempotencyKeyDigest) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z][A-Z0-9]{2,11}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public TransferAcceptanceContext {
        Objects.requireNonNull(sourceBankAccount, "sourceBankAccount");
        Objects.requireNonNull(destinationBankAccount, "destinationBankAccount");
        Objects.requireNonNull(senderWallet, "senderWallet");
        Objects.requireNonNull(recipientWallet, "recipientWallet");
        Objects.requireNonNull(settlementNetwork, "settlementNetwork");
        if (sourceBankAccount.equals(destinationBankAccount)) {
            throw new IllegalArgumentException("source and destination bank accounts must differ");
        }
        if (!CURRENCY.matcher(Objects.requireNonNull(currency, "currency")).matches()) {
            throw new IllegalArgumentException("currency must be a safe uppercase identifier");
        }
        routeVersion = requireText(routeVersion, "routeVersion");
        walletPolicyVersion = requireText(walletPolicyVersion, "walletPolicyVersion");
        if (requestCanonicalizationVersion < 1 || resolvedCanonicalizationVersion < 1) {
            throw new IllegalArgumentException("canonicalization versions must be positive");
        }
        requireDigest(requestDigest, "requestDigest");
        requireDigest(resolvedDigest, "resolvedDigest");
        requireDigest(idempotencyKeyDigest, "idempotencyKeyDigest");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1-128 characters");
        }
        return value;
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
    }
}
