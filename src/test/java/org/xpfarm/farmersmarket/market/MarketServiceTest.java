/*
 * FarmersMarket - unit tests for the atomic sale and the rest of MarketService.
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
import org.xpfarm.farmersmarket.ledger.Diamonds;
import org.xpfarm.farmersmarket.storage.AccountDao;
import org.xpfarm.farmersmarket.storage.Database;
import org.xpfarm.farmersmarket.storage.DatabaseExecutor;
import org.xpfarm.farmersmarket.storage.Migrations;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link MarketService} against a real, migrated SQLite file under a JUnit
 * {@code @TempDir} and a real {@link DatabaseExecutor} -- no server, no mocks. The properties under
 * test here are properties of the SQL and the transaction that actually run: that a sale conserves
 * money to the dust across all four parties and logs exactly one trade, that a sold listing cannot
 * be bought a second time, that an unaffordable purchase leaves everything exactly as it was.
 * Buyers are funded by writing balances straight through {@link AccountDao}; the market takes ledger
 * balances as given.
 */
class MarketServiceTest {

    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    private static final long NOW = 1_700_000_000_000L;

    private Database database;
    private DatabaseExecutor executor;
    private AccountDao accounts;
    private MarketDao market;
    private MarketService service;

    @BeforeEach
    void openMigratedDatabase(@TempDir Path dir) throws Exception {
        database = Database.open(dir.resolve("market.db"), dir.resolve("tmp").toString(), 5000);
        Migrations.applyTo(database.connection());
        executor = new DatabaseExecutor();
        accounts = new AccountDao(database);
        market = new MarketDao(database);
        service = new MarketService(database, executor, accounts, market);
    }

    @AfterEach
    void closeDatabase() throws Exception {
        executor.close();
        database.close();
    }

    @Test
    void aSaleMovesMoneyConservesItAndLogsExactlyOneTrade() throws Exception {
        fund(BUYER, Diamonds.ofDiamonds(200));
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40,
                NOW, 14).get();

        SaleResult r = service.buy(BUYER, id, 7.0, 0.5, NOW).get();

