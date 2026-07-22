/*
 * FarmersMarket - owns the plugin's single SQLite connection, its pragmas, and its tmpdir.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * The one JDBC {@link Connection} this plugin ever opens to its SQLite database file.
 *
 * <p>SQLite serialises writers regardless of connection count, so this class deliberately
 * holds exactly one connection rather than pooling; see the Global Constraints in the M1
 * plan. Callers that need concurrency run their work through {@link DatabaseExecutor}
 * instead of opening a second {@code Database}.
 *
 * <p><b>Tmpdir ordering is load-bearing.</b> {@code sqlite-jdbc} extracts its native
 * library into {@code java.io.tmpdir} the first time the driver class loads, and a
 * production container that mounts {@code /tmp} {@code noexec} fails that extraction with
 * {@link UnsatisfiedLinkError}. {@link #open} therefore creates the configured tmpdir and
 * sets the {@code org.sqlite.tmpdir} system property <em>before</em> the first call that
 * could trigger the driver to load -- {@link DriverManager#getConnection}. Setting the
 * property again on a later call in the same JVM is harmless but has no further effect,
 * since the native library has already been extracted.
 *
 * <p>Every pragma this class sets is per-connection state that SQLite does not persist in
 * the database file -- most importantly {@code foreign_keys}, which silently reverts to
 * off on a fresh connection that forgets to set it. Setting all four here, once, on the
 * only connection this plugin opens, is what keeps that invariant from depending on every
 * future caller remembering it.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens the single connection to {@code dbFile}, preparing the SQLite native-library
     * tmpdir first and applying the plugin's pragmas before returning.
     *
     * @param dbFile         path to the SQLite database file; parent directories are not
     *                       created by this method, only {@code sqliteTmpdir} is
     * @param sqliteTmpdir   directory {@code org.sqlite.tmpdir} is pointed at; created if
     *                       missing
     * @param busyTimeoutMs  value applied to {@code PRAGMA busy_timeout}, in milliseconds
     * @return an open {@code Database} with WAL journalling, {@code synchronous=NORMAL},
     *         the given busy timeout, and foreign keys already enabled
     * @throws IOException  if {@code sqliteTmpdir} could not be created
     * @throws SQLException if the connection could not be opened or a pragma failed
     */
    public static Database open(Path dbFile, String sqliteTmpdir, int busyTimeoutMs)
            throws IOException, SQLException {
        Objects.requireNonNull(dbFile, "dbFile");
        Objects.requireNonNull(sqliteTmpdir, "sqliteTmpdir");
        prepareTmpdir(sqliteTmpdir);

        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return new Database(connection);
    }

    /**
     * Creates {@code sqliteTmpdir} if it does not exist and points {@code org.sqlite.tmpdir}
     * at it, in that order -- the property is worthless to set before the directory exists,
     * and worthless to set after the driver has already extracted its native library.
     */
    private static void prepareTmpdir(String sqliteTmpdir) throws IOException {
        Path dir = Path.of(sqliteTmpdir);
        Files.createDirectories(dir);
        System.setProperty("org.sqlite.tmpdir", sqliteTmpdir);
    }

    /**
     * The one connection this {@code Database} owns.
     *
     * @return the open connection; never {@code null}
     */
    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
