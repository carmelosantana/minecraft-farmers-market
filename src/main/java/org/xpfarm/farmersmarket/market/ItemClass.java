/*
 * FarmersMarket - how a listed item is classified for market routing and pricing.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

/**
 * How a listed item is classified for market routing.
 *
 * <p>{@code COMMODITY} items are fungible stacks priced against a shared curve;
 * {@code UNIQUE} items (enchanted, named, or otherwise NBT-bearing) are sold one listing at a
 * time. Task 3 adds the {@code classify} rule that maps a stack to one of these; this task only
 * needs the two constants so the row types can name the column they persist.
 */
public enum ItemClass {
    COMMODITY,
    UNIQUE
}
