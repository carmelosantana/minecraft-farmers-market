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
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * missing, a reflective invocation failure, or a {@link LinkageError} from a half-installed
 * Floodgate whose classes cannot be resolved - resolves every player to Java edition. An
 * {@code Error} that is not a linkage failure is not swallowed anywhere in this class.
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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
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
     * True when {@code uuid} belongs to a player connected through Floodgate. A reflective
     * failure at call time resolves to {@code false} (Java edition), same as Floodgate being
     * absent.
     *
     * <p>{@link LinkageError} is caught alongside the reflective failures because a
     * half-installed or version-mismatched Floodgate throws {@link NoClassDefFoundError} rather
     * than an exception: {@code getMethod} has to resolve the declared return type, and a type
     * it cannot load fails at link time, not reflectively. Without it, every Bedrock join would
     * print a stack trace forever.
     *
     * <p>Deliberately <em>not</em> {@code Error} or {@code Throwable}. Swallowing an
     * {@link OutOfMemoryError} or a {@link StackOverflowError} here to answer "Java edition"
     * would hide a JVM in trouble behind a cosmetic default. Those still propagate.
     */
    public boolean isBedrock(UUID uuid) {
        if (isFloodgatePlayerMethod == null) {
            return false;
        }
        try {
            Object result = isFloodgatePlayerMethod.invoke(floodgateApi, uuid);
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The synthetic UUID {@code uuid} held before they linked a Java account, if they are a
     * Bedrock player who has linked one.
     *
     * <p>Floodgate hands an unlinked Bedrock player a synthetic, XUID-derived UUID. Once they
     * link a Java account it reports the real Java UUID instead and migrates none of the
     * plugin's data, so the balance stored under the old UUID is stranded unless something
     * merges it. That merge needs both UUIDs, and this is the only place the old one is
     * reachable: {@code FloodgateApi.getPlayer(uuid).getLinkedPlayer().getBedrockId()}.
     *
     * <p>Resolves to empty on failure, exactly like {@link #isBedrock}, and on the same terms:
     * Floodgate absent, the player unlinked, an API shape that moved between Floodgate versions,
     * any reflective failure, or a {@link LinkageError} from a half-installed Floodgate all give
     * {@link Optional#empty()}; an {@code Error} that is not a linkage failure still propagates.
     * An empty result means the caller simply performs no
     * merge -- which is the same behaviour as not having this method at all. That is the whole
     * safety argument for reaching three hops deep into someone else's API reflectively.
     *
     * @param uuid the UUID the player is connected under right now
     * @return their pre-link Floodgate UUID, or {@link Optional#empty()} if there is not one to
     *         be had
     */
    public Optional<UUID> linkedBedrockUuid(UUID uuid) {
        if (floodgateApi == null || uuid == null) {
            return Optional.empty();
        }
        try {
            Object player = invokePublic(floodgateApi, "getPlayer", new Class<?>[] {UUID.class}, uuid);
            if (player == null) {
                return Optional.empty();
            }
            Object linked = invokePublic(player, "getLinkedPlayer", new Class<?>[0]);
            if (linked == null) {
                return Optional.empty();
            }
            Object bedrockId = invokePublic(linked, "getBedrockId", new Class<?>[0]);
            return bedrockId instanceof UUID found ? Optional.of(found) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * Invokes {@code name} on {@code target} through a publicly accessible declaration of it.
     *
     * <p>Floodgate returns its API objects as non-public implementation classes behind public
     * interfaces. {@code target.getClass().getMethod(name)} finds the method on the
     * implementation, and invoking that throws {@link IllegalAccessException} because the
     * declaring class is not public -- so the method has to be resolved on the public interface
     * or public superclass instead. Nothing here calls {@code setAccessible}: forcing access into
     * another plugin's internals is exactly the kind of thing that breaks on their next release.
     *
     * <p>Package-private rather than private so {@code EditionResolverTest} can drive it against
     * a JDK type that has the same shape -- {@code List.of(...)} returns a non-public class behind
     * the public {@code List} interface -- since Floodgate is never on this module's classpath.
     * There is no other way to verify the one thing this method exists to do.
     *
     * @throws NoSuchMethodException if no publicly accessible declaration exists
     */
    static Object invokePublic(Object target, String name, Class<?>[] parameterTypes,
            Object... arguments) throws ReflectiveOperationException {
        for (Class<?> type : publicTypesOf(target.getClass())) {
            try {
                return type.getMethod(name, parameterTypes).invoke(target, arguments);
            } catch (NoSuchMethodException tryTheNextOne) {
                // Expected: most types in the hierarchy do not declare this method.
            }
        }
        throw new NoSuchMethodException(name + " is not publicly accessible on "
                + target.getClass().getName());
    }

    /**
     * Every public class and interface {@code type} is assignable to, nearest first, so a method
     * is resolved against the most specific public declaration available.
     *
     * <p>Package-private for the same reason as {@link #invokePublic}.
     */
    static List<Class<?>> publicTypesOf(Class<?> type) {
        List<Class<?>> found = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (Modifier.isPublic(current.getModifiers())) {
                found.add(current);
            }
            // Null-checked because an interface has no superclass and ArrayDeque refuses nulls.
            Class<?> parent = current.getSuperclass();
            if (parent != null) {
                pending.add(parent);
            }
            pending.addAll(List.of(current.getInterfaces()));
        }
        return found;
    }
}
