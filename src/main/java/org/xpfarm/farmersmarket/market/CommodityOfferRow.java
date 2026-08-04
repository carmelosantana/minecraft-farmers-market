/*
 * FarmersMarket - immutable snapshot of one row of the commodity_offers table.
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
 * One row of {@code commodity_offers}, a buyer's resting bid on a fungible material.
 *
 * @param id             the row id; {@code 0} for a not-yet-inserted row, assigned by SQLite
 * @param buyer          the player who placed the bid
 * @param materialKey    the material the bid is for
 * @param qtyRemaining   how many units the bid still wants; never negative, enforced by the table
 * @param priceEachDust  the per-unit price the buyer will pay, in dust; always positive, enforced by the table
 * @param escrowedDust   the buyer's dust held against this bid; never negative, enforced by the table
 * @param xpPaid         the experience the buyer paid to place the bid
 * @param createdAt      when the bid was placed, epoch milliseconds
 * @param status         the offer's lifecycle state
 */
public record CommodityOfferRow(
        long id,
        UUID buyer,
        String materialKey,
        int qtyRemaining,
        long priceEachDust,
        long escrowedDust,
        int xpPaid,
        long createdAt,
        OfferStatus status) {

    public CommodityOfferRow {
        Objects.requireNonNull(buyer, "buyer");
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(status, "status");
        if (qtyRemaining < 0) {
            throw new IllegalArgumentException("qtyRemaining must not be negative: " + qtyRemaining);
        }
        if (priceEachDust <= 0) {
            throw new IllegalArgumentException("priceEachDust must be positive: " + priceEachDust);
        }
        if (escrowedDust < 0) {
            throw new IllegalArgumentException("escrowedDust must not be negative: " + escrowedDust);
        }
    }
}
