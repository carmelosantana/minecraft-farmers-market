/*
 * FarmersMarket - the atomic sale, plus listing, cancel, expiry, and claim.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.xpfarm.farmersmarket.ledger.Diamonds;
import org.xpfarm.farmersmarket.ledger.LedgerException;
import org.xpfarm.farmersmarket.storage.AccountDao;
import org.xpfarm.farmersmarket.storage.Database;
import org.xpfarm.farmersmarket.storage.DatabaseExecutor;
import org.xpfarm.farmersmarket.storage.TransactionRunner;

/**
 * The market's write and read operations: listing an item, buying one, cancelling, sweeping expired
 * listings, and claiming an owed item.
 *
 * <p>Every method returns a {@link CompletableFuture} and does all of its work on
 * {@link DatabaseExecutor}'s single writer thread, exactly as the ledger does; nothing in this
 * package knows what a main thread is. Failures arrive as the future's cause, and the cause's type
 * is the contract: a {@link MarketException} means the operation was refused and nothing was
 * written, so the command layer may compensate; any other cause is an unknown outcome.
 *
 * <p><b>The sale is the operation M1's {@code Ledger} javadoc anticipated M2 would need.</b> It
 * moves money, flips the listing to {@code SOLD}, and writes the immutable trade-log row inside one
 * {@link TransactionRunner#inTransaction} body over {@link AccountDao} and {@link MarketDao} -- it
 * does <em>not</em> call {@code Ledger.transfer}, which opens its own transaction and whose nesting
 * guard would refuse the composition. This class builds its own {@link TransactionRunner} over the
 * shared {@link Database}, just as the ledger does, so there is no signature ripple. Every balance
 * change goes through {@link Diamonds} arithmetic, never a raw {@code long} add, so an overflow
 * refuses instead of wrapping a balance negative -- the same discipline {@code Ledger.mergeAccounts}
 * uses.
 *
 * <p><b>Overflow is a refusal, translated at this boundary.</b> {@link MarketMath#taxOnSale} can
 * throw a bare {@link ArithmeticException} and {@link Diamonds#plus}/{@link Diamonds#minus} throw
 * {@link LedgerException} with {@link LedgerException.Reason#AMOUNT_TOO_LARGE}. Both are unreachable
 * at this server's scale, but if either fires it does so <em>inside</em> the sale's transaction,
 * which rolls back whole before the throw ever leaves {@code inTransaction}. So the sale catches
 * both after the rollback and rethrows them as {@link MarketException.Reason#AMOUNT_TOO_LARGE},
 * giving the command layer one refusal type to map, and every such refusal is a genuine
 * nothing-written refusal. The genuine unknown-outcome case -- a commit followed by a failing
 * cleanup -- keeps its raw cause, exactly as {@code Ledger} does.
 */
public final class MarketService {

    private final AccountDao accounts;
    private final MarketDao market;
    private final DatabaseExecutor executor;
    private final TransactionRunner transactions;

