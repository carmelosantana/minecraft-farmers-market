/*
 * FarmersMarket - unit tests for the integer diamond money type.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.ledger;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link Diamonds}' parsing, formatting, and arithmetic.
 *
 * <p>The parsing tests are the load-bearing ones. {@code 1e9} silently minting money is a
 * real, shipped defect in EssentialsX-family economies, caused by handing user text straight
 * to a numeric parser that happily accepts exponential notation. The rejection tests below
 * exist so that cannot happen here, and the overflow test exists because a wrapped balance is
 * a negative balance.
 */
class DiamondsTest {

    @Test
    void parsesWholeAndFractionalAmounts() {
        assertEquals(1_000L, Diamonds.parse("1").dust());
        assertEquals(1_500L, Diamonds.parse("1.5").dust());
        assertEquals(1L, Diamonds.parse("0.001").dust());
        assertEquals(64_000L, Diamonds.parse("64").dust());
    }

    @Test
    void rejectsExponentialNotationAtParse() {
        assertThrows(LedgerException.class, () -> Diamonds.parse("1e9"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("1E9"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("0x10"));
    }

    @Test
    void rejectsNegativeAndMalformedInput() {
        assertThrows(LedgerException.class, () -> Diamonds.parse("-1"));
        assertThrows(LedgerException.class, () -> Diamonds.parse(""));
        assertThrows(LedgerException.class, () -> Diamonds.parse("  "));
        assertThrows(LedgerException.class, () -> Diamonds.parse("abc"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("1.2.3"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("Infinity"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("NaN"));
    }

    @Test
    void rejectsMorePrecisionThanDustAllows() {
        assertThrows(LedgerException.class, () -> Diamonds.parse("0.0001"));
    }

    @Test
    void formatsWithoutTrailingNoise() {
        assertEquals("1", Diamonds.ofDust(1_000L).format());
        assertEquals("1.5", Diamonds.ofDust(1_500L).format());
        assertEquals("0.001", Diamonds.ofDust(1L).format());
        assertEquals("0", Diamonds.ofDust(0L).format());
    }

    @Test
    void arithmeticIsExactOverManyRandomPairs() {
        Random random = new Random(20260721L);
        for (int i = 0; i < 10_000; i++) {
            long a = random.nextLong(0, 1_000_000_000L);
            long b = random.nextLong(0, 1_000_000_000L);
            assertEquals(a + b, Diamonds.ofDust(a).plus(Diamonds.ofDust(b)).dust());
        }
    }

    @Test
    void overflowIsRefusedRatherThanWrapped() {
        Diamonds huge = Diamonds.ofDust(Long.MAX_VALUE);
        assertThrows(LedgerException.class, () -> huge.plus(Diamonds.ofDust(1L)));
    }

    // --- Beyond the brief: the same guarantees on the other entry points -----------------

    @Test
    void everyRejectionCarriesAnActionableReason() {
        assertEquals(LedgerException.Reason.MALFORMED_AMOUNT,
                assertThrows(LedgerException.class, () -> Diamonds.parse("1e9")).reason());
        assertEquals(LedgerException.Reason.MALFORMED_AMOUNT,
                assertThrows(LedgerException.class, () -> Diamonds.parse("-1")).reason());
        assertEquals(LedgerException.Reason.AMOUNT_TOO_LARGE,
                assertThrows(LedgerException.class, () -> Diamonds.parse("99999999999999999999")).reason());
        assertEquals(LedgerException.Reason.AMOUNT_TOO_LARGE,
                assertThrows(LedgerException.class,
                        () -> Diamonds.ofDust(Long.MAX_VALUE).plus(Diamonds.ofDust(1L))).reason());
    }

    @Test
    void parseRejectsNullRatherThanThrowingNullPointer() {
        assertThrows(LedgerException.class, () -> Diamonds.parse(null));
    }

    @Test
    void parseIgnoresSurroundingWhitespaceButNothingElse() {
        assertEquals(5_000L, Diamonds.parse("  5  ").dust());
        assertThrows(LedgerException.class, () -> Diamonds.parse("5 0"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("+5"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("5."));
        assertThrows(LedgerException.class, () -> Diamonds.parse(".5"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("1_000"));
        assertThrows(LedgerException.class, () -> Diamonds.parse("1,000"));
    }

    @Test
    void fractionalDigitsArePositionalNotNumeric() {
        assertEquals(1_050L, Diamonds.parse("1.05").dust());
        assertEquals(1_005L, Diamonds.parse("1.005").dust());
        assertEquals(1_500L, Diamonds.parse("1.50").dust());
        assertEquals(1_500L, Diamonds.parse("1.500").dust());
    }

    @Test
    void ofDiamondsRefusesOverflowRatherThanWrapping() {
        assertEquals(64_000L, Diamonds.ofDiamonds(64L).dust());
        assertThrows(LedgerException.class, () -> Diamonds.ofDiamonds(Long.MAX_VALUE));
    }

    @Test
    void subtractionMayGoNegativeAndSaysSo() {
        Diamonds owed = Diamonds.ofDiamonds(3).minus(Diamonds.ofDiamonds(5));

        assertEquals(-2_000L, owed.dust());
        assertTrue(owed.isNegative());
        assertFalse(Diamonds.ofDust(0L).isNegative());
        assertEquals("-2", owed.format());
    }

    @Test
    void subtractionRefusesOverflowRatherThanWrapping() {
        Diamonds mostNegative = Diamonds.ofDust(Long.MIN_VALUE);
        assertThrows(LedgerException.class, () -> mostNegative.minus(Diamonds.ofDust(1L)));
    }

    @Test
    void formatsTheExtremesWithoutOverflowing() {
        assertEquals("-9223372036854775.808", Diamonds.ofDust(Long.MIN_VALUE).format());
        assertEquals("9223372036854775.807", Diamonds.ofDust(Long.MAX_VALUE).format());
    }

    @Test
    void ordersAndComparesByDust() {
        assertTrue(Diamonds.ofDust(1L).compareTo(Diamonds.ofDust(2L)) < 0);
        assertTrue(Diamonds.ofDust(2L).compareTo(Diamonds.ofDust(1L)) > 0);
        assertEquals(0, Diamonds.ofDust(2L).compareTo(Diamonds.ofDiamonds(0).plus(Diamonds.ofDust(2L))));
        assertEquals(Diamonds.ofDust(1_000L), Diamonds.ofDiamonds(1L));
        assertEquals(Diamonds.ofDust(1_000L).hashCode(), Diamonds.ofDiamonds(1L).hashCode());
        assertNotEquals(Diamonds.ofDust(1L), Diamonds.ofDust(2L));
    }

    @Test
    void oneDiamondIsExactlyOneThousandDust() {
        assertEquals(1_000L, Diamonds.DUST_PER_DIAMOND);
    }
}
