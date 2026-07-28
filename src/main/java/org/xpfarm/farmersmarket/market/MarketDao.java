/*
 * FarmersMarket - reads and writes the listings, trades, and pending_items tables.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import org.xpfarm.farmersmarket.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Every read and write this plugin performs against {@code listings}, {@code trades}, and
 * {@code pending_items}.
 *
 * <p>Every statement here is a {@link PreparedStatement}; no method concatenates a
 * caller-supplied value into SQL text. This class runs no transactions and owns no threading of
 * its own -- callers that need either wrap it in the database executor, and the sale wraps
 * several of these calls in one {@code TransactionRunner.inTransaction}, since {@link Database}
 * holds the one connection SQLite ever gets from this plugin.
 *
 * <p>There is deliberately no update or delete method for {@code trades}: the log is append-only
 * by design, and the {@code trades_no_update}/{@code trades_no_delete} triggers enforce that at
 * the database level even if such a method were ever added.
 */
public final class MarketDao {

    private final Database database;

    public MarketDao(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    // ---------------------------------------------------------------- listings

    /**
     * Inserts {@code row} into {@code listings} and returns the id SQLite assigned it. The
     * stored {@code status} is taken from {@link ListingRow#status()}.
     *
     * @param row the listing to insert; its {@code id} is ignored and replaced by the generated one
     * @return the generated row id
     * @throws SQLException if the write fails, including a {@code CHECK} violation
     */
    public long insertListing(ListingRow row) throws SQLException {
        Objects.requireNonNull(row, "row");
        String sql = """
                INSERT INTO listings (seller_uuid, item_class, item_key, material_key, display_name,
                        summary, amount, price_dust, item_bytes, listed_at, expires_at, status,
                        sold_at, buyer_uuid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps =
                database.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, row.seller().toString());
            ps.setString(2, row.itemClass().name());
            ps.setString(3, row.itemKey());
            ps.setString(4, row.materialKey());
            ps.setString(5, row.displayName());
            ps.setString(6, row.summary());
            ps.setInt(7, row.amount());
            ps.setLong(8, row.priceDust());
            ps.setBytes(9, row.itemBytes());
            ps.setLong(10, row.listedAtEpochMs());
            ps.setLong(11, row.expiresAtEpochMs());
            ps.setString(12, row.status().name());
            setNullableLong(ps, 13, row.soldAtEpochMs());
            ps.setString(14, row.buyer() == null ? null : row.buyer().toString());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * The full listing with {@code id}, in whatever status it is.
     *
     * @param id the listing id
     * @return the row, or {@link Optional#empty()} if no listing has that id
     */
    public Optional<ListingRow> findListing(long id) throws SQLException {
        String sql = listingSelect() + " WHERE id = ?";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapListing(rs)) : Optional.empty();
            }
        }
    }

    /**
     * The listing with {@code id}, but only while it is {@code ACTIVE}. A sold or cancelled
     * listing is invisible here -- this is the read the sale path uses, so a listing that has
     * left {@code ACTIVE} can never be bought.
     *
     * @param id the listing id
     * @return the row if it exists and is {@code ACTIVE}, otherwise {@link Optional#empty()}
     */
    public Optional<ListingRow> findActiveListing(long id) throws SQLException {
        String sql = listingSelect() + " WHERE id = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapListing(rs)) : Optional.empty();
            }
        }
    }

    /**
     * A page of active {@code UNIQUE} listings, newest first, optionally filtered by material.
     *
     * @param materialLike a SQL {@code LIKE} pattern for {@code material_key}, or {@code null}
     *                     to match every material
     * @param limit        the maximum number of rows to return
     * @param offset       the number of matching rows to skip before this page
     * @return the matching listings, newest {@code listed_at} first
     */
    public List<ListingRow> browseActive(String materialLike, int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder(listingSelect())
                .append(" WHERE status = 'ACTIVE' AND item_class = 'UNIQUE'");
        if (materialLike != null) {
            sql.append(" AND material_key LIKE ?");
        }
        sql.append(" ORDER BY listed_at DESC LIMIT ? OFFSET ?");
        try (PreparedStatement ps = database.connection().prepareStatement(sql.toString())) {
            int i = 1;
            if (materialLike != null) {
                ps.setString(i++, materialLike);
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            return queryListings(ps);
        }
    }

    /**
     * A seller's listings in one status, newest first.
     *
     * @param seller the seller to read
     * @param status the single status to return
     * @return the matching listings, newest {@code listed_at} first
     */
    public List<ListingRow> listingsBySeller(UUID seller, ListingStatus status) throws SQLException {
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(status, "status");
        String sql = listingSelect()
                + " WHERE seller_uuid = ? AND status = ? ORDER BY listed_at DESC";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            ps.setString(2, status.name());
            return queryListings(ps);
        }
    }

    /**
     * How many {@code ACTIVE} listings {@code seller} currently has, for the
     * max-listings-per-player gate.
     *
     * @param seller the seller to count
     * @return the number of active listings owned by {@code seller}
     */
    public int countActiveBySeller(UUID seller) throws SQLException {
        Objects.requireNonNull(seller, "seller");
        String sql = "SELECT COUNT(*) FROM listings WHERE seller_uuid = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Marks the listing sold, but only if it is still {@code ACTIVE}. The caller checks the
     * affected-row count is exactly 1: that guard is what makes a concurrent double-buy safe,
     * since only the first {@code markSold} of an {@code ACTIVE} listing updates a row.
     *
     * @param id            the listing to mark sold
     * @param buyer         the purchasing player
     * @param soldAtEpochMs when the sale completed, epoch milliseconds
     * @throws SQLException if the write fails
     */
    public void markSold(long id, UUID buyer, long soldAtEpochMs) throws SQLException {
        Objects.requireNonNull(buyer, "buyer");
        String sql = "UPDATE listings SET status = 'SOLD', buyer_uuid = ?, sold_at = ? "
                + "WHERE id = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, buyer.toString());
            ps.setLong(2, soldAtEpochMs);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /**
     * Transitions an {@code ACTIVE} listing to {@code CANCELLED} or {@code EXPIRED}, stamping
     * {@code sold_at} with the transition time. Only an {@code ACTIVE} listing is affected.
     *
     * @param id        the listing to transition
     * @param status    the terminal status to move it to
     * @param atEpochMs when the transition happened, epoch milliseconds
     * @throws SQLException if the write fails
     */
    public void markStatus(long id, ListingStatus status, long atEpochMs) throws SQLException {
        Objects.requireNonNull(status, "status");
        String sql = "UPDATE listings SET status = ?, sold_at = ? WHERE id = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, atEpochMs);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /**
     * A bounded batch of {@code ACTIVE} listings whose {@code expires_at} has passed, for the
     * expiry sweep.
     *
     * @param nowEpochMs the cutoff; listings with {@code expires_at <= nowEpochMs} are returned
     * @param limit      the maximum number of rows to return
     * @return the expired-but-still-active listings, oldest expiry first
     */
    public List<ListingRow> dueForExpiry(long nowEpochMs, int limit) throws SQLException {
        String sql = listingSelect()
                + " WHERE status = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at ASC LIMIT ?";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, nowEpochMs);
            ps.setInt(2, limit);
            return queryListings(ps);
        }
    }

    // ------------------------------------------------------------------ trades

    /**
     * Appends {@code row} to the {@code trades} log. Insert only: there is no update or delete
     * counterpart, and the table's triggers refuse both. A row that does not conserve money is
     * refused by the table's {@code CHECK} constraints.
     *
     * @param row the trade to record
     * @throws SQLException if the write fails, including a conservation {@code CHECK} violation
     */
    public void insertTrade(TradeRow row) throws SQLException {
        Objects.requireNonNull(row, "row");
        String sql = """
                INSERT INTO trades (happened_at, buyer_uuid, seller_uuid, item_class, item_key,
                        material_key, amount, gross_dust, tax_dust, tax_burned_dust, tax_pot_dust,
                        net_dust, listing_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, row.happenedAtEpochMs());
            ps.setString(2, row.buyer().toString());
            ps.setString(3, row.seller().toString());
            ps.setString(4, row.itemClass().name());
            ps.setString(5, row.itemKey());
            ps.setString(6, row.materialKey());
            ps.setInt(7, row.amount());
            ps.setLong(8, row.grossDust());
            ps.setLong(9, row.taxDust());
            ps.setLong(10, row.taxBurnedDust());
            ps.setLong(11, row.taxPotDust());
            ps.setLong(12, row.netDust());
            setNullableLong(ps, 13, row.listingId());
            ps.executeUpdate();
        }
    }

