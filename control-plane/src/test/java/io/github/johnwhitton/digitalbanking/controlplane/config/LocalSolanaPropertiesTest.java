package io.github.johnwhitton.digitalbanking.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import io.github.johnwhitton.digitalbanking.solana.sava.SavaSolanaMintChainAdapter;
import org.junit.jupiter.api.Test;

class LocalSolanaPropertiesTest {

    private static final String GENESIS =
            "cGfHiC6Kgg3FpFZvgwGcswsCRtp4aBP2fzuXRQPizuN";
    private static final String MINT =
            "83wQsbSD89is8SVPAR325f5qXPhg5hdTuJfbwotqRsnT";
    private static final String USER =
            "5FN9G4Lm7ffMX3Uun11thakD29iuQgxBJHmFCiwYVWVG";
    private static final String USER_2 =
            "86Cud6zB3MZRYcCBgYftqoZRZw1jVqQfDkobchgk9vir";
    private static final String FEE =
            "6LrPaQmHcveWvBDZAEoaRwtjtRZNGFAetBdAbNWDr7Wj";
    private static final String AUTHORITY =
            "2AFnC96vrk2EPo4Qgd3GQN6ionzpEjDQbmUjjm4vWg2k";
    private static final String ADMIN_REDEMPTION =
            "9xQeWvG816bUx9EPfEZvT9YcDT4VQ5cMfbX6LEK2q4H";

    @Test
    void acceptsOnlyExplicitLoopbackClassicSplFinalizedConfiguration() {
        LocalSolanaProperties accepted = properties("http://127.0.0.1:8899");
        assertEquals(URI.create("http://127.0.0.1:8899"), accepted.rpcUri());
        assertEquals(2, accepted.decimals());

        assertThrows(IllegalArgumentException.class,
                () -> properties("https://api.mainnet-beta.solana.com"));
        assertThrows(IllegalArgumentException.class,
                () -> properties("http://devnet.example:8899"));
        assertThrows(IllegalArgumentException.class,
                () -> properties("http://user:secret@127.0.0.1:8899"));
        assertThrows(IllegalArgumentException.class, () -> new LocalSolanaProperties(
                URI.create("http://127.0.0.1:8899"), GENESIS, MINT, USER, USER_2,
                Path.of(".solana-runtime"), Path.of(".solana-runtime/keys/fee.json"),
                FEE, "local-solana:fee", "fee-v1",
                Path.of(".solana-runtime/keys/authority.json"), AUTHORITY,
                "local-solana:authority", "authority-v1",
                Path.of(".solana-runtime/keys/user-1.json"),
                "local-solana:transfer", "transfer-v1",
                Path.of(".solana-runtime/keys/user-2.json"),
                "local-solana:transfer-destination", "transfer-destination-v1",
                ADMIN_REDEMPTION,
                Path.of(".solana-runtime/keys/admin-redemption-owner.json"),
                "local-solana:burn", "burn-v1", "registry-v1",
                "USD_STABLE", "USD",
                1, 2, BigInteger.valueOf(1_000_000_000_000L), "policy-v1",
                SavaSolanaMintChainAdapter.CommitmentLevel.CONFIRMED,
                SavaSolanaMintChainAdapter.CommitmentLevel.CONFIRMED,
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000),
                Duration.ofSeconds(5)));
    }

    private static LocalSolanaProperties properties(String rpc) {
        return new LocalSolanaProperties(
                URI.create(rpc), GENESIS, MINT, USER, USER_2,
                Path.of(".solana-runtime"),
                Path.of(".solana-runtime/keys/fee-payer.json"),
                FEE, "local-solana:fee-payer", "fee-v1",
                Path.of(".solana-runtime/keys/admin-mint-authority.json"),
                AUTHORITY, "local-solana:mint-authority", "authority-v1",
                Path.of(".solana-runtime/keys/user-1.json"),
                "local-solana:transfer-authority", "transfer-v1",
                Path.of(".solana-runtime/keys/user-2.json"),
                "local-solana:transfer-destination-authority",
                "transfer-destination-v1",
                ADMIN_REDEMPTION,
                Path.of(".solana-runtime/keys/admin-redemption-owner.json"),
                "local-solana:burn-authority", "burn-v1",
                "registry-v1",
                "USD_STABLE", "USD", 1, 2,
                BigInteger.valueOf(1_000_000_000_000L), "local-solana-mint-v1",
                SavaSolanaMintChainAdapter.CommitmentLevel.CONFIRMED,
                SavaSolanaMintChainAdapter.CommitmentLevel.FINALIZED,
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000),
                Duration.ofSeconds(5));
    }
}
