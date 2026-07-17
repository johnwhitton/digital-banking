package io.github.johnwhitton.digitalbanking.domain.asset;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenQuantityTest {

    private static final AssetUnit USDC = new AssetUnit(
            "USDC", "USD", 7, 6, new BigInteger("1000000000000"));

    @Test
    void parsesAndSerializesCanonicalExactQuantities() {
        assertEquals(new BigInteger("12345600"), TokenQuantity.parse("12.3456", USDC).atomicUnits());

        for (String value : List.of("0.000001", "1", "1.2", "999999.999999")) {
            TokenQuantity quantity = TokenQuantity.parse(value, USDC);
            assertEquals(value, quantity.toCanonicalString());
            assertEquals(quantity, TokenQuantity.ofAtomic(quantity.atomicUnits(), USDC));
        }
    }

    @Test
    void rejectsZeroNegativeExcessPrecisionAndNonCanonicalText() {
        for (String invalid : List.of(
                "0", "0.0", "-1", "+1", "01", "1.0", "1.230", "1e3", "1E3",
                ".1", "1.", " 1", "1 ", "0.0000001")) {
            assertThrows(IllegalArgumentException.class, () -> TokenQuantity.parse(invalid, USDC), invalid);
        }
    }

    @Test
    void enforcesMaximumAndOverflow() {
        AssetUnit bounded = new AssetUnit("TEST", "WHOLE", 1, 2, new BigInteger("10000"));

        assertEquals("100", TokenQuantity.parse("100", bounded).toCanonicalString());
        assertThrows(IllegalArgumentException.class, () -> TokenQuantity.parse("100.01", bounded));
        assertThrows(IllegalArgumentException.class,
                () -> TokenQuantity.ofAtomic(new BigInteger("10001"), bounded));
        assertThrows(IllegalArgumentException.class,
                () -> TokenQuantity.ofAtomic(BigInteger.ZERO, bounded));
    }

    @Test
    void rejectsArithmeticAcrossUnitVersions() {
        TokenQuantity versionSeven = TokenQuantity.parse("1", USDC);
        AssetUnit versionEight = new AssetUnit(
                "USDC", "USD", 8, 6, new BigInteger("1000000000000"));
        TokenQuantity otherVersion = TokenQuantity.parse("1", versionEight);

        assertThrows(IllegalArgumentException.class, () -> versionSeven.add(otherVersion));
        assertThrows(IllegalArgumentException.class, () -> versionSeven.compareTo(otherVersion));
    }

    @Test
    void rejectsInvalidUnitDefinitions() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUnit("", "USD", 1, 6, BigInteger.TEN));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUnit("USDC", "USD", 0, 6, BigInteger.TEN));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUnit("USDC", "USD", 1, -1, BigInteger.TEN));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUnit("USDC", "USD", 1, 6, BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUnit("US DC", "USD", 1, 6, BigInteger.TEN));
    }

    @Test
    void boundsCanonicalInputBeforeNumericConversion() {
        AssetUnit large = new AssetUnit("TEST", "LARGE", 1, 0, BigInteger.TEN.pow(600));

        assertThrows(IllegalArgumentException.class,
                () -> TokenQuantity.parse("1".repeat(513), large));
    }
}
