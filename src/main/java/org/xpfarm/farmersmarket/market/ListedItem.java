/*
 * FarmersMarket - the Bukkit-free value type describing an item about to be listed.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.util.Objects;

/**
 * A Bukkit-free description of one item as the market sees it: its classification, identity key,
 * display fields, amount, and the serialized bytes it will hold in escrow.
 *
 * <p>{@code BukkitItemCodec} is the one place a live {@code ItemStack} is turned into a
 * {@code ListedItem}; everything downstream (routing, persistence, browse) reads this value type
 * and never Bukkit, which is why it lives in the server-free half of the package.
 *
 * <p><strong>Compared by identity, not value.</strong> The compact constructor defensively copies
 * {@code itemBytes} so the record cannot be mutated through the array a caller passed in, but the
 * generated {@code equals}/{@code hashCode} still compare that array by reference. Nothing in the
 * market relies on {@code ListedItem} value equality, so no deep-equality implementation is
 * provided; treat two instances as equal only when they are the same object.
 *
 * @param itemClass   how the item is classified for routing
 * @param itemKey     the stable identity key the market groups the item under
 * @param materialKey the underlying material, for browse filtering
 * @param displayName the item's custom name, or {@code null} if it has none
 * @param summary     a human-readable one-line description, always present
 * @param amount      the stack size on offer
 * @param itemBytes   the serialized item stack, defensively copied on construction
 */
public record ListedItem(
        ItemClass itemClass,
        String itemKey,
        String materialKey,
        String displayName,
        String summary,
        int amount,
        byte[] itemBytes) {

    public ListedItem {
        Objects.requireNonNull(itemClass, "itemClass");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(itemBytes, "itemBytes");
        itemBytes = itemBytes.clone();
    }
}
