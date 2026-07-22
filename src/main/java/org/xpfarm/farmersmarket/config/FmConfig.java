/*
 * FarmersMarket - validated, immutable settings for the whole plugin.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Immutable, fully validated snapshot of {@code config.yml}.
 *
 * <p>Built via {@link #load}, which never throws and never prevents the plugin from
 * enabling: any missing, out-of-range, or wrong-typed value is replaced with its
 * documented default and reported through the supplied warning sink. A server operator
 * with a typo in {@code config.yml} still gets a fully working plugin.
 *
 * <p>The one deliberate exception is the buy-back faucet audit -- see {@link #load} --
 * which is fail-closed rather than fail-open, because an unsafe buy-back price is worse
 * than no buy-back at all.
 *
 * <p>This class deliberately imports nothing from {@code org.bukkit}. Reading is delegated
 * to a {@link ConfigSource}, so the whole parsing contract is unit testable without a
 * server; see {@link BukkitConfigSource} for the production wiring.
 *
 * <p>Instances are only ever produced by {@link #load}; the constructor is private so no
 * caller can construct an unvalidated instance.
 */
public final class FmConfig {

    /** Default for {@code stalls.bid-currency}. */
    public static final String DEFAULT_STALL_BID_CURRENCY = "xp";

    /** Default for {@code storage.sqlite-tmpdir}. */
    public static final String DEFAULT_SQLITE_TMPDIR = "plugins/FarmersMarket/tmp";

    private static final Set<String> STALL_BID_CURRENCIES = Set.of("xp", "diamonds");

    private final double listingFeePercent;
    private final double salesTaxPercent;
    private final double taxBurnShare;
    private final int xpPerDiamond;
    private final int listingDurationDays;
    private final int maxListingsPerPlayer;
    private final boolean buybackEnabled;
    private final Map<String, Double> buybackFloors;
    private final Map<String, Double> farmOutputCosts;
    private final boolean farmOutputAudit;
    private final boolean buyLimitEnabled;
    private final int buyLimitWindowHours;
    private final Map<String, Integer> buyLimits;
    private final int minPlaytimeHours;
    private final int maxVendorsPerPlayer;
    private final VendorPlacementMode vendorPlacementMode;
    private final int vendorMinDistanceBlocks;
    private final boolean vendorLabelEnabled;
    private final boolean stallsEnabled;
    private final String stallBidCurrency;
    private final int stallBidPeriodHours;
    private final int maxStallsPerPlayer;
    private final BarGlyphs barGlyphs;
    private final boolean chartsEnabled;
    private final int chartRefreshSeconds;
    private final boolean bedrockTouchSimplify;
    private final List<String> indexBasket;
    private final double outlierFilterMad;
    private final int historyRetentionDays;
    private final String sqliteTmpdir;
    private final int busyTimeoutMs;

    private FmConfig(
            double listingFeePercent,
            double salesTaxPercent,
            double taxBurnShare,
            int xpPerDiamond,
            int listingDurationDays,
            int maxListingsPerPlayer,
            boolean buybackEnabled,
            Map<String, Double> buybackFloors,
            Map<String, Double> farmOutputCosts,
            boolean farmOutputAudit,
            boolean buyLimitEnabled,
            int buyLimitWindowHours,
            Map<String, Integer> buyLimits,
            int minPlaytimeHours,
            int maxVendorsPerPlayer,
            VendorPlacementMode vendorPlacementMode,
            int vendorMinDistanceBlocks,
            boolean vendorLabelEnabled,
            boolean stallsEnabled,
            String stallBidCurrency,
            int stallBidPeriodHours,
            int maxStallsPerPlayer,
            BarGlyphs barGlyphs,
            boolean chartsEnabled,
            int chartRefreshSeconds,
            boolean bedrockTouchSimplify,
            List<String> indexBasket,
            double outlierFilterMad,
            int historyRetentionDays,
            String sqliteTmpdir,
            int busyTimeoutMs) {
        this.listingFeePercent = listingFeePercent;
        this.salesTaxPercent = salesTaxPercent;
        this.taxBurnShare = taxBurnShare;
        this.xpPerDiamond = xpPerDiamond;
        this.listingDurationDays = listingDurationDays;
        this.maxListingsPerPlayer = maxListingsPerPlayer;
        this.buybackEnabled = buybackEnabled;
        this.buybackFloors = Map.copyOf(buybackFloors);
        this.farmOutputCosts = Map.copyOf(farmOutputCosts);
        this.farmOutputAudit = farmOutputAudit;
        this.buyLimitEnabled = buyLimitEnabled;
        this.buyLimitWindowHours = buyLimitWindowHours;
        this.buyLimits = Map.copyOf(buyLimits);
        this.minPlaytimeHours = minPlaytimeHours;
        this.maxVendorsPerPlayer = maxVendorsPerPlayer;
        this.vendorPlacementMode = vendorPlacementMode;
        this.vendorMinDistanceBlocks = vendorMinDistanceBlocks;
        this.vendorLabelEnabled = vendorLabelEnabled;
        this.stallsEnabled = stallsEnabled;
        this.stallBidCurrency = stallBidCurrency;
        this.stallBidPeriodHours = stallBidPeriodHours;
        this.maxStallsPerPlayer = maxStallsPerPlayer;
        this.barGlyphs = barGlyphs;
        this.chartsEnabled = chartsEnabled;
        this.chartRefreshSeconds = chartRefreshSeconds;
        this.bedrockTouchSimplify = bedrockTouchSimplify;
        this.indexBasket = List.copyOf(indexBasket);
        this.outlierFilterMad = outlierFilterMad;
        this.historyRetentionDays = historyRetentionDays;
        this.sqliteTmpdir = sqliteTmpdir;
        this.busyTimeoutMs = busyTimeoutMs;
    }

