/*
 * FarmersMarket - unit tests for the money-safe transaction primitive.
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
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link TransactionRunner} against a real, migrated SQLite file under a JUnit
 * {@code @TempDir}. Nothing is mocked: the property under test -- that a failure between a write
 * and the commit rolls the write back rather than leaving it committed -- is a property of the
 * SQL that actually runs, and a mocked connection would assert nothing about it.
 *
 * <p>This is the testable surface {@code LedgerTest} used to reach through the package-private
 * {@code Ledger.inTransaction}, moved to where the primitive now lives.
 */
class TransactionRunnerTest {

    @Test
    void committedWorkPersists(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            Migrations.applyTo(db.connection());
            TransactionRunner runner = new TransactionRunner(db);
            runner.inTransaction(() -> {
                try (var st = db.connection().createStatement()) {
                    st.execute("INSERT INTO accounts(uuid,diamonds_dust,created_at,updated_at) "
                            + "VALUES ('a',5,1,1)");
                }
                return null;
            });
            assertEquals(5L, scalarLong(db.connection(), "SELECT diamonds_dust FROM accounts WHERE uuid='a'"));
        }
    }

    @Test
    void aThrowingBodyRollsBackEverythingItWrote(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            Migrations.applyTo(db.connection());
            TransactionRunner runner = new TransactionRunner(db);
            assertThrows(IllegalStateException.class, () -> runner.inTransaction(() -> {
                try (var st = db.connection().createStatement()) {
                    st.execute("INSERT INTO accounts(uuid,diamonds_dust,created_at,updated_at) "
                            + "VALUES ('a',5,1,1)");
                }
                throw new IllegalStateException("boom after the write");
            }));
            assertEquals(0L, scalarLong(db.connection(), "SELECT COUNT(*) FROM accounts WHERE uuid='a'"));
        }
    }

    @Test
    void anErrorNotAnExceptionStillRollsBackAndStillPropagates(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            Migrations.applyTo(db.connection());
            TransactionRunner runner = new TransactionRunner(db);
            // An Error between a write and the commit is the money-destruction case: a narrower
            // catch(Exception) would skip rollback, and restoring autocommit COMMITs the orphan.
            assertThrows(OutOfMemoryError.class, () -> runner.inTransaction(() -> {
                try (var st = db.connection().createStatement()) {
                    st.execute("INSERT INTO accounts(uuid,diamonds_dust,created_at,updated_at) "
                            + "VALUES ('a',5,1,1)");
                }
                throw new OutOfMemoryError("simulated");
            }));
            assertEquals(0L, scalarLong(db.connection(), "SELECT COUNT(*) FROM accounts WHERE uuid='a'"),
                    "an Error must roll back exactly as an Exception does, or a debit commits with no credit");
        }
    }

    @Test
    void nestingIsRefusedOutright(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            Migrations.applyTo(db.connection());
            TransactionRunner runner = new TransactionRunner(db);
            assertThrows(IllegalStateException.class, () -> runner.inTransaction(() ->
                    runner.inTransaction(() -> null)));
        }
    }

    @Test
    void twoDifferentRunnersOverOneConnectionStillRefuseToNest(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
            Migrations.applyTo(db.connection());
            // The whole reason the guard reads the connection's autocommit state rather than an
            // instance re-entrancy count: the ledger holds one runner and MarketService holds
            // another over the SAME connection, and an inner transaction on the second, opened
            // from inside the first's, must still be refused. The single-instance sibling above
            // could pass on a purely instance-based guard; this one cannot.
            TransactionRunner outer = new TransactionRunner(db);
            TransactionRunner inner = new TransactionRunner(db);
            assertThrows(IllegalStateException.class, () -> outer.inTransaction(() ->
                    inner.inTransaction(() -> null)));
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
