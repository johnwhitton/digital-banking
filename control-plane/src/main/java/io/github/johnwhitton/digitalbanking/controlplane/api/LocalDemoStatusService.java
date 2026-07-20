package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.util.Map;
import java.math.BigInteger;
import java.util.stream.Collectors;

import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresLocalDemoStatusReader;

/** Combines fixed local database and chain evidence without exposing either provider model. */
public final class LocalDemoStatusService {

    private final PostgresLocalDemoStatusReader database;
    private final LocalDemoChainStatusReader chain;

    public LocalDemoStatusService(
            PostgresLocalDemoStatusReader database,
            LocalDemoChainStatusReader chain) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.chain = java.util.Objects.requireNonNull(chain, "chain");
    }

    Status snapshot() {
        PostgresLocalDemoStatusReader.Snapshot durable = database.snapshot();
        LocalDemoChainStatusReader.Snapshot observed = chain.snapshot();
        boolean ethereum = "LOCAL_ANVIL".equals(observed.network());
        return new Status(
                observed.network(), observed.networkIdentity(),
                observed.observationHeight(), observed.assetReference(),
                ethereum ? observed.networkIdentity() : null,
                ethereum ? observed.observationHeight() : null,
                ethereum ? observed.assetReference() : null,
                strings(durable.bankBalancesCents()),
                strings(durable.ledgerBalancesCents()),
                strings(durable.operationalPositionsCents()),
                new TokenBalances(
                        observed.userOneBalanceAtomic().toString(),
                        observed.userTwoBalanceAtomic().toString(),
                        observed.adminBalanceAtomic().toString(),
                        observed.totalSupplyAtomic().toString()),
                durable.confirmedEffects(),
                durable.latestAcquisition().orElse(null),
                durable.latestRedemption().orElse(null),
                durable.latestSettlement().orElse(null),
                durable.payoutBeforeBurn().orElse(null));
    }

    private static Map<String, String> strings(Map<String, BigInteger> values) {
        return values.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().toString()));
    }

    public record Status(
            String network,
            String networkIdentity,
            String observationHeight,
            String assetReference,
            String chainId,
            String blockNumber,
            String contractAddress,
            Map<String, String> bankBalancesCents,
            Map<String, String> ledgerBalancesCents,
            Map<String, String> operationalPositionsCents,
            TokenBalances tokenBalancesAtomic,
            PostgresLocalDemoStatusReader.EffectCounts confirmedEffects,
            PostgresLocalDemoStatusReader.ParentStatus latestAcquisition,
            PostgresLocalDemoStatusReader.ParentStatus latestRedemption,
            PostgresLocalDemoStatusReader.ParentStatus latestSettlement,
            Boolean payoutBeforeBurn) {
    }

    public record TokenBalances(
            String userOne,
            String userTwo,
            String admin,
            String totalSupply) {
    }
}