    /**
     * @param database the open database, held only to build this service's own
     *                 {@link TransactionRunner} -- the multi-statement operations (the sale, cancel,
     *                 expiry, claim) draw their transaction boundary here, so the boundary control
     *                 has to live here too
     * @param executor the single writer thread every operation runs on
     * @param accounts the DAO the sale moves balances through
     * @param market   the DAO for listings, trades, and pending items
     */
    public MarketService(Database database, DatabaseExecutor executor, AccountDao accounts,
            MarketDao market) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.market = Objects.requireNonNull(market, "market");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = new TransactionRunner(Objects.requireNonNull(database, "database"));
    }

    // -------------------------------------------------------------------- list

    /**
     * Puts {@code item} up for sale at {@code price}, held in escrow, and returns the new listing's
     * id. The XP listing fee is charged by the command layer <em>after</em> this succeeds.
     *
     * <p>The active-listing count is read the way {@code Ledger.readBeforeWriting} reads a balance:
     * a storage failure of that read happened before the insert was attempted, so nothing was
     * written, and it arrives as {@link MarketException.Reason#NOTHING_WRITTEN} -- a definite
     * refusal the command layer answers by handing the seller their item back and charging no fee.
     * The insert itself is a single statement on the single writer thread, so it needs no
     * transaction: nothing can interleave between the count and the insert.
     *
     * @param seller              the player listing the item
     * @param item                the item to escrow and sell
     * @param price               the asking price
     * @param maxListings         the most active listings this seller may hold at once
     * @param nowEpochMs          when the listing is created, epoch milliseconds
     * @param listingDurationDays how many days until the listing lapses if unsold
     * @return a future completing with the new listing id, or failing with a {@link MarketException}
     *         whose reason is {@link MarketException.Reason#TOO_MANY_LISTINGS} or
     *         {@link MarketException.Reason#NOTHING_WRITTEN}
     */
    public CompletableFuture<Long> list(UUID seller, ListedItem item, Diamonds price,
            int maxListings, long nowEpochMs, long listingDurationDays) {
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(price, "price");
        return executor.submit(() -> {
            int active;
            try {
                active = market.countActiveBySeller(seller);
            } catch (SQLException readFailure) {
                throw new MarketException(MarketException.Reason.NOTHING_WRITTEN,
                        "could not count " + seller + "'s active listings before inserting; the "
                                + "insert was never attempted", readFailure);
            }
            if (active >= maxListings) {
                throw new MarketException(MarketException.Reason.TOO_MANY_LISTINGS,
                        seller + " already holds " + active + " active listings; the limit is "
                                + maxListings);
            }
            long expiresAt = nowEpochMs + Duration.ofDays(listingDurationDays).toMillis();
            ListingRow row = new ListingRow(0L, seller, item.itemClass(), item.itemKey(),
                    item.materialKey(), item.displayName(), item.summary(), item.amount(),
                    price.dust(), item.itemBytes(), nowEpochMs, expiresAt, ListingStatus.ACTIVE,
                    null, null);
            return market.insertListing(row);
        });
    }

    // --------------------------------------------------------------------- buy

    /**
     * The atomic sale: charges the buyer the gross, credits the seller the net and the community pot
     * its share, burns the rest, flips the listing to {@code SOLD}, and appends the trade-log row --
     * all inside one transaction that commits or rolls back whole.
     *
     * @param buyer           the purchasing player
     * @param listingId       the listing to buy
     * @param salesTaxPercent the sales-tax rate, as a config-supplied percentage
     * @param taxBurnShare    the fraction of the tax to burn, as a config-supplied share of one
     * @param nowEpochMs      when the sale completes, epoch milliseconds
     * @return a future completing with the {@link SaleResult} the command layer finishes on the main
     *         thread, or failing with a {@link MarketException} whose reason is
     *         {@link MarketException.Reason#LISTING_UNAVAILABLE},
     *         {@link MarketException.Reason#SELF_PURCHASE},
     *         {@link MarketException.Reason#INSUFFICIENT_FUNDS}, or
     *         {@link MarketException.Reason#AMOUNT_TOO_LARGE}
     */
    public CompletableFuture<SaleResult> buy(UUID buyer, long listingId,
            double salesTaxPercent, double taxBurnShare, long nowEpochMs) {
        Objects.requireNonNull(buyer, "buyer");
        return executor.submit(() -> {
            try {
                return transactions.inTransaction(() -> settle(buyer, listingId, salesTaxPercent,
                        taxBurnShare, nowEpochMs));
            } catch (LedgerException overflow) {
                // Diamonds throws this on overflow. It fired inside the transaction, which has
                // already rolled back and restored autocommit before this catch runs, so nothing
                // was written -- translate it to the one refusal type the command layer maps.
                if (overflow.reason() == LedgerException.Reason.AMOUNT_TOO_LARGE) {
                    throw new MarketException(MarketException.Reason.AMOUNT_TOO_LARGE,
                            "the sale of listing " + listingId + " overflowed a balance", overflow);
                }
                throw overflow;
            } catch (ArithmeticException overflow) {
                // MarketMath.taxOnSale throws a bare ArithmeticException on an absurd price; same
                // rolled-back-nothing-written argument, same translation.
                throw new MarketException(MarketException.Reason.AMOUNT_TOO_LARGE,
                        "the tax split for listing " + listingId + " overflowed", overflow);
            }
        });
    }

    /** The transactional body of {@link #buy}, run inside one {@code inTransaction}. */
    private SaleResult settle(UUID buyer, long listingId, double salesTaxPercent,
            double taxBurnShare, long nowEpochMs) throws SQLException {
        ListingRow listing = market.findActiveListing(listingId)
                .orElseThrow(() -> new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                        "listing " + listingId + " is not on sale"));
        if (listing.seller().equals(buyer)) {
            throw new MarketException(MarketException.Reason.SELF_PURCHASE,
                    "a seller cannot buy their own listing");
        }

        Diamonds gross = Diamonds.ofDust(listing.priceDust());
        MarketMath.TaxSplit split = MarketMath.taxOnSale(gross, salesTaxPercent, taxBurnShare);

        Diamonds buyerBalance = Diamonds.ofDust(accounts.balanceDust(buyer));
        Diamonds afterBuyer = buyerBalance.minus(gross);
        if (afterBuyer.isNegative()) {
            throw new MarketException(MarketException.Reason.INSUFFICIENT_FUNDS,
                    buyer + " holds " + buyerBalance.format() + " but the price is " + gross.format());
        }

        // Every balance change goes through Diamonds, never a raw long add, so an overflow refuses
        // with AMOUNT_TOO_LARGE (translated at the boundary above) instead of wrapping a balance
        // negative -- exactly as Ledger.mergeAccounts does.
        accounts.upsertBalance(buyer, afterBuyer.dust());
        Diamonds sellerAfter = Diamonds.ofDust(accounts.balanceDust(listing.seller())).plus(split.net());
        accounts.upsertBalance(listing.seller(), sellerAfter.dust());
        Diamonds potAfter = Diamonds.ofDust(accounts.balanceDust(SystemAccounts.COMMUNITY_POT))
                .plus(split.toPot());
        accounts.upsertBalance(SystemAccounts.COMMUNITY_POT, potAfter.dust());
        // split.burned() is credited to nobody -- that is the sink.

        // markSold updates only an ACTIVE listing, so exactly 1 row here proves the listing this
        // sale read as ACTIVE really flipped. A 0 means it left ACTIVE between the read and now --
        // impossible on the single writer thread, so it is an anomaly, not a race, and must never
        // silently complete a money move. Throwing rolls the whole transaction back: nothing
        // written, a genuine LISTING_UNAVAILABLE refusal.
        if (market.markSold(listingId, buyer, nowEpochMs) != 1) {
            throw new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                    "listing " + listingId + " left ACTIVE mid-sale; rolling back with nothing written");
        }
        market.insertTrade(new TradeRow(0L, nowEpochMs, buyer, listing.seller(),
                listing.itemClass(), listing.itemKey(), listing.materialKey(), listing.amount(),
                gross.dust(), split.tax().dust(), split.burned().dust(), split.toPot().dust(),
                split.net().dust(), listingId));

        return new SaleResult(listing.itemBytes(), listing.amount(), listing.summary(),
                listing.seller(), split, listingId);
    }

    // ------------------------------------------------------------------ cancel

    /**
     * Takes {@code seller}'s listing down and returns its escrowed bytes for the command layer to
     * hand back. The find-check-write runs in one transaction so the listing's status cannot change
     * under it.
     *
     * @param seller     the player cancelling; must own the listing
     * @param listingId  the listing to cancel
     * @param nowEpochMs when the cancellation happened, epoch milliseconds
     * @return a future completing with the escrowed item bytes, or failing with a
     *         {@link MarketException} whose reason is
     *         {@link MarketException.Reason#LISTING_UNAVAILABLE} or
     *         {@link MarketException.Reason#NOT_YOUR_LISTING}
     */
    public CompletableFuture<byte[]> cancel(UUID seller, long listingId, long nowEpochMs) {
        Objects.requireNonNull(seller, "seller");
        return executor.submit(() -> transactions.inTransaction(() -> {
            ListingRow listing = market.findActiveListing(listingId)
                    .orElseThrow(() -> new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                            "listing " + listingId + " is not on sale"));
            if (!listing.seller().equals(seller)) {
                throw new MarketException(MarketException.Reason.NOT_YOUR_LISTING,
                        seller + " does not own listing " + listingId);
            }
            if (market.markStatus(listingId, ListingStatus.CANCELLED, nowEpochMs) != 1) {
                // Same single-writer anomaly guard as the sale's markSold==1.
                throw new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                        "listing " + listingId + " left ACTIVE mid-cancel; nothing written");
            }
            return listing.itemBytes();
        }));
    }

    // --------------------------------------------------------------- expireDue

    /**
     * Sweeps up to {@code batchLimit} {@code ACTIVE} listings whose {@code expires_at} has passed,
     * flipping each to {@code EXPIRED} and moving its item to {@code pending_items} for the seller,
     * and returns how many were swept. The whole batch is one transaction. Batching keeps a
     * long-idle server's first sweep bounded.
     *
     * @param nowEpochMs the cutoff; listings expiring at or before this are swept
     * @param batchLimit the most listings to sweep in this pass
     * @return a future completing with the number of listings swept
     */
    public CompletableFuture<Integer> expireDue(long nowEpochMs, int batchLimit) {
        return executor.submit(() -> transactions.inTransaction(() -> {
            List<ListingRow> due = market.dueForExpiry(nowEpochMs, batchLimit);
            for (ListingRow listing : due) {
                market.markStatus(listing.id(), ListingStatus.EXPIRED, nowEpochMs);
                market.insertPending(new PendingItemRow(0L, listing.seller(), listing.itemBytes(),
                        listing.amount(), listing.summary(), "EXPIRED", nowEpochMs, null));
            }
            return due.size();
        }));
    }

    // ---------------------------------------------------------------- claimOne

    /**
     * Marks one item {@code owner} is owed as claimed and returns it, so the command layer can
     * deliver the bytes. Reading the owed item through {@link MarketDao#unclaimedFor} scopes it to
     * {@code owner}'s still-unclaimed rows, so a row that is not there is either not theirs, already
     * claimed, or gone -- all of which are refused, and none of which mark anything claimed.
     * Claim-all is a loop in the command layer over {@link #pendingFor}.
     *
     * @param owner      the player claiming an owed item
     * @param pendingId  the {@code pending_items} row to claim
     * @param nowEpochMs when the claim happened, epoch milliseconds
     * @return a future completing with the claimed row, or failing with a {@link MarketException}
     *         whose reason is {@link MarketException.Reason#LISTING_UNAVAILABLE}
     */
    public CompletableFuture<PendingItemRow> claimOne(UUID owner, long pendingId, long nowEpochMs) {
        Objects.requireNonNull(owner, "owner");
        return executor.submit(() -> transactions.inTransaction(() -> {
            PendingItemRow row = market.unclaimedFor(owner).stream()
                    .filter(r -> r.id() == pendingId)
                    .findFirst()
                    .orElseThrow(() -> new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                            "pending item " + pendingId + " is not owed to " + owner));
            if (market.markClaimed(pendingId, nowEpochMs) != 1) {
                // Unclaimed a moment ago, not now: the single-writer anomaly guard again.
                throw new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                        "pending item " + pendingId + " was already claimed; nothing written");
            }
            return row;
        }));
    }

    // ------------------------------------------------------------- read helpers

    /**
     * A page of active {@code UNIQUE} listings, newest first, optionally filtered by material.
     *
     * @param materialLike a SQL {@code LIKE} pattern for the material, or {@code null} for all
     * @param page         the 1-based page number
     * @param pageSize     the number of listings per page
     * @return a future completing with the matching listings
     */
    public CompletableFuture<List<ListingRow>> browse(String materialLike, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return executor.submit(() -> market.browseActive(materialLike, pageSize, offset));
    }

    /**
     * The full listing with {@code id}, in whatever status it is.
     *
     * @param id the listing id
     * @return a future completing with the listing, or {@link Optional#empty()} if none has that id
     */
    public CompletableFuture<Optional<ListingRow>> findListing(long id) {
        return executor.submit(() -> market.findListing(id));
    }

    /**
     * A seller's still-active listings, newest first -- the ones they can still cancel.
     *
     * @param seller the seller to read
     * @return a future completing with the seller's active listings
     */
    public CompletableFuture<List<ListingRow>> myListings(UUID seller) {
        Objects.requireNonNull(seller, "seller");
        return executor.submit(() -> market.listingsBySeller(seller, ListingStatus.ACTIVE));
    }

    /**
     * The community pot's current balance.
     *
     * @return a future completing with the pot balance
     */
    public CompletableFuture<Diamonds> communityPotBalance() {
        return executor.submit(() -> Diamonds.ofDust(accounts.balanceDust(SystemAccounts.COMMUNITY_POT)));
    }

    /**
     * Everything {@code owner} is still owed, oldest first.
     *
     * @param owner the player to read owed items for
     * @return a future completing with the unclaimed owed items
     */
    public CompletableFuture<List<PendingItemRow>> pendingFor(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return executor.submit(() -> market.unclaimedFor(owner));
    }
}
