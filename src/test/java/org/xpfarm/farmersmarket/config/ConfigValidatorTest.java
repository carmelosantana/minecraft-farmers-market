/*
 * FarmersMarket - unit tests for the pure config value validators.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigValidator}, including the edge cases that cannot be reached
 * through {@link FmConfig#load} with a well-behaved {@link ConfigSource} -- YAML's {@code .NaN},
 * a {@code null} string, and a {@code null} map value. The ordinary key-by-key cases are covered
 * end-to-end in {@code FmConfigTest}, which is deliberately left untouched: this extraction
 * changed where the rules live, not what they decide.
 */
final class ConfigValidatorTest {

    private final List<String> warnings = new ArrayList<>();

    private void warn(String message) {
        warnings.add(message);
    }

    // ---- ranges ----------------------------------------------------------------------------

    @Test
    void anOutOfRangeIntIsReplacedByTheDefaultRatherThanClampedToTheBound() {
        int result = ConfigValidator.requireInRange("economy.listing-duration-days", 1000, 1, 90, 14, this::warn);

        assertEquals(14, result, "an out-of-range value falls back to the default, it is not clamped to max");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("economy.listing-duration-days"), warnings.get(0));
        assertTrue(warnings.get(0).contains("1000"), "the offending value must be named: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("14"), "the substituted default must be named: " + warnings.get(0));
    }

    @Test
    void bothEndsOfAnIntRangeAreAccepted() {
        assertEquals(1, ConfigValidator.requireInRange("k", 1, 1, 90, 14, this::warn));
        assertEquals(90, ConfigValidator.requireInRange("k", 90, 1, 90, 14, this::warn));

        assertTrue(warnings.isEmpty(), "an in-range value must not warn: " + warnings);
    }

    @Test
    void anIntBelowTheRangeIsRejectedJustAsOneAboveItIs() {
        assertEquals(14, ConfigValidator.requireInRange("k", 0, 1, 90, 14, this::warn));

        assertEquals(1, warnings.size());
    }

    @Test
    void notANumberIsRejectedAsOutOfRange() {
        // YAML's .NaN reaches here as a Double that compares false against every bound, so a
        // plain min/max test would accept it and hand a NaN percentage to the fee arithmetic.
        double result = ConfigValidator.requireInRange("economy.sales-tax-percent", Double.NaN, 0.0, 100.0,
                7.0, this::warn);

        assertEquals(7.0, result);
        assertEquals(1, warnings.size());
    }

    @Test
    void notANumberIsAlsoRejectedByTheStrictlyAboveCheck() {
        double result = ConfigValidator.requireAbove("analytics.outlier-filter-mad", Double.NaN, 0.0,
                3.0, this::warn);

        assertEquals(3.0, result);
        assertEquals(1, warnings.size());
    }

