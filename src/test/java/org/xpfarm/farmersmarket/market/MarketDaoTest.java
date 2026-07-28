/*
 * FarmersMarket - unit tests for MarketDao.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xpfarm.farmersmarket.storage.Database;
import org.xpfarm.farmersmarket.storage.Migrations;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link MarketDao} against a real, migrated SQLite file under a JUnit
 * {@code @TempDir}. No mocking: the value of a DAO is the SQL it runs, and the escrow, the
 * double-buy guard, and the append-only trade log are only proven against the real database.
 */
class MarketDaoTest {

    private Database database;
    private MarketDao dao;

    @BeforeEach
    void openMigratedDatabase(@TempDir Path dir) throws Exception {
        database = Database.open(dir.resolve("market.db"), dir.resolve("tmp").toString(), 5000);
        Migrations.applyTo(database.connection());
        dao = new MarketDao(database);
    }

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
    }

    @Test
    void insertThenReadRoundTripsEveryColumnIncludingItemBytes() throws SQLException {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        byte[] itemBytes = {0, 1, 2, -128, 127, 42};
        ListingRow row = new ListingRow(0, seller, ItemClass.UNIQUE, "diamond_sword#nbt",
                "DIAMOND_SWORD", "Excalibur", "Sharpness V sword", 1, 500L, itemBytes,
                1_000L, 2_000L, ListingStatus.SOLD, 1_500L, buyer);

        long id = dao.insertListing(row);
        ListingRow stored = dao.findListing(id).orElseThrow();

        assertEquals(id, stored.id());
        assertEquals(seller, stored.seller());
        assertEquals(ItemClass.UNIQUE, stored.itemClass());
        assertEquals("diamond_sword#nbt", stored.itemKey());
        assertEquals("DIAMOND_SWORD", stored.materialKey());
        assertEquals("Excalibur", stored.displayName());
        assertEquals("Sharpness V sword", stored.summary());
        assertEquals(1, stored.amount());
        assertEquals(500L, stored.priceDust());
        assertArrayEquals(itemBytes, stored.itemBytes());
        assertEquals(1_000L, stored.listedAtEpochMs());
        assertEquals(2_000L, stored.expiresAtEpochMs());
        assertEquals(ListingStatus.SOLD, stored.status());
        assertEquals(1_500L, stored.soldAtEpochMs());
        assertEquals(buyer, stored.buyer());
    }

    @Test
    void nullableColumnsRoundTripAsNull() throws SQLException {
        // display_name, sold_at, and buyer are the three nullable fields; a fresh ACTIVE listing
        // has all three empty, and they must come back null rather than as a default value.
        long id = dao.insertListing(activeListing(UUID.randomUUID()));
        ListingRow stored = dao.findListing(id).orElseThrow();

        assertEquals(null, stored.displayName());
        assertEquals(null, stored.soldAtEpochMs());
        assertEquals(null, stored.buyer());
    }

    @Test
    void findActiveListingReturnsWhileActiveAndIsEmptyAfterMarkSold() throws SQLException {
        long id = dao.insertListing(activeListing(UUID.randomUUID()));
        assertTrue(dao.findActiveListing(id).isPresent(), "an ACTIVE listing must be visible to the sale");

        dao.markSold(id, UUID.randomUUID(), 5_000L);

        assertTrue(dao.findActiveListing(id).isEmpty(),
                "a sold listing must be invisible to findActiveListing so it cannot be bought again");
        assertTrue(dao.findListing(id).isPresent(), "findListing still sees it in any status");
    }

    @Test
    void markSoldAffectsExactlyOneRowThenZeroOnASecondCall() throws SQLException {
        long id = dao.insertListing(activeListing(UUID.randomUUID()));

        assertEquals(1, dao.markSold(id, UUID.randomUUID(), 5_000L),
                "the first markSold of an ACTIVE listing must update exactly one row");
        assertEquals(0, dao.markSold(id, UUID.randomUUID(), 6_000L),
                "a second markSold must update zero rows -- this is the sale's double-buy guard");
    }

    @Test
    void countActiveBySellerCountsOnlyActive() throws SQLException {
        UUID seller = UUID.randomUUID();
        long a = dao.insertListing(activeListing(seller));
        dao.insertListing(activeListing(seller));
        UUID other = UUID.randomUUID();
        dao.insertListing(activeListing(other));

        assertEquals(2, dao.countActiveBySeller(seller));

        dao.markSold(a, UUID.randomUUID(), 5_000L);
        assertEquals(1, dao.countActiveBySeller(seller), "a sold listing must no longer be counted");
    }

    @Test
    void markStatusMovesActiveToCancelledAndCountDrops() throws SQLException {
        UUID seller = UUID.randomUUID();
        long id = dao.insertListing(activeListing(seller));

        assertEquals(1, dao.markStatus(id, ListingStatus.CANCELLED, 7_000L),
                "cancelling an ACTIVE listing must change exactly one row");
        assertEquals(0, dao.markStatus(id, ListingStatus.EXPIRED, 8_000L),
                "a second transition off ACTIVE must change zero rows");

        assertEquals(ListingStatus.CANCELLED, dao.findListing(id).orElseThrow().status());
        assertEquals(0, dao.countActiveBySeller(seller));
        assertEquals(1, dao.listingsBySeller(seller, ListingStatus.CANCELLED).size());
    }

    @Test
    void browseActiveReturnsOnlyActiveUniqueRowsNewestFirstAndFiltersByMaterial() throws SQLException {
        UUID seller = UUID.randomUUID();
        long older = dao.insertListing(uniqueListing(seller, "DIAMOND_SWORD", 1_000L));
        long newer = dao.insertListing(uniqueListing(seller, "DIAMOND_SWORD", 2_000L));
        dao.insertListing(uniqueListing(seller, "IRON_SWORD", 3_000L));
        long soldOne = dao.insertListing(uniqueListing(seller, "DIAMOND_SWORD", 1_500L));
        dao.markSold(soldOne, UUID.randomUUID(), 4_000L);

        List<ListingRow> swords = dao.browseActive("DIAMOND_SWORD", 10, 0);

        assertEquals(List.of(newer, older), swords.stream().map(ListingRow::id).toList(),
                "browse must return active DIAMOND_SWORD listings newest first, excluding the sold one");
    }

    @Test
    void dueForExpiryReturnsOnlyActiveRowsPastTheCutoff() throws SQLException {
        UUID seller = UUID.randomUUID();
        long expired = dao.insertListing(listingExpiringAt(seller, 1_000L));
        dao.insertListing(listingExpiringAt(seller, 9_000L));

        List<ListingRow> due = dao.dueForExpiry(5_000L, 10);

        assertEquals(List.of(expired), due.stream().map(ListingRow::id).toList());
    }

    @Test
    void insertTradeThenReadBackIsFaithful() throws SQLException {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        TradeRow row = new TradeRow(0, 8_000L, buyer, seller, ItemClass.UNIQUE, "k",
                "DIAMOND_SWORD", 1, 100L, 7L, 3L, 4L, 93L, 55L);

        dao.insertTrade(row);

        TradeRow stored = onlyTrade();
        assertEquals(8_000L, stored.happenedAtEpochMs());
        assertEquals(buyer, stored.buyer());
        assertEquals(seller, stored.seller());
        assertEquals(ItemClass.UNIQUE, stored.itemClass());
        assertEquals("k", stored.itemKey());
        assertEquals("DIAMOND_SWORD", stored.materialKey());
        assertEquals(1, stored.amount());
        assertEquals(100L, stored.grossDust());
        assertEquals(7L, stored.taxDust());
        assertEquals(3L, stored.taxBurnedDust());
        assertEquals(4L, stored.taxPotDust());
        assertEquals(93L, stored.netDust());
        assertEquals(55L, stored.listingId());
    }

    @Test
    void insertTradeWithANullListingIdRoundTripsAsNull() throws SQLException {
        dao.insertTrade(new TradeRow(0, 8_000L, UUID.randomUUID(), UUID.randomUUID(),
                ItemClass.COMMODITY, "k", "WHEAT", 64, 100L, 0L, 0L, 0L, 100L, null));

        assertEquals(null, onlyTrade().listingId());
    }

    @Test
    void insertTradeRefusesAConservationViolatingRow() {
        // gross must equal net + tax; 100 != 90 + 7. The CHECK, not application code, refuses it.
        TradeRow bad = new TradeRow(0, 8_000L, UUID.randomUUID(), UUID.randomUUID(),
                ItemClass.UNIQUE, "k", "DIAMOND_SWORD", 1, 100L, 7L, 3L, 4L, 90L, null);

        assertThrows(SQLException.class, () -> dao.insertTrade(bad));
    }

    @Test
    void insertPendingThenUnclaimedForReturnsItOldestFirst() throws SQLException {
        UUID owner = UUID.randomUUID();
        byte[] bytes = {9, 8, 7};
        long first = dao.insertPending(new PendingItemRow(0, owner, bytes, 3, "3 wheat",
                "expired-listing", 1_000L, null));
        long second = dao.insertPending(new PendingItemRow(0, owner, new byte[] {1}, 1, "1 sword",
                "sold-listing", 2_000L, null));

        List<PendingItemRow> owed = dao.unclaimedFor(owner);

        assertEquals(List.of(first, second), owed.stream().map(PendingItemRow::id).toList(),
                "owed items come back oldest first");
        assertArrayEquals(bytes, owed.get(0).itemBytes());
    }

    @Test
    void unclaimedForOmitsARowAfterMarkClaimed() throws SQLException {
        UUID owner = UUID.randomUUID();
        long id = dao.insertPending(new PendingItemRow(0, owner, new byte[] {1}, 1, "1 sword",
                "sold-listing", 1_000L, null));
        assertEquals(1, dao.unclaimedFor(owner).size());

        dao.markClaimed(id, 3_000L);

        assertTrue(dao.unclaimedFor(owner).isEmpty(),
                "a claimed item must no longer be owed");
    }

    @Test
    void secondMarkClaimedIsANoOp() throws SQLException {
        UUID owner = UUID.randomUUID();
        long id = dao.insertPending(new PendingItemRow(0, owner, new byte[] {1}, 1, "1 sword",
                "sold-listing", 1_000L, null));

        assertEquals(1, dao.markClaimed(id, 3_000L));
        assertEquals(0, dao.markClaimed(id, 4_000L),
                "a second claim must affect zero rows so an item cannot be granted twice");
    }

    @Test
    void listingCheckConstraintsRefuseNonPositiveAmountAndPrice() {
        UUID seller = UUID.randomUUID();
        assertThrows(SQLException.class, () -> dao.insertListing(new ListingRow(0, seller,
                ItemClass.UNIQUE, "k", "DIAMOND_SWORD", null, "s", 0, 500L, new byte[] {1},
                1_000L, 2_000L, ListingStatus.ACTIVE, null, null)));
        assertThrows(SQLException.class, () -> dao.insertListing(new ListingRow(0, seller,
                ItemClass.UNIQUE, "k", "DIAMOND_SWORD", null, "s", 1, 0L, new byte[] {1},
                1_000L, 2_000L, ListingStatus.ACTIVE, null, null)));
    }

    // ------------------------------------------------------------------ helpers

    private static ListingRow activeListing(UUID seller) {
        return new ListingRow(0, seller, ItemClass.UNIQUE, "k", "DIAMOND_SWORD", null,
                "a sword", 1, 500L, new byte[] {1, 2, 3}, 1_000L, 2_000L,
                ListingStatus.ACTIVE, null, null);
    }

    private static ListingRow uniqueListing(UUID seller, String material, long listedAt) {
        return new ListingRow(0, seller, ItemClass.UNIQUE, "k", material, null, "a thing",
                1, 500L, new byte[] {1}, listedAt, listedAt + 1_000L, ListingStatus.ACTIVE, null, null);
    }

    private static ListingRow listingExpiringAt(UUID seller, long expiresAt) {
        return new ListingRow(0, seller, ItemClass.UNIQUE, "k", "DIAMOND_SWORD", null, "a sword",
                1, 500L, new byte[] {1}, 0L, expiresAt, ListingStatus.ACTIVE, null, null);
    }

    /** Reads back the single {@code trades} row this test inserted. */
    private TradeRow onlyTrade() throws SQLException {
        String sql = "SELECT id, happened_at, buyer_uuid, seller_uuid, item_class, item_key, "
                + "material_key, amount, gross_dust, tax_dust, tax_burned_dust, tax_pot_dust, "
                + "net_dust, listing_id FROM trades";
        try (var ps = database.connection().prepareStatement(sql);
                var rs = ps.executeQuery()) {
            assertTrue(rs.next(), "expected exactly one trade row");
            long listingId = rs.getLong("listing_id");
            Long listing = rs.wasNull() ? null : listingId;
            TradeRow row = new TradeRow(
                    rs.getLong("id"),
                    rs.getLong("happened_at"),
                    UUID.fromString(rs.getString("buyer_uuid")),
                    UUID.fromString(rs.getString("seller_uuid")),
                    ItemClass.valueOf(rs.getString("item_class")),
                    rs.getString("item_key"),
                    rs.getString("material_key"),
                    rs.getInt("amount"),
                    rs.getLong("gross_dust"),
                    rs.getLong("tax_dust"),
                    rs.getLong("tax_burned_dust"),
                    rs.getLong("tax_pot_dust"),
                    rs.getLong("net_dust"),
                    listing);
            assertFalse(rs.next(), "expected exactly one trade row");
            return row;
        }
    }
}
