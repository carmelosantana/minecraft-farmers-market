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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

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

    @Test
    void alwaysJavaNeverReportsAPreLinkBedrockUuid() {
        // No Floodgate means no link to find, which means the join listener performs no merge --
        // exactly the behaviour a server without Floodgate had before this method existed.
        assertTrue(EditionResolver.alwaysJava().linkedBedrockUuid(UUID.randomUUID()).isEmpty());
        assertTrue(EditionResolver.alwaysJava().linkedBedrockUuid(null).isEmpty());
    }

    /**
     * Floodgate hands back its API objects as non-public implementation classes behind public
     * interfaces, and resolving a method on the implementation makes {@code invoke} throw
     * {@link IllegalAccessException}. {@code List.of(...)} has exactly that shape and is on every
     * classpath, so the two tests below exercise the real problem rather than a described one.
     */
    @Test
    void invokePublicReachesAMethodDeclaredOnlyOnANonPublicClass() throws Exception {
        List<Integer> hidden = List.of(1, 2, 3);
        assertFalse(Modifier.isPublic(hidden.getClass().getModifiers()),
                "this test is pointless unless the implementation class really is non-public");

        // The naive route, which is what this helper exists instead of.
        assertThrows(IllegalAccessException.class,
                () -> hidden.getClass().getMethod("size").invoke(hidden));

        assertEquals(3, EditionResolver.invokePublic(hidden, "size", new Class<?>[0]));
    }

    @Test
    void publicTypesOfSkipsTheNonPublicImplementationAndKeepsItsPublicInterface() {
        List<Class<?>> types = EditionResolver.publicTypesOf(List.of(1, 2, 3).getClass());

        assertFalse(types.contains(List.of(1, 2, 3).getClass()),
                "a non-public class must not be offered as somewhere to resolve a method");
        assertTrue(types.contains(List.class), "the public interface must survive: " + types);
    }

    @Test
    void invokePublicRefusesAMethodThatDoesNotExist() {
        assertThrows(NoSuchMethodException.class,
                () -> EditionResolver.invokePublic(List.of(1), "getLinkedPlayer", new Class<?>[0]));
    }
}
