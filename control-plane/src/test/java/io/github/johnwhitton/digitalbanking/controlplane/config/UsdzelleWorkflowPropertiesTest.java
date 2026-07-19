package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsdzelleWorkflowPropertiesTest {

    @Test
    void acceptsDistinctMappingsAndRejectsDuplicateAuthorityRoutes() {
        assertDoesNotThrow(() -> properties(List.of(
                mapping("USER_1"), mapping("USER_2"))));
        assertThrows(IllegalArgumentException.class, () -> properties(List.of(
                mapping("USER_1"), mapping("USER_1"))));
        assertThrows(IllegalArgumentException.class, () -> properties(List.of(
                mapping("USER_1"), new UsdzelleWorkflowProperties.ParticipantMapping(
                        "local-demo", "USER_2", "BANK_2", "USER_2_BANK_ACCOUNT",
                        "synthetic-bank:USER_1_BANK_ACCOUNT",
                        "synthetic-wallet:USER_2_WALLET_1"))));
        assertThrows(IllegalArgumentException.class, () -> properties(List.of(
                mapping("USER_1"), new UsdzelleWorkflowProperties.ParticipantMapping(
                        "local-demo", "USER_2", "BANK_2", "USER_2_BANK_ACCOUNT",
                        "synthetic-bank:USER_2_BANK_ACCOUNT",
                        "synthetic-wallet:USER_1_WALLET_1"))));
    }

    private static UsdzelleWorkflowProperties properties(
            List<UsdzelleWorkflowProperties.ParticipantMapping> mappings) {
        return new UsdzelleWorkflowProperties(
                true, "phase-6b-v1", "payout-before-burn-v1",
                "conversion-v1", "fee-v1", "finality-v1", "reconciliation-v1",
                mappings);
    }

    private static UsdzelleWorkflowProperties.ParticipantMapping mapping(String user) {
        return new UsdzelleWorkflowProperties.ParticipantMapping(
                "local-demo", user, "BANK_1", user + "_BANK_ACCOUNT",
                "synthetic-bank:" + user + "_BANK_ACCOUNT",
                "synthetic-wallet:" + user + "_WALLET_1");
    }
}