    /**
     * Loads and validates configuration from {@code source}.
     *
     * <p>Every key documented in {@code config.yml} is read and range-checked here. An
     * out-of-range or wrong-typed value logs exactly one warning naming the key, the
     * offending value, and the substituted default -- it never throws and never prevents
     * the plugin from enabling.
     *
     * <p><b>The buy-back faucet audit</b> is the deliberate exception, applied to
     * {@code liquidity.buyback-floors} after both liquidity maps are read. For each entry:
     * if {@code liquidity.farm-output-audit} is {@code false}, the entry is kept and exactly
     * one warning is emitted for the whole config naming {@code farm-output-audit} as
     * disabled. Otherwise the entry is dropped -- and warned about -- if there is no
     * matching {@code liquidity.farm-output-costs} entry, or if the floor is at or above
     * that cost. A buy-back floor at or above real production cost is an infinite money
     * faucet; an item nobody has estimated a cost for is the same danger in disguise, so
     * both fail closed rather than silently loading an unsafe price.
     *
     * @param source the configuration to read from; an empty source yields the documented
     *               defaults with no warnings
     * @param warn   sink for human-readable warnings naming the offending key, value, and
     *               substituted default
     * @return a fully validated, immutable configuration snapshot
     */
    public static FmConfig load(ConfigSource source, Consumer<String> warn) {
        double listingFeePercent = requireDoubleInRange("economy.listing-fee-percent",
                source.getDouble("economy.listing-fee-percent", 1.0), 0.0, 100.0, 1.0, warn);
        double salesTaxPercent = requireDoubleInRange("economy.sales-tax-percent",
                source.getDouble("economy.sales-tax-percent", 7.0), 0.0, 100.0, 7.0, warn);
        double taxBurnShare = requireDoubleInRange("economy.tax-burn-share",
                source.getDouble("economy.tax-burn-share", 0.5), 0.0, 1.0, 0.5, warn);
        int xpPerDiamond = requireIntAbove("economy.xp-per-diamond",
                source.getInt("economy.xp-per-diamond", 40), 0, 40, warn);
        int listingDurationDays = requireIntInRange("economy.listing-duration-days",
                source.getInt("economy.listing-duration-days", 14), 1, 90, 14, warn);
        int maxListingsPerPlayer = requireIntAbove("economy.max-listings-per-player",
                source.getInt("economy.max-listings-per-player", 40), 0, 40, warn);

        boolean buybackEnabled = source.getBoolean("liquidity.buyback-enabled", true);
        Map<String, Double> rawBuybackFloors = source.getDoubleMap("liquidity.buyback-floors");
        Map<String, Double> farmOutputCosts = requirePositiveDoubleMap("liquidity.farm-output-costs",
                source.getDoubleMap("liquidity.farm-output-costs"), warn);
        boolean farmOutputAudit = source.getBoolean("liquidity.farm-output-audit", true);
        Map<String, Double> buybackFloors =
                resolveBuybackFloors(rawBuybackFloors, farmOutputCosts, farmOutputAudit, warn);

        boolean buyLimitEnabled = source.getBoolean("limits.buy-limit-enabled", true);
        int buyLimitWindowHours = requireIntInRange("limits.buy-limit-window-hours",
                source.getInt("limits.buy-limit-window-hours", 4), 1, 168, 4, warn);
        Map<String, Integer> buyLimits = requirePositiveIntMap("limits.buy-limits",
                source.getIntMap("limits.buy-limits"), warn);
        int minPlaytimeHours = requireIntAtLeast("limits.min-playtime-hours",
                source.getInt("limits.min-playtime-hours", 2), 0, 2, warn);

        int maxVendorsPerPlayer = requireIntAbove("vendor.max-per-player",
                source.getInt("vendor.max-per-player", 2), 0, 2, warn);
        String rawPlacementMode = source.getString("vendor.placement-mode", "owned-and-district");
        VendorPlacementMode vendorPlacementMode = requireEnum("vendor.placement-mode", rawPlacementMode,
                VendorPlacementMode.parse(rawPlacementMode), VendorPlacementMode.OWNED_AND_DISTRICT, warn);
        int vendorMinDistanceBlocks = requireIntAtLeast("vendor.min-distance-blocks",
                source.getInt("vendor.min-distance-blocks", 24), 0, 24, warn);
        boolean vendorLabelEnabled = source.getBoolean("vendor.label-enabled", true);

        boolean stallsEnabled = source.getBoolean("stalls.enabled", true);
        String stallBidCurrency = requireOneOf("stalls.bid-currency",
                source.getString("stalls.bid-currency", DEFAULT_STALL_BID_CURRENCY), STALL_BID_CURRENCIES,
                DEFAULT_STALL_BID_CURRENCY, warn);
        int stallBidPeriodHours = requireIntAbove("stalls.bid-period-hours",
                source.getInt("stalls.bid-period-hours", 168), 0, 168, warn);
        int maxStallsPerPlayer = requireIntAbove("stalls.max-stalls-per-player",
                source.getInt("stalls.max-stalls-per-player", 1), 0, 1, warn);

        String rawBarGlyphs = source.getString("ui.bar-glyphs", "density-4");
        BarGlyphs barGlyphs = requireEnum("ui.bar-glyphs", rawBarGlyphs, BarGlyphs.parse(rawBarGlyphs),
                BarGlyphs.DENSITY_4, warn);
        boolean chartsEnabled = source.getBoolean("ui.charts-enabled", true);
        int chartRefreshSeconds = requireIntAtLeast("ui.chart-refresh-seconds",
                source.getInt("ui.chart-refresh-seconds", 60), 10, 60, warn);
        boolean bedrockTouchSimplify = source.getBoolean("ui.bedrock-touch-simplify", true);

        List<String> indexBasket = source.getStringList("analytics.index-basket");
        double outlierFilterMad = requireDoubleAbove("analytics.outlier-filter-mad",
                source.getDouble("analytics.outlier-filter-mad", 3.0), 0.0, 3.0, warn);
        int historyRetentionDays = requireIntAbove("analytics.history-retention-days",
                source.getInt("analytics.history-retention-days", 365), 0, 365, warn);

        String sqliteTmpdir = requireNonBlank("storage.sqlite-tmpdir",
                source.getString("storage.sqlite-tmpdir", DEFAULT_SQLITE_TMPDIR), DEFAULT_SQLITE_TMPDIR, warn);
        int busyTimeoutMs = requireIntAbove("storage.busy-timeout-ms",
                source.getInt("storage.busy-timeout-ms", 5000), 0, 5000, warn);

        return new FmConfig(
                listingFeePercent, salesTaxPercent, taxBurnShare, xpPerDiamond, listingDurationDays,
                maxListingsPerPlayer,
                buybackEnabled, buybackFloors, farmOutputCosts, farmOutputAudit,
                buyLimitEnabled, buyLimitWindowHours, buyLimits, minPlaytimeHours,
                maxVendorsPerPlayer, vendorPlacementMode, vendorMinDistanceBlocks, vendorLabelEnabled,
                stallsEnabled, stallBidCurrency, stallBidPeriodHours, maxStallsPerPlayer,
                barGlyphs, chartsEnabled, chartRefreshSeconds, bedrockTouchSimplify,
                indexBasket, outlierFilterMad, historyRetentionDays,
                sqliteTmpdir, busyTimeoutMs
        );
    }

