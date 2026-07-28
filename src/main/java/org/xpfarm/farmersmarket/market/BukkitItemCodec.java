/*
 * FarmersMarket - the one Bukkit adapter that turns a live ItemStack into market data.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The single class in the {@code market} package permitted to import {@code org.bukkit.*}: it
 * turns a live {@link ItemStack} into a Bukkit-free {@link ListedItem} and back.
 *
 * <p><strong>Not unit-tested; exercised at gate 7a on a live server.</strong>
 * {@link ItemStack#serializeAsBytes()} and {@link ItemStack#deserializeBytes(byte[])} need a
 * running server's registries, and there is no meaningful way to construct real metas without one.
 * This is the same boundary {@code EditionResolver}'s reflective half sits behind. To keep that
 * untested surface as thin as possible, this class makes <em>no decisions of its own</em>: it only
 * <em>derives</em> the two classification primitives and the display fields from the stack and
 * hands every actual decision to {@link ItemClass#classify(int, boolean)} and
 * {@link ItemKey#forUnique(byte[])}, which are pure and fully unit-tested.
 *
 * <p>{@link #hasMeaningfulComponents(ItemStack)} is deliberately conservative: <strong>when in
 * doubt it returns {@code true}</strong>. A false "commodity" is the dangerous direction, because
 * it would pool an individuated (named, enchanted, damaged, or filled) item into a fungible curve
 * and destroy it -- the ChestShop "Anvil Wizardry" bug class the split market exists to avoid.
 */
public final class BukkitItemCodec {

    /** How many container entries {@link #summarise(ItemStack)} names before eliding the rest. */
    private static final int MAX_ENUMERATED_CONTENTS = 3;

    private BukkitItemCodec() {
    }

    /**
     * Derives the market's view of {@code stack}: its classification, content-hash key, material,
     * display fields, amount, and the exact serialized bytes it will hold in escrow.
     *
     * @param stack the live item being listed
     * @return the Bukkit-free description of it
     */
    public static ListedItem encode(ItemStack stack) {
        byte[] bytes = stack.serializeAsBytes();
        int amount = stack.getAmount();
        String materialKey = stack.getType().name();
        boolean meaningful = hasMeaningfulComponents(stack);
        ItemClass itemClass = ItemClass.classify(stack.getMaxStackSize(), meaningful);
        String itemKey = ItemKey.forUnique(bytes);
        String displayName = displayNameText(stack);
        String summary = summarise(stack);
        return new ListedItem(itemClass, itemKey, materialKey, displayName, summary, amount, bytes);
    }

    /**
     * Reconstitutes the stack held in escrow by a listing.
     *
     * @param item the listing's stored item
     * @return the deserialized stack
     */
    public static ItemStack decode(ListedItem item) {
        return decode(item.itemBytes());
    }

    /**
     * Reconstitutes a stack from its serialized bytes.
     *
     * @param bytes the bytes produced by {@link ItemStack#serializeAsBytes()}
     * @return the deserialized stack
     */
    public static ItemStack decode(byte[] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }

    /**
     * Whether {@code stack} carries component data that individuates it and so bars it from a
     * fungible commodity pool. Conservative by design: any block state it cannot prove empty, or a
     * meta it cannot introspect, counts as meaningful.
     */
    private static boolean hasMeaningfulComponents(ItemStack stack) {
        if (!stack.getEnchantments().isEmpty()) {
            return true;
        }
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                || meta.hasCustomModelData()) {
            return true;
        }
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            return true;
        }
        if (meta instanceof EnchantmentStorageMeta stored && stored.hasStoredEnchants()) {
            return true;
        }
        if (meta instanceof BlockStateMeta blockState && blockState.hasBlockState()) {
            BlockState state = blockState.getBlockState();
            if (state instanceof Container container) {
                return !container.getInventory().isEmpty();
            }
            // A captured block state we cannot introspect (a sign's text, a spawner): in doubt, true.
            return true;
        }
        return false;
    }

    /**
     * The plain text of the item's custom name, or {@code null} if it has none. Stripped of all
     * formatting so it is safe to render to a Bedrock client, which never sees the item icon.
     */
    private static String displayNameText(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        Component name = meta.displayName();
        return name == null ? null : PlainTextComponentSerializer.plainText().serialize(name);
    }

    /**
     * A Bedrock-safe, plain-text one-liner about the item: its material in words, its custom name
     * if present, and for a container a short enumeration of its contents. No colour codes and no
     * glyphs, because a Bedrock player behind Geyser never sees the item icon (Geyser #3001) and
     * this line is the only thing they read about, for example, a shulker box's contents in Part 1.
     */
    private static String summarise(ItemStack stack) {
        StringBuilder out = new StringBuilder(words(stack.getType()));
        String name = displayNameText(stack);
        if (name != null && !name.isBlank()) {
            out.append(" — ").append(name.strip());
        }
        String contents = containerSummary(stack);
        if (contents != null) {
            out.append(" — ").append(contents);
        }
        return out.toString();
    }

    /**
     * A short content line for a container item, or {@code null} if {@code stack} is not a
     * container or is empty. Names the first {@value #MAX_ENUMERATED_CONTENTS} occupied slots and
     * appends an ellipsis when more remain.
     */
    private static String containerSummary(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockState) || !blockState.hasBlockState()) {
            return null;
        }
        if (!(blockState.getBlockState() instanceof Container container)) {
            return null;
        }
        int occupied = 0;
        List<String> named = new ArrayList<>();
        for (ItemStack content : container.getInventory().getContents()) {
            if (content == null || content.getType().isAir()) {
                continue;
            }
            occupied++;
            if (named.size() < MAX_ENUMERATED_CONTENTS) {
                named.add(words(content.getType()) + " x" + content.getAmount());
            }
        }
        if (occupied == 0) {
            return null;
        }
        String tail = occupied > named.size() ? ", …" : "";
        return occupied + " items (" + String.join(", ", named) + tail + ")";
    }

    /**
     * The material's enum name rendered as space-separated Title Case words, e.g.
     * {@code SHULKER_BOX} to {@code "Shulker Box"}.
     */
    private static String words(Material material) {
        StringBuilder out = new StringBuilder();
        for (String token : material.name().toLowerCase(Locale.ROOT).split("_")) {
            if (token.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return out.toString();
    }
}
