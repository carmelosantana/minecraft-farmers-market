/*
 * FarmersMarket - ConfigSource adapter over a Bukkit ConfigurationSection.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link ConfigSource} adapter over a Bukkit {@link ConfigurationSection}.
 *
 * <p>This is the only class in the {@code config} package that touches {@code org.bukkit}.
 * Keeping every other class in the package free of that import is what lets {@link FmConfig}
 * be unit tested without a running server.
 *
 * <p>A {@code null} section is treated as an entirely empty configuration, so every key
 * falls back to its default.
 *
 * <p><b>Uncoercible values.</b> {@code ConfigurationSection} returns the caller's default for
 * a value of the wrong type -- {@code getInt} only accepts a {@link Number} and
 * {@code getBoolean} only a {@link Boolean}, so a quoted YAML scalar such as
 * {@code sales-tax-percent: "lots"} is silently ignored. Silence is exactly the wrong answer
 * for an operator typo, so this adapter inspects the raw value first and reports the
 * substitution through the warning sink given to its constructor before returning the
 * default.
 */
public final class BukkitConfigSource implements ConfigSource {

    private final ConfigurationSection section;
    private final Consumer<String> warn;

    /**
     * @param section the section to read, or {@code null} for an empty configuration
     * @param warn    sink for warnings about present-but-unreadable values
     */
    public BukkitConfigSource(ConfigurationSection section, Consumer<String> warn) {
        this.section = section;
        this.warn = warn;
    }

    @Override
    public int getInt(String path, int def) {
        return section == null || rejectMismatch(path, Number.class, def) ? def : section.getInt(path, def);
    }

    @Override
    public long getLong(String path, long def) {
        return section == null || rejectMismatch(path, Number.class, def) ? def : section.getLong(path, def);
    }

    @Override
    public double getDouble(String path, double def) {
        return section == null || rejectMismatch(path, Number.class, def) ? def : section.getDouble(path, def);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return section == null || rejectMismatch(path, Boolean.class, def) ? def : section.getBoolean(path, def);
    }

    @Override
    public String getString(String path, String def) {
        // Any present value renders as a string, so there is no mismatch case here.
        return section == null ? def : section.getString(path, def);
    }

    @Override
    public List<String> getStringList(String path) {
        return section == null ? List.of() : section.getStringList(path);
    }

    @Override
    public Map<String, Double> getDoubleMap(String path) {
        return coerceMap(path, raw -> raw instanceof Number number ? number.doubleValue() : null);
    }

    @Override
    public Map<String, Integer> getIntMap(String path) {
        return coerceMap(path, raw -> raw instanceof Number number ? number.intValue() : null);
    }

    /**
     * Reads the child section at {@code path} and coerces each entry's value with
     * {@code coerce}, skipping entries {@code coerce} maps to {@code null} -- e.g. a
     * non-numeric value in an item-price map. An absent or empty section yields an empty
     * map. Never {@code null}.
     */
    private <T> Map<String, T> coerceMap(String path, Function<Object, T> coerce) {
        if (section == null) {
            return Map.of();
        }
        ConfigurationSection child = section.getConfigurationSection(path);
        if (child == null) {
            return Map.of();
        }
        Map<String, T> result = new LinkedHashMap<>();
        for (String key : child.getKeys(false)) {
            T value = coerce.apply(child.get(key));
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Whether {@code path} holds a value that {@code ConfigurationSection} would refuse to
     * coerce to {@code expected}; warns as a side effect when it does. An absent key is not a
     * mismatch -- falling back to a default is the documented behaviour there, and warning
     * about it would spam every operator running a partial config.
     */
    private boolean rejectMismatch(String path, Class<?> expected, Object fallback) {
        if (!section.isSet(path)) {
            return false;
        }
        Object raw = section.get(path);
        if (raw == null || expected.isInstance(raw)) {
            return false;
        }
        warn.accept("FarmersMarket config: key '" + path + "' has unreadable value '" + raw
                + "'; using default '" + fallback + "' instead.");
        return true;
    }
}
