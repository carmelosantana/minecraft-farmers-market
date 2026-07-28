/*
 * FarmersMarket - unit tests for the conserving integer fee and sales-tax arithmetic.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import org.junit.jupiter.api.Test;
import org.xpfarm.farmersmarket.ledger.Diamonds;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the market's money math against known values and, above all, against its own conservation.
 *
 * <p>The single most important test here is the randomized property one: across twenty thousand
 * seeded sales at arbitrary prices, tax rates, and burn shares, {@code gross == net + tax} and
 * {@code tax == burned + pot} must hold to the exact dust. That is not a rounding coincidence --
 * {@code net} is <em>defined</em> as {@code gross - tax} and {@code pot} as {@code tax - burned},
 * so the remainder always lands somewhere and money is neither invented nor destroyed.
 */
class MarketMathTest {

    private static final int XP_PER_DIAMOND = 40;

    @Test
    void listingFeeIsOnePercentPricedInXp() {
        // 100 diamonds, 1% fee => 1 diamond-equivalent => 40 XP points.
        assertEquals(40, MarketMath.listingFeeXp(Diamonds.ofDiamonds(100), 1.0, XP_PER_DIAMOND));
    }

    @Test
    void aTinyListingStillCostsAtLeastOneXp() {
        // Rounds up: any listing costs something, or the anti-spam fee does nothing.
        assertEquals(1, MarketMath.listingFeeXp(Diamonds.ofDiamonds(1), 1.0, XP_PER_DIAMOND));
    }

    @Test
    void aZeroPercentFeeIsFree() {
        assertEquals(0, MarketMath.listingFeeXp(Diamonds.ofDiamonds(1000), 0.0, XP_PER_DIAMOND));
    }

    @Test
    void taxSplitsSevenPercentHalfBurnedHalfToPot() {
        MarketMath.TaxSplit split = MarketMath.taxOnSale(Diamonds.ofDiamonds(100), 7.0, 0.5);
        assertEquals(7_000L, split.tax().dust());       // 7% of 100 diamonds = 7 diamonds
        assertEquals(93_000L, split.net().dust());      // seller keeps 93
        assertEquals(3_500L, split.burned().dust());    // half of tax burned
        assertEquals(3_500L, split.toPot().dust());     // half to the pot
    }

    @Test
    void grossAlwaysEqualsNetPlusTaxAndTaxAlwaysEqualsBurnedPlusPot() {
        Random r = new Random(20260728L);
        for (int i = 0; i < 20_000; i++) {
            long grossDust = r.nextLong(0, 1_000_000_000L);
            double taxPct = r.nextDouble(0.0, 100.0);
            double burnShare = r.nextDouble(0.0, 1.0);
            MarketMath.TaxSplit s = MarketMath.taxOnSale(Diamonds.ofDust(grossDust), taxPct, burnShare);
            assertEquals(grossDust, s.net().dust() + s.tax().dust(),
                    "gross must equal net + tax, or the sale invented or destroyed money");
            assertEquals(s.tax().dust(), s.burned().dust() + s.toPot().dust(),
                    "tax must equal burned + pot");
            assertTrue(s.net().dust() >= 0 && s.tax().dust() >= 0
                    && s.burned().dust() >= 0 && s.toPot().dust() >= 0,
                    "no component of a sale may be negative");
        }
    }

    @Test
    void noComponentEverExceedsTheGross() {
        MarketMath.TaxSplit s = MarketMath.taxOnSale(Diamonds.ofDiamonds(50), 100.0, 1.0);
        assertTrue(s.tax().dust() <= s.gross().dust());
        assertEquals(0L, s.net().dust());  // a 100% tax leaves the seller nothing, but never less
    }
}
