/*
 * FarmersMarket - the committed outcome of a sale, handed back for the main thread to finish.
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
 * What a committed sale hands the command layer so it can finish on the main thread.
 *
 * <p>The item bytes come back in this value rather than being delivered inside the transaction on
 * purpose: the money move and the audit row are final before any inventory is touched. If handing
 * the buyer the item then physically fails -- their inventory is full, they log off mid-delivery --
 * the sale has still cleared, and the command layer re-holds the item through a fresh
 * {@code pending_items} row rather than trying to reverse a committed money move.
 *
 * <p><b>Compared by identity, not value,</b> for the same reason {@link ListedItem} is: the
 * defensive copy of {@code itemBytes} stops the record being mutated through a caller's array, but
 * the generated {@code equals}/{@code hashCode} still compare that array by reference. Nothing
 * relies on {@code SaleResult} value equality.
 *
 * @param itemBytes the serialized item stack to hand the buyer, defensively copied on construction
 * @param amount    the stack size sold
 * @param summary   a human-readable one-line description of what was sold
 * @param seller    the player who was paid the net
 * @param split     the conserved gross/net/tax/burn/pot breakdown of the price
 * @param listingId the {@code listings} row this sale settled
 */
public record SaleResult(
        byte[] itemBytes,
        int amount,
        String summary,
        UUID seller,
        MarketMath.TaxSplit split,
        long listingId) {

    public SaleResult {
        Objects.requireNonNull(itemBytes, "itemBytes");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(split, "split");
        itemBytes = itemBytes.clone();
    }
}