    // ----------------------------------------------------------- pending_items

    /**
     * Inserts an owed item and returns the id SQLite assigned it.
     *
     * @param row the owed item to record; its {@code id} is ignored and replaced
     * @return the generated row id
     * @throws SQLException if the write fails, including a {@code CHECK} violation
     */
    public long insertPending(PendingItemRow row) throws SQLException {
        Objects.requireNonNull(row, "row");
        String sql = """
                INSERT INTO pending_items (owner_uuid, item_bytes, amount, summary, reason,
                        created_at, claimed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps =
                database.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, row.owner().toString());
            ps.setBytes(2, row.itemBytes());
            ps.setInt(3, row.amount());
            ps.setString(4, row.summary());
            ps.setString(5, row.reason());
            ps.setLong(6, row.createdAtEpochMs());
            setNullableLong(ps, 7, row.claimedAtEpochMs());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Everything {@code owner} is still owed -- rows whose {@code claimed_at} is {@code NULL} --
     * oldest first.
     *
     * @param owner the player to read owed items for
     * @return the unclaimed owed items, oldest {@code created_at} first
     */
    public List<PendingItemRow> unclaimedFor(UUID owner) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        String sql = "SELECT id, owner_uuid, item_bytes, amount, summary, reason, created_at, claimed_at "
                + "FROM pending_items WHERE owner_uuid = ? AND claimed_at IS NULL ORDER BY created_at ASC";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<PendingItemRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapPending(rs));
                }
                return rows;
            }
        }
    }

    /**
     * Marks an owed item claimed, but only if it is still unclaimed. As with {@link #markSold},
     * the {@code claimed_at IS NULL} guard makes a double-claim a no-op rather than a second grant.
     *
     * @param id        the owed-item row to mark claimed
     * @param atEpochMs when it was claimed, epoch milliseconds
     * @throws SQLException if the write fails
     */
    public void markClaimed(long id, long atEpochMs) throws SQLException {
        String sql = "UPDATE pending_items SET claimed_at = ? WHERE id = ? AND claimed_at IS NULL";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, atEpochMs);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String listingSelect() {
        return "SELECT id, seller_uuid, item_class, item_key, material_key, display_name, summary, "
                + "amount, price_dust, item_bytes, listed_at, expires_at, status, sold_at, buyer_uuid "
                + "FROM listings";
    }

    private static List<ListingRow> queryListings(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<ListingRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapListing(rs));
            }
            return rows;
        }
    }

    private static ListingRow mapListing(ResultSet rs) throws SQLException {
        String buyer = rs.getString("buyer_uuid");
        return new ListingRow(
                rs.getLong("id"),
                UUID.fromString(rs.getString("seller_uuid")),
                ItemClass.valueOf(rs.getString("item_class")),
                rs.getString("item_key"),
                rs.getString("material_key"),
                rs.getString("display_name"),
                rs.getString("summary"),
                rs.getInt("amount"),
                rs.getLong("price_dust"),
                rs.getBytes("item_bytes"),
                rs.getLong("listed_at"),
                rs.getLong("expires_at"),
                ListingStatus.fromStored(rs.getString("status")),
                nullableLong(rs, "sold_at"),
                buyer == null ? null : UUID.fromString(buyer));
    }

    private static PendingItemRow mapPending(ResultSet rs) throws SQLException {
        return new PendingItemRow(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getBytes("item_bytes"),
                rs.getInt("amount"),
                rs.getString("summary"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                nullableLong(rs, "claimed_at"));
    }

    /** Binds {@code value} at {@code index}, or SQL {@code NULL} when it is {@code null}. */
    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    /** Reads {@code column} as a {@link Long}, returning {@code null} when the column is SQL NULL. */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** Returns the single generated key from {@code ps}, which must have just inserted one row. */
    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }
}
