/*
 * FarmersMarket - unit tests for the pure commodity fill-loop clamps.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the two commodity clamps against known values and their boundary behaviour: the buy
 * allowance never goes negative and treats a negative cap as unlimited, and the pot-affordability
 * count is bounded by both the pot's balance and the quantity still on offer.
 */
class CommodityMathTest {

    @Test
    void allowanceIsCapMinusUsed() {
        assertEquals(40, CommodityMath.remainingBuyAllowance(100, 60));
        assertEquals(0, CommodityMath.remainingBuyAllowance(100, 100));
    }

    @Test
    void allowanceNeverNegative() {
        assertEquals(0, CommodityMath.remainingBuyAllowance(100, 130), "over-cap clamps to 0, not -30");
    }

    @Test
    void negativeCapMeansUnlimited() {
        assertEquals(Integer.MAX_VALUE, CommodityMath.remainingBuyAllowance(-1, 1_000_000));
    }

    @Test
    void floorFillableIsPotBalanceDividedByPrice() {
        assertEquals(5, CommodityMath.floorFillableByPot(64, 5000L, 1000L), "5000/1000 = 5 affordable");
    }

    @Test
    void floorFillableIsBoundedByRemaining() {
        assertEquals(3, CommodityMath.floorFillableByPot(3, 5000L, 1000L), "want 3, can afford 5 -> 3");
    }

    @Test
    void floorFillableIsZeroWhenPotEmptyOrNoFloor() {
        assertEquals(0, CommodityMath.floorFillableByPot(10, 0L, 1000L));
        assertEquals(0, CommodityMath.floorFillableByPot(10, 5000L, 0L), "no floor price -> 0");
        assertEquals(0, CommodityMath.floorFillableByPot(0, 5000L, 1000L));
    }
}
