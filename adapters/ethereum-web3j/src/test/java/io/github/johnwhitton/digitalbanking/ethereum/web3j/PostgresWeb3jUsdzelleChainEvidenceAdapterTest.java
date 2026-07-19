package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PostgresWeb3jUsdzelleChainEvidenceAdapterTest {

    @Test
    void acceptsOnlyTheUncredentialedLocalAnvilEndpoints() {
        try (PostgresWeb3jUsdzelleChainEvidenceAdapter ignored = adapter(
                "http://anvil:8545", true)) {
            // Construction proves that the private Compose endpoint is accepted.
        }

        assertThrows(IllegalArgumentException.class,
                () -> adapter("http://anvil:8545", false));
        assertThrows(IllegalArgumentException.class,
                () -> adapter("http://ethereum.example:8545", true));
        assertThrows(IllegalArgumentException.class,
                () -> adapter("http://user:secret@anvil:8545", true));
    }

    private static PostgresWeb3jUsdzelleChainEvidenceAdapter adapter(
            String rpcUrl, boolean composeEnvironment) {
        return new PostgresWeb3jUsdzelleChainEvidenceAdapter(
                new DriverManagerDataSource(), rpcUrl, composeEnvironment,
                new PostgresWeb3jUsdzelleChainEvidenceAdapter.Configuration(
                        "0x1111111111111111111111111111111111111111",
                        "0x2222222222222222222222222222222222222222",
                        "0x3333333333333333333333333333333333333333",
                        "USER_WALLET_1", "registry-v1:key-v1",
                        "ADMIN", "registry-v1:key-v1"),
                Clock.systemUTC());
    }
}
