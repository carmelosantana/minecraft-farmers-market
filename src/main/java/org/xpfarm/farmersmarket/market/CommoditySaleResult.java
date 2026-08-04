/*
 * FarmersMarket - the Bukkit-free outcome of one market-sell across the bid book and the floor.
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
 * The outcome of a market-sell. {@code sold + unsold} equals the quantity the seller offered;
 * {@code unsold} is what the command layer returns to the seller's inventory. {@code proceeds} is
 * the total diamonds the seller received: the tax-netted amount from every player fill plus the
 * untaxed floor gross.
 */
public record CommoditySaleResult(int sold, int unsold, Diamonds proceeds) {
}
