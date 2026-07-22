/*
 * FarmersMarket - unit tests for the vanilla experience level and points conversion.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins both of vanilla's piecewise experience formulas against known values.
 *
 * <p>The cumulative-total formula and the cost-of-next-level formula break at
 * <em>different</em> levels -- 16/17 and 31/32 for the totals, 15/16 and 30/31 for the costs.
 * That is not a typo, and confusing the two is the classic experience-math bug, so both
 * tables are pinned here independently rather than one being derived from the other.
 */
class ExperienceMathTest {

    @Test
    void totalPointsMatchesVanillaAtKnownLevels() {
        assertEquals(0, ExperienceMath.totalPoints(0, 0f));
        assertEquals(7, ExperienceMath.totalPoints(1, 0f));
        assertEquals(315, ExperienceMath.totalPoints(15, 0f));
        assertEquals(352, ExperienceMath.totalPoints(16, 0f));
        assertEquals(394, ExperienceMath.totalPoints(17, 0f));
        assertEquals(1395, ExperienceMath.totalPoints(30, 0f));
        assertEquals(1507, ExperienceMath.totalPoints(31, 0f));
        assertEquals(1628, ExperienceMath.totalPoints(32, 0f));
    }

    @Test
    void roundTripsThroughLevelForTotal() {
        for (int level = 0; level <= 200; level++) {
            assertEquals(level, ExperienceMath.levelForTotal(ExperienceMath.totalPoints(level, 0f)));
        }
    }

    @Test
    void eachLevelCostsTheVanillaAmount() {
        assertEquals(37, ExperienceMath.totalPoints(16, 0f) - ExperienceMath.totalPoints(15, 0f));
        assertEquals(42, ExperienceMath.totalPoints(17, 0f) - ExperienceMath.totalPoints(16, 0f));
        assertEquals(121, ExperienceMath.totalPoints(32, 0f) - ExperienceMath.totalPoints(31, 0f));
    }

    @Test
    void progressWithinALevelAddsProportionally() {
        int atLevel16 = ExperienceMath.totalPoints(16, 0f);
        int halfway = ExperienceMath.totalPoints(16, 0.5f);
        assertTrue(halfway > atLevel16 && halfway < ExperienceMath.totalPoints(17, 0f));
    }

    // --- Beyond the brief -----------------------------------------------------------------

    /**
     * The two formulas' breakpoints differ by one level. Pinning the cost table directly, rather
     * than only as differences of totals, is what catches a "fix" that aligns them.
     */
    @Test
    void costOfNextLevelUsesItsOwnBreakpointsNotTheTotalsBreakpoints() {
        assertEquals(7, ExperienceMath.costOfNextLevel(0));
        assertEquals(35, ExperienceMath.costOfNextLevel(14));
        assertEquals(37, ExperienceMath.costOfNextLevel(15));
        assertEquals(42, ExperienceMath.costOfNextLevel(16));
        assertEquals(47, ExperienceMath.costOfNextLevel(17));
        assertEquals(112, ExperienceMath.costOfNextLevel(30));
        assertEquals(121, ExperienceMath.costOfNextLevel(31));
        assertEquals(130, ExperienceMath.costOfNextLevel(32));
    }

    @Test
    void everyAdjacentPairOfTotalsDiffersByExactlyTheAdvertisedCost() {
        for (int level = 0; level < 500; level++) {
            assertEquals(ExperienceMath.costOfNextLevel(level),
                    ExperienceMath.totalPoints(level + 1, 0f) - ExperienceMath.totalPoints(level, 0f),
                    "cost mismatch at level " + level);
        }
    }

    @Test
    void progressIsRoundedAgainstTheCostOfTheLevelBeingWorkedThrough() {
        assertEquals(352 + 21, ExperienceMath.totalPoints(16, 0.5f));
        assertEquals(352 + 42, ExperienceMath.totalPoints(16, 1f));
        assertEquals(315 + 19, ExperienceMath.totalPoints(15, 0.5f));
    }

    @Test
    void levelForTotalFloorsPartialProgressToTheLevelBelow() {
        assertEquals(0, ExperienceMath.levelForTotal(0));
        assertEquals(0, ExperienceMath.levelForTotal(6));
        assertEquals(1, ExperienceMath.levelForTotal(7));
        assertEquals(15, ExperienceMath.levelForTotal(351));
        assertEquals(16, ExperienceMath.levelForTotal(352));
        assertEquals(31, ExperienceMath.levelForTotal(1627));
        assertEquals(32, ExperienceMath.levelForTotal(1628));
    }

    @Test
    void levelForTotalStaysExactAcrossTheWholeIntRange() {
        for (int level = 0; level <= 5_000; level++) {
            int total = ExperienceMath.totalPoints(level, 0f);
            assertEquals(level, ExperienceMath.levelForTotal(total), "at level " + level);
            if (level > 0) {
                assertEquals(level - 1, ExperienceMath.levelForTotal(total - 1), "just below level " + level);
            }
        }
    }

    @Test
    void refusesInputThatCannotDescribeAPlayer() {
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.totalPoints(-1, 0f));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.totalPoints(0, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.totalPoints(0, 1.5f));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.totalPoints(0, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ExperienceMath.totalPoints(0, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.levelForTotal(-1));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.costOfNextLevel(-1));
    }

    @Test
    void refusesLevelsWhoseTotalWouldNotFitAnInt() {
        assertThrows(ArithmeticException.class, () -> ExperienceMath.totalPoints(1_000_000, 0f));
    }
}
