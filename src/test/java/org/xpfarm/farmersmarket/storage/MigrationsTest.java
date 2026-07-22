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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

            assertTrue(first >= 1);
            assertTrue(tableExists(database.connection(), "accounts"));
            assertTrue(tableExists(database.connection(), "account_links"));
            assertTrue(tableExists(database.connection(), "schema_version"));

            // Pins applyTo's own behaviour, not the SQL's. Every statement in MIGRATION_1 is
            // CREATE ... IF NOT EXISTS, so simply calling applyTo twice and comparing the
            // returned version passes even if the second call re-runs every migration from
            // zero -- which is precisely what M2's first ALTER TABLE ... ADD COLUMN could not
            // survive. Dropping a table the applied migration created makes the difference
            // observable: a second applyTo that re-ran migration 1 would put it back.
            dropTable(database.connection(), "account_links");
            int second = Migrations.applyTo(database.connection());

            assertEquals(first, second, "re-applying migrations must be a no-op");
            assertFalse(tableExists(database.connection(), "account_links"),
                    "applyTo re-ran a migration the recorded version says was already applied; "
                            + "it must only run migrations strictly beyond that version");
        }
    }

    @Test
    void enablesWalNormalSynchronousAndForeignKeys(@TempDir Path dir) throws Exception {
        try (Database database = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            assertEquals("wal", scalar(database.connection(), "PRAGMA journal_mode").toLowerCase(Locale.ROOT));
            assertEquals("1", scalar(database.connection(), "PRAGMA foreign_keys"));
            // synchronous=NORMAL is 1. Under WAL it is the durability/throughput trade-off this
            // plugin deliberately chose; left at the driver's default (FULL, 2) every ledger
            // write pays an extra fsync, and set to OFF (0) a crash can lose committed writes.
            assertEquals("1", scalar(database.connection(), "PRAGMA synchronous"));
        }
    }

    @Test
    void createsConfiguredTmpdirIfMissingAndPointsSqliteAtIt(@TempDir Path dir) throws Exception {
        Path tmpdir = dir.resolve("does/not/exist/yet");
        assertTrue(java.nio.file.Files.notExists(tmpdir), "precondition: tmpdir must not already exist");

        try (Database database = Database.open(dir.resolve("m.db"), tmpdir.toString(), 5000)) {
            assertTrue(java.nio.file.Files.isDirectory(tmpdir));
            // Creating the directory is only half the job: sqlite-jdbc extracts its native
            // library into java.io.tmpdir unless org.sqlite.tmpdir points it elsewhere, and a
            // container with /tmp mounted noexec fails that with UnsatisfiedLinkError. The
            // whole reason the storage.sqlite-tmpdir key exists is this property.
            assertEquals(tmpdir.toString(), System.getProperty("org.sqlite.tmpdir"));
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

    /**
     * An {@link Error} part-way through a migration must roll the whole migration back.
     *
     * <p>Same shape, and the same stakes, as {@code Ledger.inTransaction}: a
     * {@code catch (SQLException)} does not catch an {@code Error}, and the {@code finally} that
     * restores autocommit is implemented by the driver as a {@code COMMIT} of the still-open
     * transaction -- so the statements that had already run would be committed as a
     * half-applied schema, with {@code schema_version} left saying they never ran. Harmless only
     * while every statement is {@code CREATE ... IF NOT EXISTS}; the class javadoc already names
     * {@code ALTER TABLE ... ADD COLUMN} as what M2 adds.
     *
     * <p>The failure is injected with a {@link Proxy} over the JDBC interfaces, because no real
     * connection can be made to throw an {@code Error} on demand and this module ships no
     * mocking framework by design.
     */
    @Test
    void anErrorPartWayThroughAMigrationRollsBackRatherThanCommittingHalfASchema(@TempDir Path dir)
            throws Exception {
        try (Database database = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            Connection real = database.connection();

            assertThrows(StackOverflowError.class,
                    () -> Migrations.applyTo(failingOnStatementsMentioning(real, "account_links")));

            assertFalse(tableExists(real, "accounts"),
                    "the accounts table was created before the failure and then committed by the "
                            + "autocommit restore; every failure must roll back first");
            assertTrue(real.getAutoCommit(), "autocommit must be restored however this fails");
            // And the connection is left usable, not wedged mid-transaction.
            assertEquals(1, Migrations.applyTo(real));
            assertTrue(tableExists(real, "account_links"));
        }
    }

    /**
     * {@code real}, wrapped so that any {@code Statement#execute} whose SQL mentions
     * {@code marker} throws an {@code Error} instead of running. Everything else delegates.
     */
    private static Connection failingOnStatementsMentioning(Connection real, String marker) {
        return (Connection) Proxy.newProxyInstance(
                MigrationsTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = delegate(real, method, args);
                    return "createStatement".equals(method.getName())
                            ? failingStatement((Statement) result, marker)
                            : result;
                });
    }

    private static Statement failingStatement(Statement real, String marker) {
        return (Statement) Proxy.newProxyInstance(
                MigrationsTest.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName()) && args != null && args.length > 0
                            && String.valueOf(args[0]).contains(marker)) {
                        throw new StackOverflowError("simulated JVM-level failure mid-migration");
                    }
                    return delegate(real, method, args);
                });
    }

    /** Invokes {@code method} on {@code target}, unwrapping the reflective failure shell. */
    private static Object delegate(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Removes a table an already-applied migration created, bypassing {@link Migrations}. */
    private static void dropTable(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + tableName);
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
