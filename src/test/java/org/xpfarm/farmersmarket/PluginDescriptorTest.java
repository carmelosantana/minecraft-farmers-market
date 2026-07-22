/*
 * FarmersMarket - validates the shipped resource YAML the same way a Paper server parses it.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses the shipped resource YAML with the same SnakeYAML the server uses.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A malformed {@code plugin.yml} is not a compile error, is not a test failure, and does not
 * fail {@code mvn verify} — Maven copies the file into the JAR and it is only parsed when a real
 * Paper server boots. Magic Carpet shipped a descriptor whose unquoted {@code ": "} inside the
 * description made SnakeYAML read the rest of the line as a nested mapping and throw
 * {@code ScannerException: mapping values are not allowed here}. Paper logged
 * {@code InvalidDescriptionException} and never registered the plugin at all — it was absent from
 * {@code /plugins} rather than present-and-disabled, a materially more confusing symptom. The
 * defect survived every per-task review, an adversarial whole-branch review, and a green CI run,
 * because nothing in the pipeline ever parsed the file as YAML.
 *
 * <p>These tests close that gap at gate 6 instead of gate 7a.
 *
 * <p>The command and permission assertions below start from what {@code plugin.yml} declares.
 * {@code minecraft-plugin-dev} tightens them to what the code actually looks up as it writes
 * that code — every node passed to {@code hasPermission(...)} gains an assertion here.
 */
final class PluginDescriptorTest {

    private static final Path PLUGIN_YML = descriptor("plugin.yml");
    private static final Path CONFIG_YML = descriptor("config.yml");

    /**
     * Prefers the Maven-filtered copy in {@code target/classes} — that is the file that actually
     * ships, and property substitution can inject YAML metacharacters the source file never had.
     * Falls back to the source tree so the test still runs before {@code process-resources}.
     */
    private static Path descriptor(String name) {
        Path filtered = Path.of("target", "classes", name);
        return Files.exists(filtered) ? filtered : Path.of("src", "main", "resources", name);
    }

    private static Map<String, Object> parse(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    @Test
    void pluginYmlIsValidYaml() throws IOException {
        assertNotNull(parse(PLUGIN_YML), "plugin.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void configYmlIsValidYaml() throws IOException {
        assertNotNull(parse(CONFIG_YML), "config.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void pluginYmlDeclaresTheFieldsPaperRequires() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        assertEquals("FarmersMarket", parsed.get("name"));
        assertEquals("org.xpfarm.farmersmarket.FarmersMarketPlugin", parsed.get("main"));
        assertInstanceOf(String.class, parsed.get("api-version"),
                "api-version must be quoted; unquoted it parses as a double and 1.20 becomes 1.2");
        assertEquals("26.1", parsed.get("api-version"));
        assertNotNull(parsed.get("description"), "description is required");

        Object version = parsed.get("version");
        assertNotNull(version, "version is required");
        assertFalse(version.toString().contains("${"),
                "version still holds an unresolved Maven property: " + version);
    }

    @Test
    void pluginYmlDeclaresEveryCommandTheCodeLooksUp() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) parse(PLUGIN_YML).get("commands");
        assertNotNull(commands, "commands section is required");
        assertTrue(commands.containsKey("market"),
                "the market command must be declared or getCommand(\"market\") returns null");
    }

    @Test
    void pluginYmlDeclaresEveryPermissionTheCodeChecks() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");
        assertNotNull(permissions, "permissions section is required");

        for (String node : new String[] {
            "farmersmarket.use", "farmersmarket.admin", "farmersmarket.admin.reload",
        }) {
            assertTrue(permissions.containsKey(node), node + " must be declared");
        }
    }

    /**
     * The mirror of the test above, and the reason both exist. Declaring a permission for a
     * feature no code implements tells an operator they have a knob that does nothing; worse,
     * it lets a future edit start <em>checking</em> a node nobody re-examined the meaning of.
     * Each later milestone re-adds its own nodes alongside the code that reads them.
     */
    @Test
    void pluginYmlDoesNotDeclarePermissionsForUnbuiltMilestones() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");
        assertNotNull(permissions, "permissions section is required");

        for (String unbuilt : new String[] {
            "farmersmarket.vendor.place", "farmersmarket.stall.rent", "farmersmarket.chart",
            "farmersmarket.admin.floor", "farmersmarket.admin.audit",
            "farmersmarket.admin.freeze", "farmersmarket.admin.pot",
            "farmersmarket.bypass.fees", "farmersmarket.bypass.buylimit",
        }) {
            assertFalse(permissions.containsKey(unbuilt),
                    unbuilt + " belongs to a later milestone and must not be declared yet");
        }
    }

    /**
     * The {@code farmersmarket.admin} parent must not grant a child node that no longer exists.
     * Paper registers an undeclared child as a permission in its own right, so a stale entry
     * here quietly re-creates exactly the node the test above just removed.
     */
    @Test
    void adminParentOnlyGrantsChildrenThatStillExist() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");
        @SuppressWarnings("unchecked")
        Map<String, Object> admin = (Map<String, Object>) permissions.get("farmersmarket.admin");
        assertNotNull(admin, "farmersmarket.admin must be declared");

        @SuppressWarnings("unchecked")
        Map<String, Object> children = (Map<String, Object>) admin.get("children");
        assertNotNull(children, "farmersmarket.admin must declare its children");
        for (String child : children.keySet()) {
            assertTrue(permissions.containsKey(child),
                    "farmersmarket.admin grants '" + child + "', which is not declared anywhere");
        }
    }

    /** The usage line an operator reads must name the subcommands that actually exist. */
    @Test
    void marketUsageNamesOnlyTheSubcommandsM1Implements() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) parse(PLUGIN_YML).get("commands");
        @SuppressWarnings("unchecked")
        Map<String, Object> market = (Map<String, Object>) commands.get("market");
        assertNotNull(market, "the market command must be declared");

        assertEquals("/market [balance | deposit | withdraw | reload]", market.get("usage"));
    }

    @Test
    void pluginYmlDeclaresItsSoftDependencies() throws IOException {
        Object softdepend = parse(PLUGIN_YML).get("softdepend");
        assertNotNull(softdepend, "softdepend is required");
        assertTrue(softdepend.toString().contains("Floodgate"),
                "Floodgate must be a soft dependency — it is reached reflectively for Bedrock detection");
    }

    /**
     * The runtime SQLite driver is declared rather than shaded, because Paper's bundled
     * {@code org.xerial:sqlite-jdbc} is marked legacy in Paper's own build file. A missing
     * {@code libraries:} entry means the plugin enables and then fails on first query.
     */
    @Test
    void pluginYmlDeclaresTheSqliteDriverItLoadsAtRuntime() throws IOException {
        Object libraries = parse(PLUGIN_YML).get("libraries");
        assertNotNull(libraries, "libraries is required — the SQLite driver is not shaded");
        assertTrue(libraries.toString().contains("org.xerial:sqlite-jdbc"),
                "the SQLite JDBC driver must be declared in libraries");
    }
}
