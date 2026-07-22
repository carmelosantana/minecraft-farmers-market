/*
 * FarmersMarket - read-only, Bukkit-free view over configuration key/value pairs.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import java.util.List;
import java.util.Map;

/**
 * Read-only, Bukkit-free view of a configuration tree addressed by dotted paths.
 *
 * <p>This is the seam that keeps {@link FmConfig} testable without a running server:
 * production code hands it a {@link BukkitConfigSource} wrapping the plugin's
 * {@code config.yml}, while tests hand it a plain map.
 *
 * <p><b>Contract:</b> every getter returns {@code def} when the path is absent, and also
 * when the stored value cannot be coerced to the requested type. Coercion is the
 * implementation's business -- range validation is not, and lives in {@link FmConfig}.
 * No getter ever throws.
 */
public interface ConfigSource {

    /** The {@code int} at {@code path}, or {@code def} if absent or not a number. */
    int getInt(String path, int def);

    /** The {@code long} at {@code path}, or {@code def} if absent or not a number. */
    long getLong(String path, long def);

    /** The {@code double} at {@code path}, or {@code def} if absent or not a number. */
    double getDouble(String path, double def);

    /** The {@code boolean} at {@code path}, or {@code def} if absent or not a boolean. */
    boolean getBoolean(String path, boolean def);

    /** The {@code String} at {@code path}, or {@code def} if absent. */
    String getString(String path, String def);

    /** The string list at {@code path}, or an empty list if absent. Never {@code null}. */
    List<String> getStringList(String path);

    /**
     * The child mapping at {@code path} with every value coerced to {@code double}, skipping
     * entries whose value is not a number. An absent path yields an empty map. Never
     * {@code null}.
     */
    Map<String, Double> getDoubleMap(String path);

    /**
     * The child mapping at {@code path} with every value coerced to {@code int}, skipping
     * entries whose value is not a number. An absent path yields an empty map. Never
     * {@code null}.
     */
    Map<String, Integer> getIntMap(String path);
}
