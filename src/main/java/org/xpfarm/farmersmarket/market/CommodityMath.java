/*
 * FarmersMarket - the pure buy-limit and pot-affordability clamps for the commodity fill loop.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

/** Pure clamps used by the commodity fill loop. No Bukkit, no I/O; every value is a plain count. */
public final class CommodityMath {
    private CommodityMath() {
    }

    /**
     * How many more units {@code cap} allows a buyer who has already bought {@code usedInWindow}.
     * A negative {@code cap} means "no limit configured" and returns {@link Integer#MAX_VALUE}.
     */
    public static int remainingBuyAllowance(int cap, int usedInWindow) {
        if (cap < 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, cap - usedInWindow);
    }

    /**
     * How many units the pot can buy at {@code floorPriceDust} each, bounded by {@code remainingQty}.
     * Returns 0 when there is no floor price, an empty pot, or nothing left to sell.
     */
    public static int floorFillableByPot(int remainingQty, long potBalanceDust, long floorPriceDust) {
        if (remainingQty <= 0 || potBalanceDust <= 0 || floorPriceDust <= 0) {
            return 0;
        }
        long affordable = potBalanceDust / floorPriceDust;
        return (int) Math.min((long) remainingQty, affordable);
    }
}
