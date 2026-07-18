package io.github.johnwhitton.digitalbanking.domain.accounting;

import java.math.BigInteger;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;

/** Exact non-negative USD value represented as integer cents. */
public record UsdCents(BigInteger value) implements Comparable<UsdCents> {

    public static final BigInteger MAX_VALUE = new BigInteger("999999999999999999");
    public static final UsdCents ZERO = new UsdCents(BigInteger.ZERO);
    private static final AssetUnit USD = new AssetUnit("USD", "CENT", 1, 2, MAX_VALUE);

    public UsdCents {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_VALUE) > 0) {
            throw new IllegalArgumentException("USD cents must be non-negative and bounded");
        }
    }

    public static UsdCents positive(BigInteger value) {
        UsdCents cents = new UsdCents(value);
        if (cents.value.signum() == 0) {
            throw new IllegalArgumentException("USD transaction amount must be positive");
        }
        return cents;
    }

    public static UsdCents parsePositive(String amount, String currency) {
        UsdCents cents = parseNonNegative(amount, currency);
        if (cents.value.signum() == 0) {
            throw new IllegalArgumentException("USD transaction amount must be positive");
        }
        return cents;
    }

    public static UsdCents parseNonNegative(String amount, String currency) {
        if (!"USD".equals(currency)) {
            throw new IllegalArgumentException("only USD is supported");
        }
        if ("0".equals(amount)) {
            return ZERO;
        }
        return new UsdCents(TokenQuantity.parse(amount, USD).atomicUnits());
    }

    public UsdCents add(UsdCents other) {
        return new UsdCents(value.add(require(other).value));
    }

    public UsdCents subtract(UsdCents other) {
        BigInteger result = value.subtract(require(other).value);
        if (result.signum() < 0) {
            throw new IllegalArgumentException("USD balance is insufficient");
        }
        return new UsdCents(result);
    }

    public String toCanonicalString() {
        if (value.signum() == 0) {
            return "0";
        }
        return TokenQuantity.ofAtomic(value, USD).toCanonicalString();
    }

    @Override
    public int compareTo(UsdCents other) {
        return value.compareTo(require(other).value);
    }

    private static UsdCents require(UsdCents value) {
        if (value == null) {
            throw new NullPointerException("USD cents");
        }
        return value;
    }
}
