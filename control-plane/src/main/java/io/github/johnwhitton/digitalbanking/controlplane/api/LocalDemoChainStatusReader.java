package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.math.BigInteger;

/** Provider-neutral, bounded chain evidence for the local-only demo status view. */
@FunctionalInterface
public interface LocalDemoChainStatusReader {

    Snapshot snapshot();

    record Snapshot(
            String network,
            String networkIdentity,
            String observationHeight,
            String assetReference,
            BigInteger userOneBalanceAtomic,
            BigInteger userTwoBalanceAtomic,
            BigInteger adminBalanceAtomic,
            BigInteger totalSupplyAtomic) {
        public Snapshot {
            java.util.Objects.requireNonNull(network, "network");
            java.util.Objects.requireNonNull(networkIdentity, "networkIdentity");
            java.util.Objects.requireNonNull(observationHeight, "observationHeight");
            java.util.Objects.requireNonNull(assetReference, "assetReference");
            java.util.Objects.requireNonNull(userOneBalanceAtomic, "userOneBalanceAtomic");
            java.util.Objects.requireNonNull(userTwoBalanceAtomic, "userTwoBalanceAtomic");
            java.util.Objects.requireNonNull(adminBalanceAtomic, "adminBalanceAtomic");
            java.util.Objects.requireNonNull(totalSupplyAtomic, "totalSupplyAtomic");
        }
    }
}