    // ---- accessors -------------------------------------------------------------------

    public double listingFeePercent() {
        return listingFeePercent;
    }

    public double salesTaxPercent() {
        return salesTaxPercent;
    }

    public double taxBurnShare() {
        return taxBurnShare;
    }

    public int xpPerDiamond() {
        return xpPerDiamond;
    }

    public int listingDurationDays() {
        return listingDurationDays;
    }

    public int maxListingsPerPlayer() {
        return maxListingsPerPlayer;
    }

    public boolean buybackEnabled() {
        return buybackEnabled;
    }

    /** Post-audit survivors only; see {@link #load}. Unmodifiable, never {@code null}. */
    public Map<String, Double> buybackFloors() {
        return buybackFloors;
    }

    /** Positive entries only. Unmodifiable, never {@code null}. */
    public Map<String, Double> farmOutputCosts() {
        return farmOutputCosts;
    }

    public boolean farmOutputAudit() {
        return farmOutputAudit;
    }

    public boolean buyLimitEnabled() {
        return buyLimitEnabled;
    }

    public int buyLimitWindowHours() {
        return buyLimitWindowHours;
    }

    /** Positive entries only. Unmodifiable, never {@code null}. */
    public Map<String, Integer> buyLimits() {
        return buyLimits;
    }

