package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;

/** Resolves server-owned versioned asset/unit definitions. */
@FunctionalInterface
public interface AssetUnitCatalog {

    Optional<AssetUnit> find(String assetId, String unitId, int version);
}
