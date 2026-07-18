package io.github.johnwhitton.digitalbanking.domain.accounting;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsdCentsTest {

    @Test
    void parsesExactUsdAndRejectsFractionUnsupportedCurrencyAndBounds() {
        assertEquals(new BigInteger("10000"),
                UsdCents.parsePositive("100", "USD").value());
        assertEquals("100", new UsdCents(new BigInteger("10000")).toCanonicalString());
        assertEquals("0", UsdCents.ZERO.toCanonicalString());
        assertEquals(UsdCents.ZERO, UsdCents.parseNonNegative("0", "USD"));

        assertThrows(IllegalArgumentException.class,
                () -> UsdCents.parsePositive("0.001", "USD"));
        assertThrows(IllegalArgumentException.class,
                () -> UsdCents.parsePositive("0", "USD"));
        assertThrows(IllegalArgumentException.class,
                () -> UsdCents.parsePositive("1", "EUR"));
        assertThrows(IllegalArgumentException.class,
                () -> new UsdCents(UsdCents.MAX_VALUE.add(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class,
                () -> new UsdCents(BigInteger.ONE).subtract(new UsdCents(BigInteger.TWO)));
    }
}
