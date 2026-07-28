/*
 * FarmersMarket - immutable snapshot of one row of the append-only trades log.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code trades}, a single immutable entry in the market's audit trail.
 *
 * <p>The money fields conserve by construction and are held to it by the table's own CHECKs:
 * {@code grossDust == netDust + taxDust} and {@code taxDust == taxBurnedDust + taxPotDust}. A
 * row that does not balance invented or destroyed money and the database refuses to store it.
 *
 * @param id                 the row id; {@code 0} for a not-yet-inserted row, assigned by SQLite
 * @param happenedAtEpochMs  when the trade cleared, epoch milliseconds
 * @param buyer              the player who paid
 * @param seller             the player who was paid; may be a {@link SystemAccounts} account
 * @param itemClass          how the traded item was classified
 * @param itemKey            the stable identity key the item was grouped under
 * @param materialKey        the underlying material
 * @param amount             the stack size traded
 * @param grossDust          the total the buyer paid, in dust
 * @param taxDust            the portion taken as tax, in dust
 * @param taxBurnedDust      the portion of the tax destroyed, in dust
 * @param taxPotDust         the portion of the tax paid into the community pot, in dust
 * @param netDust            the portion the seller received, in dust
 * @param listingId          the {@code listings} row this trade settled, or {@code null} if none
 */
public record TradeRow(
        long id,
        long happenedAtEpochMs,
        UUID buyer,
        UUID seller,
        ItemClass itemClass,
        String itemKey,
        String materialKey,
        int amount,
        long grossDust,
        long taxDust,
        long taxBurnedDust,
        long taxPotDust,
        long netDust,
        Long listingId) {

    public TradeRow {
        Objects.requireNonNull(buyer, "buyer");
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(itemClass, "itemClass");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(materialKey, "materialKey");
    }
}
