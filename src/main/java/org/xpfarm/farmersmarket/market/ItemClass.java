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
 * time.
 *
 * <p>The {@link #classify(int, boolean)} rule that maps a stack to one of these is a pure
 * function over two primitives on purpose: the decision that guards the fungibility boundary is
 * unit-testable without a live server, and the one Bukkit adapter ({@code BukkitItemCodec}) only
 * has to <em>derive</em> those two primitives from a real {@code ItemStack}.
 */
public enum ItemClass {
    COMMODITY,
    UNIQUE;

    /**
     * Classifies an item from the two facts that decide fungibility.
     *
     * <p>An item is a {@link #COMMODITY} only when it both stacks ({@code maxStackSize > 1})
     * <em>and</em> carries no meaningful component data; everything else is {@link #UNIQUE}. A
     * single-stack item (a tool, armour) is {@code UNIQUE} even when plain, because it cannot be
     * pooled fungibly; a stackable item bearing enchantments, a custom name, damage, custom model
     * data, lore, or container contents is {@code UNIQUE} because pooling it into a fungible curve
     * would destroy what made it worth listing (the ChestShop "Anvil Wizardry" bug class).
     *
     * @param maxStackSize            the stack's maximum size; {@code 1} for tools and armour
     * @param hasMeaningfulComponents whether the item carries component data that individuates it
     * @return {@link #COMMODITY} only for a plain stackable item, otherwise {@link #UNIQUE}
     */
    public static ItemClass classify(int maxStackSize, boolean hasMeaningfulComponents) {
        return (maxStackSize > 1 && !hasMeaningfulComponents) ? COMMODITY : UNIQUE;
    }
}
