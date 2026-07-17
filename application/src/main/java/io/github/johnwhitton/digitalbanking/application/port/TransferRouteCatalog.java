package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;

/** Resolves currency and an optional allowlisted route to immutable versioned asset context. */
@FunctionalInterface
public interface TransferRouteCatalog {

    Optional<Route> find(String currency, Optional<SettlementNetwork> requestedNetwork);

    record Route(
            String currency,
            SettlementNetwork settlementNetwork,
            AssetUnit assetUnit,
            String routeVersion) {

        public Route {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(settlementNetwork, "settlementNetwork");
            Objects.requireNonNull(assetUnit, "assetUnit");
            if (routeVersion == null || routeVersion.isBlank() || routeVersion.length() > 128) {
                throw new IllegalArgumentException(
                        "routeVersion must contain 1-128 characters");
            }
        }
    }
}
