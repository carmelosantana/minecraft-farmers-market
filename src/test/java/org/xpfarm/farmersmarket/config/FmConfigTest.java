/*
 * FarmersMarket - unit tests for FmConfig#load.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FmConfig#load}. Deliberately imports nothing from {@code org.bukkit}:
 * reading goes through {@link MapConfigSource}, so the entire validation contract -- range
 * checks, enum parsing, and the buy-back faucet audit -- is exercised with no running server.
 */
class FmConfigTest {

    @Test
    void rejectsOutOfRangeTaxAndFallsBackToDefault() {
        List<String> warnings = new ArrayList<>();
        MapConfigSource source = new MapConfigSource(Map.of("economy.sales-tax-percent", 150.0));

        FmConfig config = FmConfig.load(source, warnings::add);

        assertEquals(7.0, config.salesTaxPercent());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("economy.sales-tax-percent"));
    }

    @Test
    void acceptsValidValuesWithoutWarning() {
        List<String> warnings = new ArrayList<>();
        MapConfigSource source = new MapConfigSource(Map.of(
                "economy.sales-tax-percent", 5.0,
                "economy.listing-fee-percent", 0.5,
                "storage.busy-timeout-ms", 9000));

        FmConfig config = FmConfig.load(source, warnings::add);

        assertEquals(5.0, config.salesTaxPercent());
        assertEquals(0.5, config.listingFeePercent());
        assertEquals(9000, config.busyTimeoutMs());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void emptyConfigYieldsEveryDocumentedDefault() {
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of()), w -> {
        });

        assertEquals(1.0, config.listingFeePercent());
        assertEquals(7.0, config.salesTaxPercent());
        assertEquals(0.5, config.taxBurnShare());
        assertEquals(40, config.xpPerDiamond());
        assertEquals(14, config.listingDurationDays());
        assertEquals(40, config.maxListingsPerPlayer());
        assertEquals(4, config.buyLimitWindowHours());
        assertEquals(2, config.minPlaytimeHours());
        assertEquals(2, config.maxVendorsPerPlayer());
        assertEquals(24, config.vendorMinDistanceBlocks());
        assertEquals(168, config.stallBidPeriodHours());
        assertEquals(1, config.maxStallsPerPlayer());
        assertEquals(60, config.chartRefreshSeconds());
        assertEquals(3.0, config.outlierFilterMad());
        assertEquals(365, config.historyRetentionDays());
        assertEquals(5000, config.busyTimeoutMs());
        assertEquals("plugins/FarmersMarket/tmp", config.sqliteTmpdir());
        assertEquals(BarGlyphs.DENSITY_4, config.barGlyphs());
        assertEquals(VendorPlacementMode.OWNED_AND_DISTRICT, config.vendorPlacementMode());
    }

    /**
     * Every boolean default, pinned.
     *
     * <p>{@code liquidity.farm-output-audit} is the one that matters most: it is the check that
     * refuses a buy-back floor at or above what the item costs to farm, which is an infinite
     * money faucet. Defaulting it to {@code false} would disarm that on every server that never
     * touched the key, and nothing else in the suite would notice.
     */
    @Test
    void everyBooleanDefaultsToOnIncludingTheFaucetSafetySwitch() {
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of()), w -> {
        });

        assertTrue(config.farmOutputAudit(),
                "the faucet audit must default ON; off, a misconfigured floor mints money");
        assertTrue(config.buybackEnabled());
        assertTrue(config.buyLimitEnabled());
        assertTrue(config.vendorLabelEnabled());
        assertTrue(config.stallsEnabled());
        assertTrue(config.chartsEnabled());
        assertTrue(config.bedrockTouchSimplify());
    }

    /**
     * The other half of the test above: an operator's explicit {@code false} must actually be
     * read. Together they pin the default and the plumbing -- a getter hardcoded to {@code true}
     * passes the first test and fails this one.
     */
    @Test
    void everyBooleanIsReadFromTheConfigWhenItIsSetToFalse() {
        Map<String, Object> allOff = new LinkedHashMap<>();
        allOff.put("liquidity.farm-output-audit", false);
        allOff.put("liquidity.buyback-enabled", false);
        allOff.put("limits.buy-limit-enabled", false);
        allOff.put("vendor.label-enabled", false);
        allOff.put("stalls.enabled", false);
        allOff.put("ui.charts-enabled", false);
        allOff.put("ui.bedrock-touch-simplify", false);

        FmConfig config = FmConfig.load(new MapConfigSource(allOff), w -> {
        });

        assertFalse(config.farmOutputAudit());
        assertFalse(config.buybackEnabled());
        assertFalse(config.buyLimitEnabled());
        assertFalse(config.vendorLabelEnabled());
        assertFalse(config.stallsEnabled());
        assertFalse(config.chartsEnabled());
        assertFalse(config.bedrockTouchSimplify());
    }

    /**
     * Zero is a legal, documented value for both keys whose contract is {@code >= 0}: no
     * playtime requirement, and no minimum spacing between vendors. Validating them with
     * "strictly greater than 0" instead would silently substitute the default for an operator
     * who meant exactly what they typed, warning about a value that was never wrong.
     */
    @Test
    void zeroIsAcceptedForBothKeysWhoseMinimumIsZero() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("limits.min-playtime-hours", 0);
        source.put("vendor.min-distance-blocks", 0);

        FmConfig config = FmConfig.load(new MapConfigSource(source), warnings::add);

        assertEquals(0, config.minPlaytimeHours());
        assertEquals(0, config.vendorMinDistanceBlocks());
        assertTrue(warnings.isEmpty(), () -> "0 is legal for both keys, got " + warnings);
    }

    /** The other side of that boundary: below zero is still refused, and still warns. */
    @Test
    void negativeIsRefusedForBothKeysWhoseMinimumIsZero() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("limits.min-playtime-hours", -1);
        source.put("vendor.min-distance-blocks", -1);

        FmConfig config = FmConfig.load(new MapConfigSource(source), warnings::add);

        assertEquals(2, config.minPlaytimeHours());
        assertEquals(24, config.vendorMinDistanceBlocks());
        assertEquals(2, warnings.size(), () -> "both keys must warn, got " + warnings);
    }

    @Test
    void unknownEnumValueWarnsAndFallsBack() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("ui.bar-glyphs", "rainbow")), warnings::add);

        assertEquals(BarGlyphs.DENSITY_4, config.barGlyphs());
        assertEquals(1, warnings.size());
    }

    @Test
    void buybackFloorAtOrAboveFarmOutputCostIsRefused() {
        List<String> warnings = new ArrayList<>();
        MapConfigSource source = new MapConfigSource(Map.of(
                "liquidity.buyback-floors", Map.of("DIAMOND", 10.0),
                "liquidity.farm-output-costs", Map.of("DIAMOND", 10.0)));

        FmConfig config = FmConfig.load(source, warnings::add);

        assertTrue(config.buybackFloors().isEmpty(),
                "a floor at or above production cost is an infinite money faucet and must not load");
        assertTrue(warnings.get(0).contains("DIAMOND"));
    }

    @Test
    void buybackFloorWithNoFarmOutputCostIsRefused() {
        List<String> warnings = new ArrayList<>();
        MapConfigSource source = new MapConfigSource(Map.of(
                "liquidity.buyback-floors", Map.of("IRON_INGOT", 1.0)));

        FmConfig config = FmConfig.load(source, warnings::add);

        assertTrue(config.buybackFloors().isEmpty(),
                "an unestimated item is the dangerous case; fail closed");
    }

    @Test
    void buybackFloorBelowFarmOutputCostLoads() {
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
                "liquidity.buyback-floors", Map.of("IRON_INGOT", 1.0),
                "liquidity.farm-output-costs", Map.of("IRON_INGOT", 4.0))), w -> {
        });

        assertEquals(1, config.buybackFloors().size());
    }

    @Test
    void disablingTheAuditStillLoadsFloorsButWarnsLoudly() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
                "liquidity.farm-output-audit", false,
                "liquidity.buyback-floors", Map.of("DIAMOND", 999.0))), warnings::add);

        assertEquals(1, config.buybackFloors().size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("farm-output-audit")));
    }

    /**
     * The audit-disabled warning fires once for the whole config, not once per surviving
     * floor -- otherwise an operator with a dozen floors and the audit off would get a dozen
     * copies of the same warning.
     */
    @Test
    void disablingTheAuditWarnsExactlyOnceRegardlessOfFloorCount() {
        List<String> warnings = new ArrayList<>();
        Map<String, Double> manyFloors = new LinkedHashMap<>();
        manyFloors.put("DIAMOND", 1.0);
        manyFloors.put("IRON_INGOT", 1.0);
        manyFloors.put("GOLD_INGOT", 1.0);

        FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
                "liquidity.farm-output-audit", false,
                "liquidity.buyback-floors", manyFloors)), warnings::add);

        assertEquals(3, config.buybackFloors().size());
        assertEquals(1, warnings.stream().filter(w -> w.contains("farm-output-audit")).count());
    }

    /**
     * Pins a deliberate decision, not an accident of control flow: with no floors configured
     * at all, disabling the audit has nothing to warn about. No floors means no faucet risk.
     */
    @Test
    void auditDisabledWithNoFloorsIsSilent() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
                "liquidity.farm-output-audit", false)), warnings::add);

        assertTrue(config.buybackFloors().isEmpty());
        assertTrue(warnings.isEmpty(), () -> "no floors means nothing to warn about, got " + warnings);
    }

    @Test
    void nonPositiveFarmOutputCostEntryIsDroppedAndWarned() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
                "liquidity.farm-output-costs", Map.of("DIAMOND", -1.0))), warnings::add);

        assertTrue(config.farmOutputCosts().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("farm-output-costs") && w.contains("DIAMOND")));
    }

    @Test
    void nonPositiveBuyLimitEntryIsDroppedAndWarned() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
                "limits.buy-limits", Map.of("DIAMOND", 0))), warnings::add);

        assertTrue(config.buyLimits().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("buy-limits") && w.contains("DIAMOND")));
    }

    @Test
    void validBuyLimitAndFarmOutputCostEntriesLoadWithoutWarning() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
                "limits.buy-limits", Map.of("DIAMOND", 16),
                "liquidity.farm-output-costs", Map.of("IRON_INGOT", 4.0))), warnings::add);

        assertEquals(16, config.buyLimits().get("DIAMOND"));
        assertEquals(4.0, config.farmOutputCosts().get("IRON_INGOT"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unknownVendorPlacementModeWarnsAndFallsBack() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("vendor.placement-mode", "everywhere")), warnings::add);

        assertEquals(VendorPlacementMode.OWNED_AND_DISTRICT, config.vendorPlacementMode());
        assertEquals(1, warnings.size());
    }

    @Test
    void vendorPlacementModeParsesEveryConstantCaseInsensitively() {
        assertEquals(VendorPlacementMode.DISTRICT_ONLY, FmConfig.load(
                new MapConfigSource(Map.of("vendor.placement-mode", "District-Only")), w -> {
        }).vendorPlacementMode());
        assertEquals(VendorPlacementMode.ANYWHERE, FmConfig.load(
                new MapConfigSource(Map.of("vendor.placement-mode", "ANYWHERE")), w -> {
        }).vendorPlacementMode());
    }

    @Test
    void barGlyphsParsesRamp8CaseInsensitively() {
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("ui.bar-glyphs", "RAMP-8")), w -> {
        });

        assertEquals(BarGlyphs.RAMP_8, config.barGlyphs());
    }

    @Test
    void invalidStallBidCurrencyWarnsAndFallsBackToXp() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("stalls.bid-currency", "gold")), warnings::add);

        assertEquals("xp", config.stallBidCurrency());
        assertEquals(1, warnings.size());
    }

    @Test
    void stallBidCurrencyAcceptsDiamondsCaseInsensitively() {
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("stalls.bid-currency", "DIAMONDS")), w -> {
        });

        assertEquals("diamonds", config.stallBidCurrency());
    }

    @Test
    void blankSqliteTmpdirWarnsAndFallsBackToDefault() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("storage.sqlite-tmpdir", "   ")), warnings::add);

        assertEquals("plugins/FarmersMarket/tmp", config.sqliteTmpdir());
        assertEquals(1, warnings.size());
    }

    @Test
    void chartRefreshSecondsBelowTenWarnsAndFallsBack() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("ui.chart-refresh-seconds", 9)), warnings::add);

        assertEquals(60, config.chartRefreshSeconds());
        assertEquals(1, warnings.size());
    }

    @Test
    void zeroXpPerDiamondWarnsAndFallsBack() {
        List<String> warnings = new ArrayList<>();
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("economy.xp-per-diamond", 0)), warnings::add);

        assertEquals(40, config.xpPerDiamond());
        assertEquals(1, warnings.size());
    }

    @Test
    void wrongTypedValueFallsBackSilentlyFromTheSourceButIsStillDefaultCorrect() {
        // MapConfigSource returns the supplied default for a wrongly-typed value without
        // warning itself (that is BukkitConfigSource's job in production); FmConfig still
        // ends up with the documented default either way.
        FmConfig config = FmConfig.load(
                new MapConfigSource(Map.of("economy.sales-tax-percent", "not-a-number")), w -> {
        });

        assertEquals(7.0, config.salesTaxPercent());
    }

    /**
     * In-memory {@link ConfigSource} for tests, holding raw values keyed by dotted path.
     *
     * <p>Coercion mirrors a real {@code ConfigurationSection}: a numeric getter accepts only
     * a {@link Number} and the boolean getter only a {@link Boolean}. A map-valued entry
     * (for {@link #getDoubleMap} / {@link #getIntMap}) is itself a raw {@code Map<String,
     * Object>} whose non-numeric entries are skipped. A present-but-uncoercible value simply
     * returns the caller's default -- no warning is produced here, matching the type each
     * {@code ConfigSource} getter's own contract documents; only {@link FmConfig}'s range,
     * enum, and audit checks warn in these tests.
     */
    private static final class MapConfigSource implements ConfigSource {

        private final Map<String, Object> values;

        MapConfigSource(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public int getInt(String path, int def) {
            Object raw = values.get(path);
            return raw instanceof Number number ? number.intValue() : def;
        }

        @Override
        public long getLong(String path, long def) {
            Object raw = values.get(path);
            return raw instanceof Number number ? number.longValue() : def;
        }

        @Override
        public double getDouble(String path, double def) {
            Object raw = values.get(path);
            return raw instanceof Number number ? number.doubleValue() : def;
        }

        @Override
        public boolean getBoolean(String path, boolean def) {
            Object raw = values.get(path);
            return raw instanceof Boolean bool ? bool : def;
        }

        @Override
        public String getString(String path, String def) {
            Object raw = values.get(path);
            return raw instanceof String str ? str : def;
        }

        @Override
        public List<String> getStringList(String path) {
            Object raw = values.get(path);
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }

        @Override
        public Map<String, Double> getDoubleMap(String path) {
            return coerceMap(path, raw -> raw instanceof Number number ? number.doubleValue() : null);
        }

        @Override
        public Map<String, Integer> getIntMap(String path) {
            return coerceMap(path, raw -> raw instanceof Number number ? number.intValue() : null);
        }

        private <T> Map<String, T> coerceMap(String path, Function<Object, T> coerce) {
            Object raw = values.get(path);
            if (!(raw instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, T> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    T value = coerce.apply(entry.getValue());
                    if (value != null) {
                        result.put(key, value);
                    }
                }
            }
            return result;
        }
    }
}
