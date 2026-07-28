/*
 * FarmersMarket - immutable snapshot of one row of the listings table.
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
 * One row of {@code listings}, the market's escrow record for an item put up for sale.
 *
 * @param id                the row id; {@code 0} for a not-yet-inserted row, assigned by SQLite
 * @param seller            the player who listed the item
 * @param itemClass         how the item is classified for routing
 * @param itemKey           the stable identity key the market groups the item under
 * @param materialKey       the underlying material, for browse filtering
 * @param displayName       the item's custom name, or {@code null} if it has none
 * @param summary           a human-readable one-line description, always present
 * @param amount            the stack size on offer; always positive, enforced by the table
 * @param priceDust         the asking price in dust; always positive, enforced by the table
 * @param itemBytes         the serialized item stack held in escrow, read back verbatim
 * @param listedAtEpochMs   when the listing was created, epoch milliseconds
 * @param expiresAtEpochMs  when the listing lapses if unsold, epoch milliseconds
 * @param status            the listing's lifecycle state
 * @param soldAtEpochMs     when the sale completed, or {@code null} while unsold
 * @param buyer             who bought it, or {@code null} while unsold
 */
public record ListingRow(
        long id,
        UUID seller,
        ItemClass itemClass,
        String itemKey,
        String materialKey,
        String displayName,
        String summary,
        int amount,
        long priceDust,
        byte[] itemBytes,
        long listedAtEpochMs,
        long expiresAtEpochMs,
        ListingStatus status,
        Long soldAtEpochMs,
        UUID buyer) {

    public ListingRow {
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(itemClass, "itemClass");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(itemBytes, "itemBytes");
        Objects.requireNonNull(status, "status");
    }
}
