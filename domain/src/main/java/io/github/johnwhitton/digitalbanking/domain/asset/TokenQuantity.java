package io.github.johnwhitton.digitalbanking.domain.asset;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Positive exact quantity represented only as integer atomic units.
 */
public record TokenQuantity(BigInteger atomicUnits, AssetUnit unit)
        implements Comparable<TokenQuantity> {

    private static final Pattern DECIMAL = Pattern.compile("(0|[1-9][0-9]*)(?:\\.([0-9]+))?");
    private static final int MAX_CANONICAL_LENGTH = 512;

    public TokenQuantity {
        Objects.requireNonNull(atomicUnits, "atomicUnits");
        Objects.requireNonNull(unit, "unit");
        if (atomicUnits.signum() <= 0) {
            throw new IllegalArgumentException("token quantity must be positive");
        }
        if (atomicUnits.compareTo(unit.maxAtomicUnits()) > 0) {
            throw new IllegalArgumentException("token quantity exceeds the configured maximum");
        }
    }

    public static TokenQuantity parse(String canonical, AssetUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (canonical == null || canonical.length() > MAX_CANONICAL_LENGTH) {
            throw new IllegalArgumentException("token quantity is required and size-bounded");
        }
        Matcher matcher = DECIMAL.matcher(canonical);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("token quantity is not canonical base-10 text");
        }

        String fractional = matcher.group(2);
        if (fractional != null) {
            if (fractional.length() > unit.scale()) {
                throw new IllegalArgumentException("token quantity has excess precision");
            }
            if (fractional.charAt(fractional.length() - 1) == '0') {
                throw new IllegalArgumentException("token quantity has a non-canonical trailing zero");
            }
        }

        BigInteger factor = BigInteger.TEN.pow(unit.scale());
        BigInteger whole = new BigInteger(matcher.group(1)).multiply(factor);
        BigInteger fraction = BigInteger.ZERO;
        if (fractional != null) {
            fraction = new BigInteger(fractional)
                    .multiply(BigInteger.TEN.pow(unit.scale() - fractional.length()));
        }
        return ofAtomic(whole.add(fraction), unit);
    }

    public static TokenQuantity ofAtomic(BigInteger atomicUnits, AssetUnit unit) {
        return new TokenQuantity(atomicUnits, unit);
    }

    public TokenQuantity add(TokenQuantity other) {
        requireSameUnit(other);
        return ofAtomic(atomicUnits.add(other.atomicUnits), unit);
    }

    @Override
    public int compareTo(TokenQuantity other) {
        requireSameUnit(other);
        return atomicUnits.compareTo(other.atomicUnits);
    }

    public String toCanonicalString() {
        BigInteger factor = BigInteger.TEN.pow(unit.scale());
        BigInteger[] parts = atomicUnits.divideAndRemainder(factor);
        if (parts[1].signum() == 0) {
            return parts[0].toString();
        }

        String rawFraction = parts[1].toString();
        String fraction = "0".repeat(unit.scale() - rawFraction.length()) + rawFraction;
        int end = fraction.length();
        while (fraction.charAt(end - 1) == '0') {
            end--;
        }
        return parts[0] + "." + fraction.substring(0, end);
    }

    private void requireSameUnit(TokenQuantity other) {
        Objects.requireNonNull(other, "other");
        if (!unit.equals(other.unit)) {
            throw new IllegalArgumentException("token quantities use different unit definitions");
        }
    }
}
