/*
 * FarmersMarket - tests for the server-independent parts of edition resolution.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Covers only the parts of {@link EditionResolver} reachable without a running Bukkit server.
 *
 * <p>{@link EditionResolver#create(java.util.logging.Logger)} calls
 * {@code Bukkit.getPluginManager()}, which needs a running server, so it is not exercised here.
 * {@link EditionResolver#attemptLink(java.util.logging.Logger)} does not touch Bukkit at all -
 * it is pure reflection against Floodgate's API class - and this module has no Floodgate
 * dependency on its test classpath, so the "Floodgate absent" failure path is exercised for
 * real rather than simulated. {@link EditionResolver#isBedrock(java.util.UUID)} on an
 * {@link EditionResolver#alwaysJava()} instance never dereferences its {@code UUID} argument,
 * so it is callable with {@code null} without needing a real player.
 */
final class EditionResolverTest {

    @Test
    void attemptLinkReturnsNullWhenFloodgateClassIsAbsent() {
        // Floodgate is a soft dependency and is never on this module's classpath, so this
        // exercises the real "class not found" failure path, not a simulated one.
        assertNull(EditionResolver.attemptLink(null));
    }

    @Test
    void alwaysJavaNeverReportsBedrock() {
        EditionResolver resolver = EditionResolver.alwaysJava();

        // The unlinked resolver returns before touching its argument, so null is safe here.
        assertFalse(resolver.isBedrock(null));
    }
}