        // Money conservation across the four parties, to the dust.
        assertEquals(100_000L, currentBalance(BUYER).dust(), "buyer balance fell by exactly the gross");
        assertEquals(93_000L, currentBalance(SELLER).dust(), "seller received net");
        assertEquals(3_500L, currentBalance(SystemAccounts.COMMUNITY_POT).dust(), "pot received its half");
        // Burned diamonds are credited to nobody: total ledger supply fell by exactly the burn.
        long supplyAfter = currentBalance(BUYER).dust() + currentBalance(SELLER).dust()
                + currentBalance(SystemAccounts.COMMUNITY_POT).dust();
        assertEquals(200_000L - 3_500L, supplyAfter, "the only diamonds that left the world are the burn");
        assertEquals(1, tradeCount(), "exactly one trade row");
        assertEquals(id, r.listingId());
    }

    @Test
    void aSoldListingCannotBeBoughtAgain() throws Exception {
        fund(BUYER, Diamonds.ofDiamonds(200));
        fund(OTHER, Diamonds.ofDiamonds(200));
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();

        service.buy(BUYER, id, 7.0, 0.5, NOW).get();
        // The refusal is reached by the pre-read short-circuit, not the markSold != 1 row-count
        // guard: settle's findActiveListing (SELECT ... WHERE status='ACTIVE') returns empty for
        // the now-SOLD listing and orElseThrows LISTING_UNAVAILABLE before markSold is ever
        // called. The != 1 guard is pinned directly in MarketDaoTest.markSoldAffectsExactlyOne...
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> service.buy(OTHER, id, 7.0, 0.5, NOW).get());

        assertInstanceOf(MarketException.class, thrown.getCause());
        assertEquals(MarketException.Reason.LISTING_UNAVAILABLE,
                ((MarketException) thrown.getCause()).reason());
        assertEquals(1, tradeCount(), "the second attempt logged no trade");
        assertEquals(Diamonds.ofDiamonds(200).dust(), currentBalance(OTHER).dust(),
                "the second buyer was not charged");
    }

    @Test
    void anUnaffordablePurchaseChangesNothing() throws Exception {
        fund(BUYER, Diamonds.ofDiamonds(50));
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> service.buy(BUYER, id, 7.0, 0.5, NOW).get());

        // The refusal must be the typed pre-write INSUFFICIENT_FUNDS -- a nothing-written refusal
        // the command layer compensates -- not the raw SQLException the accounts CHECK constraint
        // would throw if the buyer were charged negative first, which is an unknown outcome. Both
        // leave state unchanged, so only the reason distinguishes the guard from its DB backstop.
        assertEquals(MarketException.Reason.INSUFFICIENT_FUNDS,
                assertInstanceOf(MarketException.class, thrown.getCause()).reason());
        assertEquals(50_000L, currentBalance(BUYER).dust());
        assertEquals(0L, currentBalance(SELLER).dust());
        assertEquals(0, tradeCount());
        assertTrue(service.findListing(id).get().orElseThrow().status() == ListingStatus.ACTIVE,
                "a refused sale leaves the listing on sale");
    }

    @Test
    void aSellerCannotBuyTheirOwnListing() throws Exception {
        // A seller may well be funded -- fund them, so the refusal is proven to be the self-purchase
        // guard and not an incidental insufficient-funds failure.
        fund(SELLER, Diamonds.ofDiamonds(200));
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> service.buy(SELLER, id, 7.0, 0.5, NOW).get());

        assertEquals(MarketException.Reason.SELF_PURCHASE,
                assertInstanceOf(MarketException.class, thrown.getCause()).reason());
        assertEquals(0, tradeCount(), "a refused self-purchase logs no trade");
        assertEquals(200_000L, currentBalance(SELLER).dust(), "no money moved");
        assertEquals(ListingStatus.ACTIVE, service.findListing(id).get().orElseThrow().status(),
                "the listing is still on sale");
    }

    @Test
    void anOwedItemCannotBeClaimedTwice() throws Exception {
        // Expire a listing to owe SELLER an item, then read its pending id.
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(10), 40, NOW, 14).get();
        long later = NOW + java.time.Duration.ofDays(15).toMillis();
        service.expireDue(later, 100).get();
        long pendingId = service.pendingFor(SELLER).get().get(0).id();

        PendingItemRow claimed = service.claimOne(SELLER, pendingId, later).get();
        assertEquals(pendingId, claimed.id(), "the first claim returns the owed row");

        // The second claim must not succeed a second delivery. It is refused by the pre-read
        // short-circuit, not the markClaimed != 1 row-count guard: claimOne reads unclaimedFor
        // (WHERE claimed_at IS NULL), the already-claimed row is absent, and it orElseThrows
        // LISTING_UNAVAILABLE before markClaimed is ever called. The != 1 guard itself is pinned
        // directly in MarketDaoTest.secondMarkClaimedIsANoOp.
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> service.claimOne(SELLER, pendingId, later).get());
        assertEquals(MarketException.Reason.LISTING_UNAVAILABLE,
                assertInstanceOf(MarketException.class, thrown.getCause()).reason());
        assertTrue(service.pendingFor(SELLER).get().isEmpty(),
                "the item stays claimed -- a failed second claim does not resurrect the debt");
    }

    @Test
    void cancellingReturnsTheEscrowBytesAndTakesTheListingDown() throws Exception {
        byte[] original = uniqueItem().itemBytes();
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();

        byte[] returned = service.cancel(SELLER, id, NOW).get();

        assertArrayEquals(original, returned, "the exact escrowed bytes come back");
        assertEquals(ListingStatus.CANCELLED, service.findListing(id).get().orElseThrow().status());
    }

    @Test
    void onlyTheSellerCanCancel() throws Exception {
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> service.cancel(OTHER, id, NOW).get());
        assertEquals(MarketException.Reason.NOT_YOUR_LISTING,
                ((MarketException) thrown.getCause()).reason());
    }

    @Test
    void theListingCapIsEnforced() throws Exception {
        for (int i = 0; i < 2; i++) {
            service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(10), 2, NOW, 14).get();
        }
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(10), 2, NOW, 14).get());
        assertEquals(MarketException.Reason.TOO_MANY_LISTINGS,
                ((MarketException) thrown.getCause()).reason());
    }

    @Test
    void expirySweepsDueListingsAndOwesTheItemToTheSeller() throws Exception {
        long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(10), 40, NOW, 14).get();
        long later = NOW + java.time.Duration.ofDays(15).toMillis();

        assertEquals(1, service.expireDue(later, 100).get());

        assertEquals(ListingStatus.EXPIRED, service.findListing(id).get().orElseThrow().status());
        assertEquals(1, service.pendingFor(SELLER).get().size(), "the seller is owed the expired item");
    }

    // ------------------------------------------------------- commodity: place bid

    @Test
    void placeBidEscrowsDiamondsFromBuyer() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedBalance(buyer, 100_000L); // 100 diamonds
        long id = service.placeBid(buyer, "minecraft:iron_ingot", 10, Diamonds.ofDust(3000L), 2, 1L)
                .get();
        assertEquals(70_000L, accounts.balanceDust(buyer), "10 x 3000 dust escrowed out of balance");
        CommodityOfferRow bid = market.findActiveOffer(id).orElseThrow();
        assertEquals(30_000L, bid.escrowedDust());
    }

    @Test
    void placeBidRefusesWhenBalanceTooLow() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedBalance(buyer, 10_000L);
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> service.placeBid(buyer, "k", 10, Diamonds.ofDust(3000L), 0, 1L).get());
        assertEquals(MarketException.Reason.INSUFFICIENT_FUNDS,
                ((MarketException) ex.getCause()).reason());
    }

    // ------------------------------------------------------- commodity: market-sell

    @Test
    void marketSellFillsBestBidFirstThenFloorAndConservesSupply() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        seedBalance(low, 1_000_000L);
        seedBalance(high, 1_000_000L);
        seedBalance(SystemAccounts.COMMUNITY_POT, 1_000_000L);
        long supplyBefore = totalSupply(); // sum of all account balances + active-offer escrow

        service.placeBid(low, "minecraft:iron_ingot", 10, Diamonds.ofDust(2000L), 0, 1L).get();
        service.placeBid(high, "minecraft:iron_ingot", 5, Diamonds.ofDust(5000L), 0, 2L).get();

        CommoditySpec spec = ironSpec(); // fixed CommoditySpec for iron_ingot
        // Sell 20: 5 to `high` @5000, 10 to `low` @2000, 5 remainder to floor @1000.
        CommoditySaleResult result = service.marketSell(seller, spec, 20,
                /*tax*/7.0, /*burnShare*/0.5, /*floorDust*/1000L,
                /*buyLimitEnabled*/false, /*cap*/-1, /*window*/0L, 100L).get();

        assertEquals(20, result.sold());
        assertEquals(0, result.unsold());
        assertEquals(supplyBefore - burnedAcrossTrades(), totalSupply(),
                "supply falls by exactly the burned tax; floor sales burn nothing");
        // both bids fully filled
        assertTrue(market.findActiveOffer(highBidId()).isEmpty());
    }

    @Test
    void marketSellStopsAtPotCapacityAndReturnsRemainder() throws Exception {
        UUID seller = UUID.randomUUID();
        seedBalance(SystemAccounts.COMMUNITY_POT, 3000L); // affords 3 at floor 1000
        CommoditySaleResult result = service.marketSell(seller, ironSpec(), 10,
                7.0, 0.5, 1000L, false, -1, 0L, 100L).get();
        assertEquals(3, result.sold(), "no bids; pot affords only 3");
        assertEquals(7, result.unsold());
    }

    @Test
    void marketSellSkipsSellersOwnBid() throws Exception {
        UUID seller = UUID.randomUUID();
        seedBalance(seller, 1_000_000L);
        service.placeBid(seller, "minecraft:iron_ingot", 10, Diamonds.ofDust(9000L), 0, 1L).get();
        // no other bids, no floor
        CommoditySaleResult result = service.marketSell(seller, ironSpec(), 10,
                7.0, 0.5, /*no floor*/0L, false, -1, 0L, 100L).get();
        assertEquals(0, result.sold(), "a seller cannot fill their own bid");
        assertEquals(10, result.unsold());
    }

    @Test
    void marketSellHonoursBuyLimitThenCancelsRemainderOfBid() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        seedBalance(buyer, 1_000_000L);
        long bidId = service.placeBid(buyer, "minecraft:iron_ingot", 100, Diamonds.ofDust(1000L), 0, 1L).get();
        // cap 30, empty window: fill 30, then cancel the remaining 70 and refund its escrow.
        long buyerBalAfterBid = accounts.balanceDust(buyer); // 1_000_000 - 100_000 = 900_000
        CommoditySaleResult result = service.marketSell(seller, ironSpec(), 100,
                7.0, 0.5, 0L, /*buyLimitEnabled*/true, /*cap*/30, /*window*/3_600_000L, 1_000_000L).get();
        assertEquals(30, result.sold(), "capped at 30");
        assertEquals(70, result.unsold(), "the other 70 could not be bought by the only bidder");
        assertTrue(market.findActiveOffer(bidId).isEmpty(), "bid remainder cancelled");
        // refund: escrow held 100_000; 30 spent (30_000); 70_000 returned to buyer.
        assertEquals(buyerBalAfterBid + 70_000L, accounts.balanceDust(buyer));
    }

    // ------------------------------------------------------- commodity: cancel bid

    @Test
    void cancelBidRefundsRemainingEscrowNotXp() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedBalance(buyer, 100_000L);
        long id = service.placeBid(buyer, "k", 10, Diamonds.ofDust(3000L), 5, 1L).get();
        service.cancelBid(buyer, id, 2L).get();
        assertEquals(100_000L, accounts.balanceDust(buyer), "full escrow refunded (nothing filled)");
        assertTrue(market.findActiveOffer(id).isEmpty());
    }

    @Test
    void cancelBidRefusesForeignOffer() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        seedBalance(buyer, 100_000L);
        long id = service.placeBid(buyer, "k", 1, Diamonds.ofDust(1000L), 0, 1L).get();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> service.cancelBid(other, id, 2L).get());
        assertEquals(MarketException.Reason.NOT_YOUR_LISTING, ((MarketException) ex.getCause()).reason());
    }

    // ------------------------------------------------------------------ helpers

    private static ListedItem uniqueItem() {
        return new ListedItem(ItemClass.UNIQUE, "u:abc", "DIAMOND_SWORD", "Excalibur",
                "Diamond Sword — Excalibur", 1, new byte[] {7, 7, 7});
    }

    /** Writes a balance straight through the DAO, on the executor's thread, bypassing the market. */
    private void fund(UUID uuid, Diamonds amount) throws Exception {
        executor.submit(() -> {
            accounts.upsertBalance(uuid, amount.dust());
            return null;
        }).get();
    }

    /** Reads a balance on the executor's thread; the connection belongs to that thread. */
    private Diamonds currentBalance(UUID uuid) throws Exception {
        return executor.submit(() -> Diamonds.ofDust(accounts.balanceDust(uuid))).get();
    }

    /** Counts the rows in the append-only {@code trades} log, on the executor's thread. */
    private int tradeCount() throws Exception {
        return executor.submit(() -> {
            try (var ps = database.connection().prepareStatement("SELECT COUNT(*) FROM trades");
                    var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }).get();
    }

    /** Writes a balance in dust straight through the DAO, on the executor's thread. */
    private void seedBalance(UUID uuid, long dust) throws Exception {
        executor.submit(() -> {
            accounts.upsertBalance(uuid, dust);
            return null;
        }).get();
    }

    /**
     * Every diamond that still exists: the sum of all account balances plus the dust escrowed
     * against still-{@code ACTIVE} offers. Money moved into escrow has not left the world, so it
     * must count, or a bid would look like a burn.
     */
    private long totalSupply() throws Exception {
        return executor.submit(() -> {
            long sum;
            try (var ps = database.connection()
                    .prepareStatement("SELECT COALESCE(SUM(diamonds_dust), 0) FROM accounts");
                    var rs = ps.executeQuery()) {
                rs.next();
                sum = rs.getLong(1);
            }
            try (var ps = database.connection().prepareStatement(
                    "SELECT COALESCE(SUM(escrowed_dust), 0) FROM commodity_offers WHERE status = 'ACTIVE'");
                    var rs = ps.executeQuery()) {
                rs.next();
                sum += rs.getLong(1);
            }
            return sum;
        }).get();
    }

    /** The total tax burned across every logged trade, the only diamonds that left the world. */
    private long burnedAcrossTrades() throws Exception {
        return executor.submit(() -> {
            try (var ps = database.connection()
                    .prepareStatement("SELECT COALESCE(SUM(tax_burned_dust), 0) FROM trades");
                    var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }).get();
    }

    /** The id of the highest-priced offer in the book, whatever status it now holds. */
    private long highBidId() throws Exception {
        return executor.submit(() -> {
            try (var ps = database.connection().prepareStatement(
                    "SELECT id FROM commodity_offers ORDER BY price_each_dust DESC, id ASC LIMIT 1");
                    var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }).get();
    }

    /** A fixed, Bukkit-free {@link CommoditySpec} for iron ingots. */
    private static CommoditySpec ironSpec() {
        return new CommoditySpec("minecraft:iron_ingot", "ironkey", new byte[] {1}, "Iron Ingot");
    }
}
