/*
 * FarmersMarket - pure range and type validation for config.yml values.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pure, Bukkit-free range and type validation for {@code config.yml} values.
 *
 * <p>Problems are reported through a caller-supplied {@link Consumer} rather than logged
 * directly, so {@code ConfigValidatorTest} can exercise the whole contract with zero
 * {@code org.bukkit} types and no running server.
 *
 * <p><b>Contract:</b> an out-of-range value is <em>not</em> clamped to the nearest bound -- it
 * is replaced entirely by the documented default, and exactly one warning naming the key, the
 * offending value, and the substituted default is emitted. This class never throws.
 *
 * <p>Extracted from {@link FmConfig} so the two have separate reasons to change: this one owns
 * how a value is judged and how the rejection reads, {@link FmConfig} owns which keys exist and
 * what their bounds are. It matches the shape of the sibling plugin's
 * {@code org.xpfarm.timberblast.config.ConfigValidator} deliberately -- M2 through M5 copy
 * whatever shape M1 leaves behind, and one shape across both plugins is worth more than a
 * marginally nicer second one.
 */
public final class ConfigValidator {

    /** Prefix every warning carries, so an operator can grep one string for all of them. */
    private static final String PREFIX = "FarmersMarket config: ";

    private ConfigValidator() {
    }

    /** Rejects an {@code int} outside {@code [min, max]}, substituting {@code fallback}. */
    public static int requireInRange(String key, int value, int min, int max, int fallback,
            Consumer<String> warn) {
        if (value < min || value > max) {
            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects a {@code double} outside {@code [min, max]}, substituting {@code fallback}. */
    public static double requireInRange(String key, double value, double min, double max, double fallback,
            Consumer<String> warn) {
        if (Double.isNaN(value) || value < min || value > max) {
            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects an {@code int} below {@code min}, substituting {@code fallback}. */
    public static int requireAtLeast(String key, int value, int min, int fallback, Consumer<String> warn) {
        if (value < min) {
            warn.accept(belowMinimumMessage(key, value, min, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects an {@code int} that is not strictly greater than {@code min}, e.g. {@code > 0}. */
    public static int requireAbove(String key, int value, int min, int fallback, Consumer<String> warn) {
        if (value <= min) {
            warn.accept(mustExceedMessage(key, value, min, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects a {@code double} that is not strictly greater than {@code min}, e.g. {@code > 0}. */
    public static double requireAbove(String key, double value, double min, double fallback,
            Consumer<String> warn) {
        if (Double.isNaN(value) || value <= min) {
            warn.accept(mustExceedMessage(key, value, min, fallback));
            return fallback;
        }
        return value;
    }

    /** Rejects a value not present in {@code allowed} (case-insensitively), substituting {@code fallback}. */
    public static String requireOneOf(String key, String value, Set<String> allowed, String fallback,
            Consumer<String> warn) {
        String candidate = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (candidate == null || !allowed.contains(candidate)) {
            warn.accept(PREFIX + "key '" + key + "' has invalid value '" + value
                    + "'; using default '" + fallback + "' instead.");
            return fallback;
        }
        return candidate;
    }

    /** Rejects a blank or {@code null} value, substituting {@code fallback}. */
    public static String requireNonBlank(String key, String value, String fallback, Consumer<String> warn) {
        if (value == null || value.isBlank()) {
            warn.accept(PREFIX + "key '" + key + "' has blank value; using default '"
                    + fallback + "' instead.");
            return fallback;
        }
        return value;
    }

    /**
     * Substitutes {@code fallback} when {@code parsed} did not resolve to an enum constant.
     *
     * <p>The parse is done by the enum itself and handed in already attempted, rather than being
     * performed here through {@code Enum.valueOf}: the enums in this package accept
     * {@code kebab-case} config spellings that {@code valueOf} would reject outright.
     */
    public static <E extends Enum<E>> E requireEnum(String key, String rawValue, Optional<E> parsed, E fallback,
            Consumer<String> warn) {
        if (parsed.isPresent()) {
            return parsed.get();
        }
        warn.accept(PREFIX + "key '" + key + "' has unknown value '" + rawValue
                + "'; using default '" + fallback + "' instead.");
        return fallback;
    }

    /**
     * Drops every entry of an item-keyed map whose value is {@code null} or not positive, warning
     * about each one dropped.
     *
     * <p>Generic over the value type because the two maps this validates -- item costs
     * ({@code Double}) and per-item buy limits ({@code Integer}) -- reject on exactly the same
     * rule and should say so in exactly the same words. Comparison goes through
     * {@link Number#doubleValue()}, which is exact for every {@code Integer} and every
     * {@code Double} a YAML file can hold.
     *
     * @return a new map preserving the iteration order of {@code raw}; never {@code null}
     */
    public static <V extends Number> Map<String, V> requirePositiveValues(String key, Map<String, V> raw,
            Consumer<String> warn) {
        Map<String, V> result = new LinkedHashMap<>();
        for (Map.Entry<String, V> entry : raw.entrySet()) {
            if (entry.getValue() != null && entry.getValue().doubleValue() > 0) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                warn.accept(PREFIX + key + " entry '" + entry.getKey()
                        + "' has non-positive value '" + entry.getValue() + "'; entry ignored.");
            }
        }
        return result;
    }

    /**
     * Reports a key that is present in the configuration but whose stored value cannot be read as
     * the requested type -- an operator typo such as {@code sales-tax-percent: "lots"}.
     *
     * <p>Detecting this is the {@link ConfigSource} implementation's job (only it can see the raw
     * value); phrasing the warning lives here so every rejection reads alike. See
     * {@link BukkitConfigSource}.
     *
     * @param key      the dotted configuration path
     * @param value    the raw, uncoercible value as stored
     * @param fallback the default being substituted
     * @param warn     the warning sink
     */
    public static void reportUnreadable(String key, Object value, Object fallback, Consumer<String> warn) {
        warn.accept(PREFIX + "key '" + key + "' has unreadable value '" + value
                + "'; using default '" + fallback + "' instead.");
    }

    private static String outOfRangeMessage(String key, Object value, Object min, Object max, Object fallback) {
        return PREFIX + "key '" + key + "' has out-of-range value '" + value
                + "' (must be between " + min + " and " + max + "); using default '" + fallback + "' instead.";
    }

    private static String belowMinimumMessage(String key, Object value, Object min, Object fallback) {
        return PREFIX + "key '" + key + "' has out-of-range value '" + value
                + "' (must be at least " + min + "); using default '" + fallback + "' instead.";
    }

    private static String mustExceedMessage(String key, Object value, Object min, Object fallback) {
        return PREFIX + "key '" + key + "' has out-of-range value '" + value
                + "' (must be greater than " + min + "); using default '" + fallback + "' instead.";
    }
}
