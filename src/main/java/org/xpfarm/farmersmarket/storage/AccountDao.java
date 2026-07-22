/*
 * FarmersMarket - reads and writes the accounts and account_links tables.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Every read and write this plugin performs against {@code accounts} and
 * {@code account_links}.
 *
 * <p>Every statement here is a {@link PreparedStatement}; no method ever concatenates a
 * caller-supplied value into SQL text. This class does not run its own transactions or own
 * threading -- callers that need either wrap it in {@link DatabaseExecutor#submit}, since
 * {@link Database} holds the one connection SQLite ever gets from this plugin.
 *
 * <p>None of this class's methods declare {@code throws SQLException} away: a caller
 * running on {@link DatabaseExecutor} lets it surface as the cause of the returned
 * {@code CompletableFuture}'s exceptional completion, which is the failure signal Task 4's
 * ledger is built to react to.
 */
public final class AccountDao {

    private final Database database;

    public AccountDao(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * The current balance for {@code uuid}, in dust.
     *
     * <p>Returns {@code 0} for a UUID with no row at all, rather than throwing or requiring
     * an {@link Optional} -- an account nobody has ever touched is indistinguishable from
     * one holding nothing, and forcing every balance check to unwrap an {@code Optional}
     * for that case would be pure noise. {@link #findAccount} exists for callers that need
     * to tell the two apart.
     *
     * @param uuid the account to read
     * @return the balance in dust, or {@code 0} if no row exists for {@code uuid}
     */
    public long balanceDust(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        String sql = "SELECT diamonds_dust FROM accounts WHERE uuid = ?";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("diamonds_dust") : 0L;
            }
        }
    }

    /**
     * Inserts a fresh row for {@code uuid} at {@code diamondsDust}, or overwrites the
     * balance and {@code updated_at} of the row already there.
     *
     * <p>A single statement handles both cases via
     * {@code ON CONFLICT(uuid) DO UPDATE}, so this never races itself the way a
     * read-then-decide-then-write sequence could. {@code created_at} is only ever set on
     * the insert branch -- an existing row's creation time never changes.
     *
     * <p>{@code diamonds_dust} must be non-negative; the table's own
     * {@code CHECK (diamonds_dust >= 0)} constraint rejects a negative value with a
     * {@link SQLException} rather than trusting every call site to have checked first.
     *
     * @param uuid          the account to write
     * @param diamondsDust  the new balance, in dust
     * @throws SQLException if the write fails, including a {@code CHECK} constraint
     *                       violation for a negative balance
     */
    public void upsertBalance(UUID uuid, long diamondsDust) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO accounts (uuid, diamonds_dust, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET diamonds_dust = ?, updated_at = ?
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, diamondsDust);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setLong(5, diamondsDust);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    /**
     * The full row for {@code uuid}, if one exists.
     *
     * @param uuid the account to read
     * @return the row, or {@link Optional#empty()} if {@code uuid} has never been written
     */
    public Optional<AccountRow> findAccount(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        String sql = "SELECT uuid, diamonds_dust, created_at, updated_at FROM accounts WHERE uuid = ?";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AccountRow(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getLong("diamonds_dust"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")));
            }
        }
    }

    /**
     * Records that {@code floodgateUuid} has been merged into {@code javaUuid}.
     *
     * <p>Re-inserting the same {@code floodgateUuid} overwrites the target and timestamp
     * rather than failing, since {@code floodgate_uuid} is the primary key: a Bedrock
     * account can only ever point at one Java account at a time, so a second merge for the
     * same source is a correction, not a conflict.
     *
     * @param floodgateUuid   the synthetic, XUID-derived UUID Floodgate assigned before
     *                        linking
     * @param javaUuid        the real Java-edition UUID the account merged into
     * @param mergedAtEpochMs when the merge happened, epoch milliseconds
     */
    public void insertLink(UUID floodgateUuid, UUID javaUuid, long mergedAtEpochMs) throws SQLException {
        Objects.requireNonNull(floodgateUuid, "floodgateUuid");
        Objects.requireNonNull(javaUuid, "javaUuid");
        String sql = """
                INSERT INTO account_links (floodgate_uuid, java_uuid, merged_at)
                VALUES (?, ?, ?)
                ON CONFLICT(floodgate_uuid) DO UPDATE SET java_uuid = ?, merged_at = ?
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, floodgateUuid.toString());
            ps.setString(2, javaUuid.toString());
            ps.setLong(3, mergedAtEpochMs);
            ps.setString(4, javaUuid.toString());
            ps.setLong(5, mergedAtEpochMs);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes the account row for {@code uuid}, if one exists.
     *
     * @param uuid the account to remove
     */
    public void deleteAccount(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        String sql = "DELETE FROM accounts WHERE uuid = ?";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Every recorded Floodgate-to-Java account link.
     *
     * @return one {@code {floodgateUuid, javaUuid}} pair per row of {@code account_links},
     *         in no particular order; empty if none have been recorded
     */
    public List<UUID[]> allLinks() throws SQLException {
        String sql = "SELECT floodgate_uuid, java_uuid FROM account_links";
        List<UUID[]> links = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                links.add(new UUID[] {
                        UUID.fromString(rs.getString("floodgate_uuid")),
                        UUID.fromString(rs.getString("java_uuid"))
                });
            }
        }
        return links;
    }
}
