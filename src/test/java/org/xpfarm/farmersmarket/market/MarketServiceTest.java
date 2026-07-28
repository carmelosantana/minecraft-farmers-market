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
}
