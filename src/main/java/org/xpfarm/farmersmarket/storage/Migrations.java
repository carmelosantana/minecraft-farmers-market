/*
 * FarmersMarket - ordered, idempotent SQLite schema migrations.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Applies every schema migration this plugin has ever shipped, in order, exactly once each.
 *
 * <p>The current schema version lives in its own one-row {@code schema_version} table
 * rather than {@code PRAGMA user_version}, so it can be read and written through ordinary
 * prepared statements alongside everything else {@link #applyTo} does inside one
 * transaction. Each element of {@link #MIGRATIONS} is one migration's statements, applied
 * in the array's own order; migration {@code N} (1-indexed) brings the schema from version
 * {@code N - 1} to version {@code N}.
 *
 * <p>{@link #applyTo} is idempotent by construction: it only ever runs the migrations
 * strictly beyond the version already recorded, so calling it twice in a row on the same
 * connection is a no-op the second time and returns the same version both times.
 */
public final class Migrations {

    /**
     * Migration 1: the accounts ledger and the Floodgate account-link table.
     *
     * <p>{@code CHECK (diamonds_dust >= 0)} is deliberate, not incidental -- a negative
     * balance is a bug the database itself refuses, rather than a rule application code
     * has to remember to enforce on every write path. {@code idx_account_links_java} exists
     * because a link is always looked up by the Java-edition side once a Bedrock player's
     * account has been merged; without it that lookup would be a full table scan.
     */
    private static final String[] MIGRATION_1 = {
            """
            CREATE TABLE IF NOT EXISTS accounts (
                uuid            TEXT    PRIMARY KEY NOT NULL,
                diamonds_dust   INTEGER NOT NULL DEFAULT 0,
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL,
                CHECK (diamonds_dust >= 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS account_links (
                floodgate_uuid  TEXT    PRIMARY KEY NOT NULL,
                java_uuid       TEXT    NOT NULL,
                merged_at       INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_account_links_java ON account_links(java_uuid)"
    };

    /** Every migration this plugin has ever shipped, oldest first. Never mutate in place. */
    private static final List<String[]> MIGRATIONS = List.<String[]>of(MIGRATION_1);

    private Migrations() {
    }

    /**
     * Brings {@code connection}'s schema up to the newest version {@link #MIGRATIONS} knows
     * about, applying only the migrations beyond whatever version is already recorded, all
     * inside one transaction.
     *
     * @param connection the connection to migrate; its autocommit mode is restored to
     *                   whatever it was before this call returns, whether or not it succeeds
     * @return the resulting schema version, equal to {@link #MIGRATIONS}'s size
     * <p><b>Every failure rolls back, {@code Throwable} and not {@code Exception}, and that
     * distinction is the schema.</b> An {@link Error} part-way through a migration would skip a
     * narrower {@code catch}, and restoring autocommit on the way out is implemented by the
     * driver as a {@code COMMIT} of the still-open transaction -- committing a half-applied
     * schema and recording nothing about it. That is survivable today only because every
     * statement in {@code MIGRATION_1} is {@code CREATE ... IF NOT EXISTS}; the first
     * {@code ALTER TABLE ... ADD COLUMN} makes it permanent damage. Neither the rollback nor the
     * autocommit restoration may replace the failure that caused them -- both are attached to
     * the original as suppressed exceptions instead. This mirrors
     * {@code Ledger.inTransaction} exactly, deliberately.
     *
     * @throws SQLException          if creating the version table, reading it, applying a
     *                                pending migration, or writing the new version fails;
     *                                the transaction is rolled back first so a failed
     *                                migration never leaves a partial schema committed
     * @throws IllegalStateException if the recorded version is already higher than the
     *                                highest version this build knows -- see the fail-closed
     *                                note above; nothing is written in this case, not even a
     *                                partial transaction, because nothing was started
     */
    public static int applyTo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }

        // Read-only check, deliberately outside the transaction below: an older jar opening a
        // database a newer jar already migrated is a situation only a human can resolve, not
        // one this method may "handle". The alternative -- running zero migrations but still
        // rewriting schema_version down to this build's MIGRATIONS.size() -- would make the
        // next run of the *newer* jar believe the migrations it already applied still need to
        // run again, replaying anything non-idempotent (e.g. ALTER TABLE ... ADD COLUMN) onto
        // data that already has it. Failing here, before touching the transaction at all,
        // guarantees schema_version is never rewritten in this case.
        int current = readVersion(connection);
        if (current > MIGRATIONS.size()) {
            throw new IllegalStateException(
                    "FarmersMarket database schema_version is " + current + ", but this build's "
                            + "highest known migration is " + MIGRATIONS.size() + ". This jar is older "
                            + "than the database it just opened; refusing to apply any migration or "
                            + "touch schema_version. Run the newer jar against this database instead, "
                            + "or restore a database backup matching this build.");
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try {
            for (int i = current; i < MIGRATIONS.size(); i++) {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : MIGRATIONS.get(i)) {
                        statement.execute(sql);
                    }
                }
            }
            int newVersion = MIGRATIONS.size();
            if (newVersion != current) {
                writeVersion(connection, newVersion);
            }
            connection.commit();
            return newVersion;
        } catch (Throwable t) {
            failure = t;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                t.addSuppressed(rollbackFailure);
            }
            throw t;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreFailure) {
                if (failure == null) {
                    throw restoreFailure;
                }
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    /** Reads the recorded version, or {@code 0} if {@code schema_version} has no row yet. */
    private static int readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            return rs.next() ? rs.getInt("version") : 0;
        }
    }

    /** Replaces whatever single row {@code schema_version} holds with {@code version}. */
    private static void writeVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM schema_version");
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO schema_version(version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }
}
