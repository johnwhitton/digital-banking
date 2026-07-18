package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UsdzelleWorkflowPropertiesTest {

    @Test
    void rejectsMultipleMappingsWhileTheLocalChainAdapterIsSingleUser() {
        assertThrows(IllegalArgumentException.class, () -> new UsdzelleWorkflowProperties(
                true, "phase-6b-v1", "payout-before-burn-v1",
                "conversion-v1", "fee-v1", "finality-v1", "reconciliation-v1",
                List.of(mapping("USER_1"), mapping("USER_2"))));
    }

    private static UsdzelleWorkflowProperties.ParticipantMapping mapping(String user) {
        return new UsdzelleWorkflowProperties.ParticipantMapping(
                "local-demo", user, "BANK_1", user + "_BANK_ACCOUNT",
                "synthetic-bank:" + user + "_BANK_ACCOUNT",
                "synthetic-wallet:" + user + "_WALLET_1");
    }
}