    @Test
    void requireAtLeastAcceptsTheMinimumItselfAndRejectsWhatIsBelowIt() {
        assertEquals(0, ConfigValidator.requireAtLeast("limits.min-playtime-hours", 0, 0, 2, this::warn));
        assertTrue(warnings.isEmpty());

        assertEquals(2, ConfigValidator.requireAtLeast("limits.min-playtime-hours", -1, 0, 2, this::warn));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("at least"), warnings.get(0));
    }

    @Test
    void requireAboveRejectsTheBoundItselfWhichIsTheDifferenceFromRequireAtLeast() {
        // economy.xp-per-diamond of 0 would divide by zero, so "at least 0" is the wrong rule
        // for it and "above 0" is the right one. The two differ only on the bound.
        assertEquals(40, ConfigValidator.requireAbove("economy.xp-per-diamond", 0, 0, 40, this::warn));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("greater than"), warnings.get(0));

        assertEquals(1, ConfigValidator.requireAbove("economy.xp-per-diamond", 1, 0, 40, this::warn));
        assertEquals(1, warnings.size(), "a value above the bound must not warn");
    }

    @Test
    void requireAboveRejectsTheBoundItselfForDoublesToo() {
        assertEquals(3.0, ConfigValidator.requireAbove("analytics.outlier-filter-mad", 0.0, 0.0, 3.0, this::warn));
        assertEquals(1, warnings.size());

        assertEquals(0.5, ConfigValidator.requireAbove("analytics.outlier-filter-mad", 0.5, 0.0, 3.0, this::warn));
        assertEquals(1, warnings.size());
    }

    // ---- strings and enums -----------------------------------------------------------------

    @Test
    void oneOfNormalizesCaseAndSurroundingSpaceRatherThanRejectingIt() {
        assertEquals("diamonds",
                ConfigValidator.requireOneOf("stalls.bid-currency", "  DiaMonds ", Set.of("xp", "diamonds"),
                        "xp", this::warn));

        assertTrue(warnings.isEmpty(), "a value that differs only in case is not a typo: " + warnings);
    }

    @Test
    void aNullValueIsRejectedByOneOfRatherThanThrowing() {
        assertEquals("xp", ConfigValidator.requireOneOf("stalls.bid-currency", null, Set.of("xp"), "xp", this::warn));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("stalls.bid-currency"), warnings.get(0));
    }

    @Test
    void aBlankStringIsRejectedAsBlankAndNotAcceptedAsAValue() {
        // A whitespace-only sqlite-tmpdir would resolve to the server root and scatter the
        // driver's native library there, so " " must fail exactly as "" does.
        assertEquals(FmConfig.DEFAULT_SQLITE_TMPDIR,
                ConfigValidator.requireNonBlank("storage.sqlite-tmpdir", "   ",
                        FmConfig.DEFAULT_SQLITE_TMPDIR, this::warn));
        assertEquals(FmConfig.DEFAULT_SQLITE_TMPDIR,
                ConfigValidator.requireNonBlank("storage.sqlite-tmpdir", null,
                        FmConfig.DEFAULT_SQLITE_TMPDIR, this::warn));

        assertEquals(2, warnings.size());
    }

    @Test
    void anEnumThatParsedIsReturnedAndOneThatDidNotFallsBack() {
        assertEquals(BarGlyphs.RAMP_8, ConfigValidator.requireEnum("ui.bar-glyphs", "ramp-8",
                Optional.of(BarGlyphs.RAMP_8), BarGlyphs.DENSITY_4, this::warn));
        assertTrue(warnings.isEmpty());

        assertEquals(BarGlyphs.DENSITY_4, ConfigValidator.requireEnum("ui.bar-glyphs", "sparkline",
                Optional.empty(), BarGlyphs.DENSITY_4, this::warn));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("sparkline"),
                "the unknown value must be named so the operator can find their typo: " + warnings.get(0));
    }

    // ---- item maps -------------------------------------------------------------------------

    @Test
    void nonPositiveAndNullMapEntriesAreDroppedAndEveryOneIsReported() {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("DIAMOND", 4.0);
        raw.put("WHEAT", 0.0);
        raw.put("CARROT", -1.0);
        raw.put("POTATO", null);

        Map<String, Double> kept = ConfigValidator.requirePositiveValues("liquidity.farm-output-costs",
                raw, this::warn);

        assertEquals(Map.of("DIAMOND", 4.0), kept);
        assertEquals(3, warnings.size(), "one warning per dropped entry: " + warnings);
        assertTrue(warnings.stream().allMatch(w -> w.contains("liquidity.farm-output-costs")), warnings.toString());
    }

    @Test
    void survivingMapEntriesKeepTheirConfiguredOrder() {
        // The order an operator wrote is the order the warnings and any later listing read in.
        Map<String, Integer> raw = new LinkedHashMap<>();
        raw.put("ZOMBIE_HEAD", 1);
        raw.put("APPLE", 0);
        raw.put("BEETROOT", 2);

        Map<String, Integer> kept = ConfigValidator.requirePositiveValues("limits.buy-limits", raw, this::warn);

        assertIterableEquals(List.of("ZOMBIE_HEAD", "BEETROOT"), kept.keySet());
    }

    @Test
    void anIntegerMapAndADoubleMapAreJudgedByTheSameRuleAndWordedTheSameWay() {
        // One generic validator serves both maps; this pins that an Integer 0 is refused exactly
        // as a Double 0.0 is, rather than slipping through some numeric conversion.
        Map<String, Integer> ints = new LinkedHashMap<>();
        ints.put("DIAMOND", 0);
        Map<String, Double> doubles = new LinkedHashMap<>();
        doubles.put("DIAMOND", 0.0);

        assertTrue(ConfigValidator.requirePositiveValues("limits.buy-limits", ints, this::warn).isEmpty());
        assertTrue(ConfigValidator.requirePositiveValues("limits.buy-limits", doubles, this::warn).isEmpty());

        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("DIAMOND") && warnings.get(1).contains("DIAMOND"), warnings.toString());
    }

    // ---- wording ---------------------------------------------------------------------------

    @Test
    void everyRejectionCarriesTheGreppablePrefixAndTheKey() {
        ConfigValidator.requireInRange("a.int", 5, 0, 1, 0, this::warn);
        ConfigValidator.requireInRange("a.double", 5.0, 0.0, 1.0, 0.0, this::warn);
        ConfigValidator.requireAtLeast("a.least", -1, 0, 0, this::warn);
        ConfigValidator.requireAbove("a.above", 0, 0, 1, this::warn);
        ConfigValidator.requireAbove("a.aboved", 0.0, 0.0, 1.0, this::warn);
        ConfigValidator.requireOneOf("a.oneof", "nope", Set.of("yes"), "yes", this::warn);
        ConfigValidator.requireNonBlank("a.blank", "", "x", this::warn);
        ConfigValidator.requireEnum("a.enum", "nope", Optional.empty(), BarGlyphs.DENSITY_4, this::warn);
        ConfigValidator.requirePositiveValues("a.map", Map.of("K", 0), this::warn);
        ConfigValidator.reportUnreadable("a.unreadable", "lots", 7, this::warn);

        assertEquals(10, warnings.size(), "every validator must report exactly one warning: " + warnings);
        for (String warning : warnings) {
            assertTrue(warning.startsWith("FarmersMarket config: "),
                    "an operator greps one prefix for all of these: " + warning);
        }
        assertTrue(warnings.get(9).contains("unreadable value 'lots'"), warnings.get(9));
    }

    @Test
    void everyRejectionAvoidsTheGlyphsGeyserCannotRender() {
        // These strings reach the operator console rather than a Bedrock client, but the reload
        // command echoes them into chat, where a block-ramp glyph renders as blank space.
        ConfigValidator.requireInRange("a.int", 5, 0, 1, 0, this::warn);
        ConfigValidator.requireOneOf("a.oneof", "nope", Set.of("yes"), "yes", this::warn);
        ConfigValidator.reportUnreadable("a.unreadable", "lots", 7, this::warn);

        for (String warning : warnings) {
            for (char forbidden = '▁'; forbidden <= '▇'; forbidden++) {
                assertFalse(warning.indexOf(forbidden) >= 0,
                        "block-ramp glyph U+" + Integer.toHexString(forbidden) + " in: " + warning);
            }
            assertFalse(warning.indexOf('§') >= 0, "raw legacy formatting code in: " + warning);
        }
    }
}
