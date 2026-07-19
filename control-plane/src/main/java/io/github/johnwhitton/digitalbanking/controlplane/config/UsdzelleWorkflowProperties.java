package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fail-closed local user-held workflow policy and participant mapping. */
@ConfigurationProperties("digital-banking.usdzelle-workflow")
public record UsdzelleWorkflowProperties(
        boolean enabled,
        String workflowVersion,
        String payoutPolicyVersion,
        String conversionPolicyVersion,
        String feePolicyVersion,
        String finalityPolicyVersion,
        String reconciliationPolicyVersion,
        List<ParticipantMapping> participants) {

    public UsdzelleWorkflowProperties {
        require(workflowVersion, "workflowVersion");
        require(payoutPolicyVersion, "payoutPolicyVersion");
        require(conversionPolicyVersion, "conversionPolicyVersion");
        require(feePolicyVersion, "feePolicyVersion");
        require(finalityPolicyVersion, "finalityPolicyVersion");
        require(reconciliationPolicyVersion, "reconciliationPolicyVersion");
        participants = participants == null ? List.of() : List.copyOf(participants);
        if (!enabled || !"payout-before-burn-v1".equals(payoutPolicyVersion)
                || participants.isEmpty()
                || participants.stream().map(mapping ->
                        mapping.tenantId() + ':' + mapping.participantId())
                    .distinct().count() != participants.size()
                || participants.stream().map(ParticipantMapping::bankAccountReference)
                    .distinct().count() != participants.size()
                || participants.stream().map(ParticipantMapping::userWalletReference)
                    .distinct().count() != participants.size()) {
            throw new IllegalArgumentException(
                    "local USDZELLE workflow policy and unique mappings are required");
        }
    }

    public record ParticipantMapping(
            String tenantId,
            String participantId,
            String bankId,
            String bankAccountId,
            String bankAccountReference,
            String userWalletReference) {
        public ParticipantMapping {
            require(tenantId, "tenantId");
            require(participantId, "participantId");
            require(bankId, "bankId");
            require(bankAccountId, "bankAccountId");
            require(bankAccountReference, "bankAccountReference");
            require(userWalletReference, "userWalletReference");
            if (!bankAccountReference.equals("synthetic-bank:" + bankAccountId)
                    || !userWalletReference.startsWith("synthetic-wallet:")) {
                throw new IllegalArgumentException(
                        "workflow participant mapping is inconsistent");
            }
        }
    }

    private static void require(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
