/*
 * FarmersMarket - reflective, absent-tolerant detection of a player's client edition.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.identity;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Tells whether a player is connected through Floodgate (a Bedrock player behind Geyser),
 * keyed by {@link UUID} so callers never need a live {@code Player} object on hand to ask.
 *
 * <p>Floodgate is a <em>soft</em> dependency: it is never on this plugin's compile classpath
 * and its class must never be linked when the plugin is absent from the server. This class
 * therefore checks {@code Bukkit.getPluginManager().isPluginEnabled("floodgate")} exactly once,
 * at construction, and only then reaches for Floodgate's API via {@link Class#forName}. Any
 * failure anywhere in that chain - the plugin absent, the class missing, the expected method
 * missing, a reflective invocation failure - resolves every player to Java edition.
 */
public final class EditionResolver {

    private static final String FLOODGATE_PLUGIN_NAME = "floodgate";
    private static final String FLOODGATE_API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";
    private static final String IS_FLOODGATE_PLAYER_METHOD = "isFloodgatePlayer";

    private final Object floodgateApi;
    private final Method isFloodgatePlayerMethod;

    private EditionResolver(Object floodgateApi, Method isFloodgatePlayerMethod) {
        this.floodgateApi = floodgateApi;
        this.isFloodgatePlayerMethod = isFloodgatePlayerMethod;
    }

    /**
     * A resolver that reports every player as Java edition without ever touching Floodgate.
     * Used when Floodgate is confirmed absent, and as the fallback for any linking failure.
     */
    public static EditionResolver alwaysJava() {
        return new EditionResolver(null, null);
    }

    /**
     * Builds the real resolver for a running server. Checks whether Floodgate is enabled
     * exactly once; if it is not, returns {@link #alwaysJava()} without ever calling
     * {@link Class#forName} on a Floodgate class. If Floodgate is enabled but the reflective
     * link fails for any reason, logs a warning (when {@code logger} is non-null) and also
     * falls back to {@link #alwaysJava()}.
     */
    public static EditionResolver create(Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled(FLOODGATE_PLUGIN_NAME)) {
            return alwaysJava();
        }
        EditionResolver linked = attemptLink(logger);
        return linked != null ? linked : alwaysJava();
    }

    /**
     * Attempts the reflective link to Floodgate's API, independent of the plugin-manager check
     * so it can be exercised directly in a test with no Bukkit server present. Returns
     * {@code null} on any failure - split out from {@link #create(Logger)} purely so this
     * server-independent half of the logic is unit-testable.
     */
    static EditionResolver attemptLink(Logger logger) {
        try {
            Class<?> apiClass = Class.forName(FLOODGATE_API_CLASS);
            Object instance = apiClass.getMethod("getInstance").invoke(null);
            Method method = apiClass.getMethod(IS_FLOODGATE_PLAYER_METHOD, UUID.class);
            return new EditionResolver(instance, method);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (logger != null) {
                logger.log(Level.WARNING,
                        "Floodgate is enabled but its API could not be linked reflectively; "
                                + "treating every player as Java edition",
                        e);
            }
            return null;
        }
    }

    /**
     * True when {@code uuid} belongs to a player connected through Floodgate. Never throws: a
     * reflective failure at call time resolves to {@code false} (Java edition), same as
     * Floodgate being absent.
     */
    public boolean isBedrock(UUID uuid) {
        if (isFloodgatePlayerMethod == null) {
            return false;
        }
        try {
            Object result = isFloodgatePlayerMethod.invoke(floodgateApi, uuid);
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
