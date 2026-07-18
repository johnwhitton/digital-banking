package io.github.johnwhitton.digitalbanking.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class LocalEthereumPropertiesTest {

    @Test
    void acceptsOnlyUncredentialedLoopbackAnvilEndpoint() {
        LocalEthereumProperties properties = properties("http://127.0.0.1:8545", 31_337L);
        assertEquals("http://127.0.0.1:8545", properties.rpcUrl());

        assertThrows(IllegalArgumentException.class,
                () -> properties("https://ethereum.example/rpc", 31_337L));
        assertThrows(IllegalArgumentException.class,
                () -> properties("http://user:secret@127.0.0.1:8545", 31_337L));
        assertThrows(IllegalArgumentException.class,
                () -> properties("http://127.0.0.1:8545", 1L));

        LocalEthereumProperties transferOnly = new LocalEthereumProperties(
                "http://localhost:8545", 31_337L,
                "0x1111111111111111111111111111111111111111", "",
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(180_000),
                1, "USD_STABLE", "USD", 1, 2,
                BigInteger.valueOf(1_000_000_000_000L), "local-policy-v1",
                "USER_WALLET_2");
        assertThrows(IllegalArgumentException.class,
                transferOnly::requiredMintRecipientAddress);
    }

    private static LocalEthereumProperties properties(String url, long chainId) {
        return new LocalEthereumProperties(
                url, chainId,
                "0x1111111111111111111111111111111111111111",
                "0x2222222222222222222222222222222222222222",
                BigInteger.valueOf(1_000_000_000L),
                BigInteger.valueOf(2_000_000_000L), BigInteger.valueOf(180_000),
                1, "USD_STABLE", "USD", 1, 2,
                BigInteger.valueOf(1_000_000_000_000L), "local-ethereum-mint-v1",
                "USER_WALLET_2");
    }
}
