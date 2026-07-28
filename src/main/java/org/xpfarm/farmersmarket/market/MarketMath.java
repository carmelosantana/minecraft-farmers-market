/*
 * FarmersMarket - the conserving, integer-only fee and sales-tax arithmetic.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import org.xpfarm.farmersmarket.ledger.Diamonds;

/**
 * The market's fee and tax math: the XP cost of putting up a listing, and the split of a gross
 * sale price into the seller's net, the burned tax, and the community pot.
 *
 * <p><b>Its whole job is to conserve.</b> For any sale, {@code gross == net + tax} and
 * {@code tax == burned + toPot}, exactly and to the dust -- not because the rounding happens to
 * line up, but because {@code net} is <em>defined</em> as {@code gross - tax} and {@code toPot}
 * as {@code tax - burned}. The remainder therefore always lands somewhere, and money is neither
 * invented nor destroyed. Task 2's {@code trades} table then re-verifies exactly this with its
 * own {@code CHECK} constraints at the database.
 *
 * <p>Money is integer-only here, just as in {@link Diamonds}: no {@code double}, {@code float},
 * or {@code BigDecimal} ever holds or computes a dust amount. The only floating-point value in
 * this class is a config-supplied <em>rate</em> -- a fee percent, a tax percent, a burn share --
 * which is turned into an integer basis-point count once, at the top of each method, and never
 * touches a dust amount thereafter. Every subsequent step is {@code long} arithmetic, and any
 * step that could overflow goes through {@link Math#multiplyExact} or {@link Math#toIntExact} so
 * it is refused rather than silently wrapped into a negative.
 */
public final class MarketMath {

    private MarketMath() {
    }

    /**
     * A percent such as {@code 7.0} as basis points of {@code 10_000} (so {@code 700}).
     *
     * <p>Converts a config <em>rate</em>, never a balance. This is the single point where a
     * {@code double} is allowed near the math, and it produces a plain integer count that all
     * downstream dust arithmetic uses.
     *
     * @param percent the rate as a percentage, such as {@code 7.0} for seven percent
     * @return the rate in basis points of ten thousand
     */
    static long basisPoints(double percent) {
        return Math.round(percent * 100.0);
    }

    /**
     * A share such as {@code 0.5} as basis points of {@code 10_000} (so {@code 5000}).
     *
     * @param share the rate as a fraction of one, such as {@code 0.5} for half
     * @return the share in basis points of ten thousand
     */
    static long shareBasisPoints(double share) {
        return Math.round(share * 10_000.0);
    }

    /**
     * The XP listing fee for a listing priced at {@code price}, rounded up so any non-free
     * listing costs at least one point.
     *
     * <p>The ceiling is deliberate: a fee that rounded down to zero for small listings would be
     * no anti-spam fee at all. XP is not money in the {@link Diamonds} sense -- it is a count of
     * whole experience points -- so this returns an {@code int}, and an overflow of that
     * {@code int} is refused via {@link Math#toIntExact} rather than wrapped.
     *
     * @param price            the listing's asking price
     * @param listingFeePercent the fee rate, as a config-supplied percentage
     * @param xpPerDiamond     how many XP points one diamond of price is worth for fee purposes
     * @return the fee in whole XP points; {@code 0} only when the rate or price is zero, else at
     *         least {@code 1}
     * @throws ArithmeticException if the fee does not fit in an {@code int}
     */
    public static int listingFeeXp(Diamonds price, double listingFeePercent, int xpPerDiamond) {
        long bp = basisPoints(listingFeePercent);
        long numerator = Math.multiplyExact(Math.multiplyExact(price.dust(), bp), (long) xpPerDiamond);
        long denom = 10_000L * Diamonds.DUST_PER_DIAMOND;
        return Math.toIntExact(ceilDiv(numerator, denom));
    }

    /**
     * A gross sale price split into its conserved parts.
     *
     * @param gross  the buyer's total payment
     * @param net    what the seller keeps, defined as {@code gross - tax}
     * @param tax    the total tax taken from the sale
     * @param burned the portion of the tax destroyed
     * @param toPot  the portion of the tax paid into the community pot, defined as
     *               {@code tax - burned}
     */
    public record TaxSplit(Diamonds gross, Diamonds net, Diamonds tax, Diamonds burned, Diamonds toPot) {
    }

    /**
     * Splits a gross sale price into the seller's net, the burned tax, and the pot tax.
     *
     * <p>Conserves to the dust: {@code net = gross - tax} and {@code toPot = tax - burned}, so the
     * three parts always re-sum to the whole regardless of how the tax and burn divisions
     * rounded. The tax is floored (its division truncates), which means any lost dust stays with
     * the seller rather than being invented; the pot then takes whatever the burn floor left
     * behind. Both multiplications are checked so an absurd price is refused rather than wrapped.
     *
     * @param gross          the buyer's total payment; must be non-negative for the split to be
     *                       non-negative
     * @param salesTaxPercent the tax rate, as a config-supplied percentage
     * @param taxBurnShare   the fraction of the tax to burn, as a config-supplied share of one
     * @return the conserved split
     * @throws ArithmeticException if a division's product does not fit in a {@code long}
     */
    public static TaxSplit taxOnSale(Diamonds gross, double salesTaxPercent, double taxBurnShare) {
        long taxBp = basisPoints(salesTaxPercent);
        long taxDust = Math.multiplyExact(gross.dust(), taxBp) / 10_000L;
        long netDust = gross.dust() - taxDust;
        long burnedDust = Math.multiplyExact(taxDust, shareBasisPoints(taxBurnShare)) / 10_000L;
        long potDust = taxDust - burnedDust;
        return new TaxSplit(gross, Diamonds.ofDust(netDust), Diamonds.ofDust(taxDust),
                Diamonds.ofDust(burnedDust), Diamonds.ofDust(potDust));
    }

    /**
     * Integer division of {@code a} by {@code b} rounding toward positive infinity.
     *
     * <p>Expressed as {@code -floorDiv(-a, b)} so it stays exact for the whole {@code long} range
     * rather than overflowing the way {@code (a + b - 1) / b} does near {@link Long#MAX_VALUE}.
     */
    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
