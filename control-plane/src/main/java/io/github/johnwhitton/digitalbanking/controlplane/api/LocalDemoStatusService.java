package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.util.Map;
import java.math.BigInteger;
import java.util.stream.Collectors;

import io.github.johnwhitton.digitalbanking.ethereum.web3j.Web3jLocalDemoStatusReader;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresLocalDemoStatusReader;

/** Combines fixed local database and chain evidence without exposing either provider model. */
public final class LocalDemoStatusService {

    private final PostgresLocalDemoStatusReader database;
    private final Web3jLocalDemoStatusReader chain;

    public LocalDemoStatusService(
            PostgresLocalDemoStatusReader database,
            Web3jLocalDemoStatusReader chain) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.chain = java.util.Objects.requireNonNull(chain, "chain");
    }

    Status snapshot() {
        PostgresLocalDemoStatusReader.Snapshot durable = database.snapshot();
        Web3jLocalDemoStatusReader.Snapshot observed = chain.snapshot();
        if (!BigInteger.valueOf(31_337).equals(observed.chainId())) {
            throw new IllegalStateException("local demo chain identity is invalid");
        }
        return new Status(
                "LOCAL_ANVIL", observed.chainId().toString(),
                observed.blockNumber().toString(), observed.contractAddress(),
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