    public int minPlaytimeHours() {
        return minPlaytimeHours;
    }

    public int maxVendorsPerPlayer() {
        return maxVendorsPerPlayer;
    }

    public VendorPlacementMode vendorPlacementMode() {
        return vendorPlacementMode;
    }

    public int vendorMinDistanceBlocks() {
        return vendorMinDistanceBlocks;
    }

    public boolean vendorLabelEnabled() {
        return vendorLabelEnabled;
    }

    public boolean stallsEnabled() {
        return stallsEnabled;
    }

    /** Either {@code "xp"} or {@code "diamonds"}, normalized to lowercase. */
    public String stallBidCurrency() {
        return stallBidCurrency;
    }

    public int stallBidPeriodHours() {
        return stallBidPeriodHours;
    }

    public int maxStallsPerPlayer() {
        return maxStallsPerPlayer;
    }

    public BarGlyphs barGlyphs() {
        return barGlyphs;
    }

    public boolean chartsEnabled() {
        return chartsEnabled;
    }

    public int chartRefreshSeconds() {
        return chartRefreshSeconds;
    }

    public boolean bedrockTouchSimplify() {
        return bedrockTouchSimplify;
    }

    /** Unmodifiable, never {@code null}. */
    public List<String> indexBasket() {
        return indexBasket;
    }

    public double outlierFilterMad() {
        return outlierFilterMad;
    }

    public int historyRetentionDays() {
        return historyRetentionDays;
    }

    public String sqliteTmpdir() {
        return sqliteTmpdir;
    }

    public int busyTimeoutMs() {
        return busyTimeoutMs;
    }

    // ---- validation helpers -----------------------------------------------------------

