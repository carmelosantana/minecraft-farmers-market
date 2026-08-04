/*
 * FarmersMarket - the Bukkit-free canonical single-unit form of one commodity.
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
 * The canonical single-unit form of one commodity: the namespaced material key both a bid and a
 * sell match on, the content-hash item key the trade log records, the serialized bytes of exactly
 * one plain item (delivered {@code amount}x to a buyer on a fill), and a display name for chat.
 * Bukkit-free by construction so it can cross into the market service and be tested without a server.
 */
public record CommoditySpec(String materialKey, String itemKey, byte[] oneItemBytes, String displayName) {
    public CommoditySpec {
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(oneItemBytes, "oneItemBytes");
        Objects.requireNonNull(displayName, "displayName");
        oneItemBytes = oneItemBytes.clone();
    }

    @Override
    public byte[] oneItemBytes() {
        return oneItemBytes.clone();
    }
}
