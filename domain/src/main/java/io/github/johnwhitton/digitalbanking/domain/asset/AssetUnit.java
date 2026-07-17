package io.github.johnwhitton.digitalbanking.domain.asset;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned definition of the atomic unit used by an asset.
 */
public record AssetUnit(
        String assetId,
        String unitId,
        int version,
        int scale,
        BigInteger maxAtomicUnits) {

    private static final int MAX_SUPPORTED_SCALE = 255;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    public AssetUnit {
        assetId = requireText(assetId, "assetId");
        unitId = requireText(unitId, "unitId");
        if (version <= 0) {
            throw new IllegalArgumentException("unit version must be positive");
        }
        if (scale < 0 || scale > MAX_SUPPORTED_SCALE) {
            throw new IllegalArgumentException("unit scale is outside the supported range");
        }
        Objects.requireNonNull(maxAtomicUnits, "maxAtomicUnits");
        if (maxAtomicUnits.signum() <= 0) {
            throw new IllegalArgumentException("maximum atomic units must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a safe bounded identifier");
        }
        return value;
    }
}
