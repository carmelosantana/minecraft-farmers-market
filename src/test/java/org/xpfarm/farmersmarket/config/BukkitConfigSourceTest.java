/*
 * FarmersMarket - unit tests for BukkitConfigSource.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BukkitConfigSource} against a real
 * {@link org.bukkit.configuration.ConfigurationSection}. {@code MemoryConfiguration} is plain
 * data with no server behind it, so the adapter's null guard and its present-but-uncoercible
 * detection are both exercisable here -- for scalars, for lists, and for the item-keyed maps.
 */
class BukkitConfigSourceTest {

    private final List<String> warnings = new ArrayList<>();

    private BukkitConfigSource sourceOver(MemoryConfiguration section) {
        return new BukkitConfigSource(section, warnings::add);
    }

    // ---- null section ----------------------------------------------------------------

    @Test
    void nullSection_behavesAsAnEmptyConfiguration() {
        BukkitConfigSource source = new BukkitConfigSource(null, warnings::add);

        assertEquals(5000, source.getInt("storage.busy-timeout-ms", 5000));
        assertEquals(7.0, source.getDouble("economy.sales-tax-percent", 7.0));
        assertTrue(source.getBoolean("liquidity.buyback-enabled", true));
        assertEquals("xp", source.getString("stalls.bid-currency", "xp"));
        assertEquals(List.of(), source.getStringList("analytics.index-basket"));
        assertEquals(Map.of(), source.getDoubleMap("liquidity.buyback-floors"));
        assertEquals(Map.of(), source.getIntMap("limits.buy-limits"));

        assertTrue(warnings.isEmpty(), () -> "an absent configuration is not an error, got " + warnings);
    }

    // ---- absent keys ------------------------------------------------------------------

    @Test
    void absentContainerKeys_fallBackSilently() {
        BukkitConfigSource source = sourceOver(new MemoryConfiguration());

        assertEquals(List.of(), source.getStringList("analytics.index-basket"));
        assertEquals(Map.of(), source.getDoubleMap("liquidity.buyback-floors"));
        assertEquals(Map.of(), source.getIntMap("limits.buy-limits"));

        assertTrue(warnings.isEmpty(), () -> "an unset map or list is normal, not an error, got " + warnings);
    }

    // ---- well-typed containers ---------------------------------------------------------

    /**
     * Map-valued paths are set via {@code createSection}, not {@code set}, because that is
     * what actually happens when {@code YamlConfiguration} parses a real {@code config.yml}:
     * SnakeYAML hands it a raw {@code Map}, which it recursively converts into nested
     * {@code ConfigurationSection}s. {@code MemoryConfiguration#set} with a raw {@code Map}
     * value skips that conversion, which would make these fixtures unfaithful to production.
     */
    @Test
    void wellTypedContainers_areReadThroughWithoutWarning() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("analytics.index-basket", List.of("DIAMOND", "IRON_INGOT"));
        section.createSection("liquidity.buyback-floors", Map.of("DIAMOND", 1.5));
        section.createSection("limits.buy-limits", Map.of("DIAMOND", 16));

        BukkitConfigSource source = sourceOver(section);

        assertEquals(List.of("DIAMOND", "IRON_INGOT"), source.getStringList("analytics.index-basket"));
        assertEquals(1.5, source.getDoubleMap("liquidity.buyback-floors").get("DIAMOND"));
        assertEquals(16, source.getIntMap("limits.buy-limits").get("DIAMOND"));

        assertTrue(warnings.isEmpty(), () -> "valid containers must not warn, got " + warnings);
    }

    @Test
    void emptyMapValue_isReadAsAnEmptyMapWithoutWarning() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.createSection("liquidity.buyback-floors", Map.of());

        BukkitConfigSource source = sourceOver(section);

        assertEquals(Map.of(), source.getDoubleMap("liquidity.buyback-floors"));
        assertTrue(warnings.isEmpty(), () -> "config.yml ships every liquidity map as {}, got " + warnings);
    }

    /** A non-numeric entry inside an otherwise-valid map is skipped, not warned about. */
    @Test
    void nonNumericEntryInsideAValidMap_isSkippedSilently() {
        MemoryConfiguration section = new MemoryConfiguration();
        Map<String, Object> floors = new java.util.LinkedHashMap<>();
        floors.put("DIAMOND", 1.5);
        floors.put("IRON_INGOT", "oops");
        section.createSection("liquidity.buyback-floors", floors);

        BukkitConfigSource source = sourceOver(section);
        Map<String, Double> result = source.getDoubleMap("liquidity.buyback-floors");

        assertEquals(1, result.size());
        assertEquals(1.5, result.get("DIAMOND"));
        assertTrue(warnings.isEmpty(),
                () -> "a bad entry inside a valid map falls back per-entry without warning, got " + warnings);
    }

    // ---- present but wrongly typed containers ------------------------------------------

    @Test
    void indexBasketAsAScalar_warnsAndFallsBackToAnEmptyList() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("analytics.index-basket", 42);

        BukkitConfigSource source = sourceOver(section);

        assertEquals(List.of(), source.getStringList("analytics.index-basket"));
        assertSingleWarningNaming("analytics.index-basket", "42", "[]");
    }

    @Test
    void buybackFloorsAsAScalar_warnsAndFallsBackToAnEmptyMap() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("liquidity.buyback-floors", "oops");

        BukkitConfigSource source = sourceOver(section);

        assertEquals(Map.of(), source.getDoubleMap("liquidity.buyback-floors"));
        assertSingleWarningNaming("liquidity.buyback-floors", "oops", "{}");
    }

    @Test
    void buyLimitsAsAScalar_warnsAndFallsBackToAnEmptyMap() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("limits.buy-limits", "oops");

        BukkitConfigSource source = sourceOver(section);

        assertEquals(Map.of(), source.getIntMap("limits.buy-limits"));
        assertSingleWarningNaming("limits.buy-limits", "oops", "{}");
    }

    // ---- scalar present-but-uncoercible (baseline regression coverage) ----------------

    @Test
    void unreadableInt_warnsAndFallsBack() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("storage.busy-timeout-ms", "lots");

        assertEquals(5000, sourceOver(section).getInt("storage.busy-timeout-ms", 5000));
        assertSingleWarningNaming("storage.busy-timeout-ms", "lots", "5000");
    }

    @Test
    void unreadableDouble_warnsAndFallsBack() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("economy.sales-tax-percent", "very");

        assertEquals(7.0, sourceOver(section).getDouble("economy.sales-tax-percent", 7.0));
        assertSingleWarningNaming("economy.sales-tax-percent", "very", "7.0");
    }

    @Test
    void unreadableBoolean_warnsAndFallsBack() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("liquidity.buyback-enabled", "yes-please");

        assertTrue(sourceOver(section).getBoolean("liquidity.buyback-enabled", true));
        assertSingleWarningNaming("liquidity.buyback-enabled", "yes-please", "true");
    }

    private void assertSingleWarningNaming(String key, String value, String fallback) {
        assertEquals(1, warnings.size(), () -> "expected exactly one warning, got " + warnings);
        String message = warnings.get(0);
        assertTrue(message.contains(key), () -> "warning must name the key: " + message);
        assertTrue(message.contains(value), () -> "warning must quote the offending value: " + message);
        assertTrue(message.contains(fallback), () -> "warning must name the substituted default: " + message);
    }
}
