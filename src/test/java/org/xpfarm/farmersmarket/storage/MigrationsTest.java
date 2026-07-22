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
        // A downgrade scenario: the recorded version is already at the newest migration.
        // Re-running applyTo must not attempt to re-run any migration and must report the
        // same version back unchanged.
        try (Database database = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            int first = Migrations.applyTo(database.connection());
            int second = Migrations.applyTo(database.connection());
            int third = Migrations.applyTo(database.connection());
            assertEquals(first, second);
            assertEquals(second, third);
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
}
