/*
 * FarmersMarket - immutable snapshot of one row of the pending_items table.
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
 * One row of {@code pending_items}, an item the market owes a player who was not online (or had
 * no room) when it needed to be handed over.
 *
 * @param id                 the row id; {@code 0} for a not-yet-inserted row, assigned by SQLite
 * @param owner              the player owed the item
 * @param itemBytes          the serialized item stack to hand over, read back verbatim
 * @param amount             the stack size owed; always positive, enforced by the table
 * @param summary            a human-readable one-line description of what is owed
 * @param reason             why it is owed (e.g. a sold listing, an expired one returned)
 * @param createdAtEpochMs   when the debt was recorded, epoch milliseconds
 * @param claimedAtEpochMs   when the player claimed it, or {@code null} while still owed
 */
public record PendingItemRow(
        long id,
        UUID owner,
        byte[] itemBytes,
        int amount,
        String summary,
        String reason,
        long createdAtEpochMs,
        Long claimedAtEpochMs) {

    public PendingItemRow {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(itemBytes, "itemBytes");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(reason, "reason");
    }
}
