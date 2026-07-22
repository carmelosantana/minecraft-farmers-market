/*
 * FarmersMarket - unit tests for Database and Migrations.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Database#open} and {@link Migrations#applyTo} against a real SQLite file
 * under a JUnit {@code @TempDir} -- no mocking, since mocking the database would prove
 * nothing about whether the schema, pragmas, or idempotency actually hold.
 */
class MigrationsTest {

    @Test
    void appliesSchemaFromScratchAndIsIdempotent(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("market.db");
        try (Database database = Database.open(db, dir.resolve("tmp").toString(), 5000)) {
            int first = Migrations.applyTo(database.connection());
            int second = Migrations.applyTo(database.connection());

            assertEquals(first, second, "re-applying migrations must be a no-op");
            assertTrue(first >= 1);
            assertTrue(tableExists(database.connection(), "accounts"));
            assertTrue(tableExists(database.connection(), "account_links"));
            assertTrue(tableExists(database.connection(), "schema_version"));
        }
    }

    @Test
    void enablesWalAndForeignKeys(@TempDir Path dir) throws Exception {
        try (Database database = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            assertEquals("wal", scalar(database.connection(), "PRAGMA journal_mode").toLowerCase(Locale.ROOT));
            assertEquals("1", scalar(database.connection(), "PRAGMA foreign_keys"));
        }
    }

    @Test
    void createsConfiguredTmpdirIfMissing(@TempDir Path dir) throws Exception {
        Path tmpdir = dir.resolve("does/not/exist/yet");
        assertTrue(java.nio.file.Files.notExists(tmpdir), "precondition: tmpdir must not already exist");

        try (Database database = Database.open(dir.resolve("m.db"), tmpdir.toString(), 5000)) {
            assertTrue(java.nio.file.Files.isDirectory(tmpdir));
        }
    }

    @Test
    void rejectsSchemaCreatedFromAHigherVersionThanKnown(@TempDir Path dir) throws Exception {
        // Simulates an older jar (this build, knowing only MIGRATIONS.size() migrations)
        // opening a database a newer jar already migrated further. applyTo must refuse
        // outright rather than "handling" it by rewriting schema_version down to what this
        // build knows -- that rewrite is exactly the bug this test exists to catch: it would
        // make the newer jar replay migrations 2..99 on its next run, against data that
        // already has them.
        try (Database database = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            Migrations.applyTo(database.connection());
            setSchemaVersion(database.connection(), 99);

            assertThrows(IllegalStateException.class, () -> Migrations.applyTo(database.connection()));

            // The assertion that actually catches the bug: schema_version must be untouched,
            // not silently rewritten down to this build's MIGRATIONS.size().
            assertEquals(99, readSchemaVersion(database.connection()));
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
            return rs.next();
        }
    }

    private static String scalar(Connection connection, String pragma) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(pragma)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Force-writes {@code schema_version} to {@code version}, bypassing {@link Migrations}. */
    private static void setSchemaVersion(Connection connection, int version) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM schema_version");
        }
        try (var ps = connection.prepareStatement("INSERT INTO schema_version(version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private static int readSchemaVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
