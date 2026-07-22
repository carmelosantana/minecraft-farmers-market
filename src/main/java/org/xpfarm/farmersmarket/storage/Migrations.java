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
     * @throws SQLException if creating the version table, reading it, applying a pending
     *                       migration, or writing the new version fails; the transaction is
     *                       rolled back first so a failed migration never leaves a partial
     *                       schema committed
     */
    public static int applyTo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int current = readVersion(connection);
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
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
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