    /**
     * Applies the buy-back faucet audit described on {@link #load}. Returns only the
     * surviving floor entries.
     */
    private static Map<String, Double> resolveBuybackFloors(Map<String, Double> rawFloors,
            Map<String, Double> farmOutputCosts, boolean auditEnabled, Consumer<String> warn) {
        if (rawFloors.isEmpty()) {
            return Map.of();
        }
        if (!auditEnabled) {
            warn.accept("FarmersMarket config: liquidity.farm-output-audit is disabled -- "
                    + "liquidity.buyback-floors entries are loading WITHOUT being checked against "
                    + "production cost. An unsafe floor here is an infinite money faucet; only "
                    + "disable this for testing.");
            return Map.copyOf(rawFloors);
        }

        Map<String, Double> surviving = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : rawFloors.entrySet()) {
            String item = entry.getKey();
            double floor = entry.getValue();
            Double cost = farmOutputCosts.get(item);
            if (cost == null) {
                warn.accept("FarmersMarket config: liquidity.buyback-floors entry '" + item
                        + "' has no matching liquidity.farm-output-costs entry; refusing to load it "
                        + "(an unestimated item is the dangerous case, so this fails closed).");
            } else if (floor >= cost) {
                warn.accept("FarmersMarket config: liquidity.buyback-floors entry '" + item + "' (" + floor
                        + ") is at or above its liquidity.farm-output-costs entry (" + cost
                        + "); refusing to load it (this would be an infinite money faucet).");
            } else {
                surviving.put(item, floor);
            }
        }
        return surviving;
    }

    /** Drops non-positive entries from an item-cost map, warning about each one dropped. */
    private static Map<String, Double> requirePositiveDoubleMap(String key, Map<String, Double> raw,
            Consumer<String> warn) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                warn.accept("FarmersMarket config: " + key + " entry '" + entry.getKey()
                        + "' has non-positive value '" + entry.getValue() + "'; entry ignored.");
            }
        }
        return result;
    }

    /** Drops non-positive entries from an item-quantity map, warning about each one dropped. */
    private static Map<String, Integer> requirePositiveIntMap(String key, Map<String, Integer> raw,
            Consumer<String> warn) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                warn.accept("FarmersMarket config: " + key + " entry '" + entry.getKey()
                        + "' has non-positive value '" + entry.getValue() + "'; entry ignored.");
            }
        }
        return result;
    }

    /** Rejects an {@code int} outside {@code [min, max]}, substituting {@code fallback}. */
    private static int requireIntInRange(String key, int value, int min, int max, int fallback,
            Consumer<String> warn) {
        if (value < min || value > max) {
            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects an {@code int} below {@code min}, substituting {@code fallback}. */
    private static int requireIntAtLeast(String key, int value, int min, int fallback, Consumer<String> warn) {
        if (value < min) {
            warn.accept(belowMinimumMessage(key, value, min, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects an {@code int} that is not strictly greater than {@code min}, e.g. {@code > 0}. */
    private static int requireIntAbove(String key, int value, int min, int fallback, Consumer<String> warn) {
        if (value <= min) {
            warn.accept(mustExceedMessage(key, value, min, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects a {@code double} outside {@code [min, max]}, substituting {@code fallback}. */
    private static double requireDoubleInRange(String key, double value, double min, double max, double fallback,
            Consumer<String> warn) {
        if (Double.isNaN(value) || value < min || value > max) {
            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects a {@code double} that is not strictly greater than {@code min}, e.g. {@code > 0}. */
    private static double requireDoubleAbove(String key, double value, double min, double fallback,
            Consumer<String> warn) {
        if (Double.isNaN(value) || value <= min) {
            warn.accept(mustExceedMessage(key, value, min, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects a value not present in {@code allowed} (case-insensitively), substituting {@code fallback}. */
    private static String requireOneOf(String key, String value, Set<String> allowed, String fallback,
            Consumer<String> warn) {
        String candidate = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (candidate == null || !allowed.contains(candidate)) {
            warn.accept("FarmersMarket config: key '" + key + "' has invalid value '" + value
                    + "'; using default '" + fallback + "' instead.");
            return fallback;
        }
        return candidate;
    }

    /** Rejects a blank or {@code null} value, substituting {@code fallback}. */
    private static String requireNonBlank(String key, String value, String fallback, Consumer<String> warn) {
        if (value == null || value.isBlank()) {
            warn.accept("FarmersMarket config: key '" + key + "' has blank value; using default '"
                    + fallback + "' instead.");
            return fallback;
        }
        return value;
    }

    /** Substitutes {@code fallback} when {@code parsed} did not resolve to an enum constant. */
    private static <E extends Enum<E>> E requireEnum(String key, String rawValue, Optional<E> parsed, E fallback,
            Consumer<String> warn) {
        if (parsed.isPresent()) {
            return parsed.get();
        }
        warn.accept("FarmersMarket config: key '" + key + "' has unknown value '" + rawValue
                + "'; using default '" + fallback + "' instead.");
        return fallback;
    }

    private static String outOfRangeMessage(String key, Object value, Object min, Object max, Object fallback) {
        return "FarmersMarket config: key '" + key + "' has out-of-range value '" + value
                + "' (must be between " + min + " and " + max + "); using default '" + fallback + "' instead.";
    }

    private static String belowMinimumMessage(String key, Object value, Object min, Object fallback) {
        return "FarmersMarket config: key '" + key + "' has out-of-range value '" + value
                + "' (must be at least " + min + "); using default '" + fallback + "' instead.";
    }

    private static String mustExceedMessage(String key, Object value, Object min, Object fallback) {
        return "FarmersMarket config: key '" + key + "' has out-of-range value '" + value
                + "' (must be greater than " + min + "); using default '" + fallback + "' instead.";
    }
}
