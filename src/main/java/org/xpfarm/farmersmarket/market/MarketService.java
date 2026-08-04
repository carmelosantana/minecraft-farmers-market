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
            int swept = 0;
            for (ListingRow listing : due) {
                // Assert the row-count exactly as buy/cancel/claimOne do. It is 1 today: the
                // dueForExpiry read and this markStatus write share one transaction on the single
                // writer thread, so a row read ACTIVE cannot have left ACTIVE before the update.
                // The guard is what keeps that invariant explicit -- if a future refactor split the
                // read from the writes, a 0 here would skip this listing's insertPending rather than
                // double-owe its item, and only the rows this sweep actually flipped are counted.
                if (market.markStatus(listing.id(), ListingStatus.EXPIRED, nowEpochMs) != 1) {
                    continue;
                }
                market.insertPending(new PendingItemRow(0L, listing.seller(), listing.itemBytes(),
                        listing.amount(), listing.summary(), "EXPIRED", nowEpochMs, null));
                swept++;
            }
            return swept;
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

    // ----------------------------------------------------------- holdForClaim

    /**
     * Records an item the market owes {@code owner} as a fresh {@code pending_items} row, so it
     * shows up in {@code /market claim}, and returns the new row's id.
     *
     * <p>This is the escrow of last resort the command layer reaches for when a bought or
     * cancelled unique cannot be handed over immediately -- the recipient is offline, or their
     * inventory is full. A valuable unique is held here rather than dropped on the ground, because
     * a dropped item despawns and this one must not. The insert is a single statement on the
     * single writer thread, so it needs no transaction, exactly like {@link #list}'s insert.
     *
     * @param owner      the player owed the item
     * @param itemBytes  the serialized stack to hold, read back verbatim on claim
     * @param amount     the stack size owed; must be positive, enforced by the table
     * @param summary    a human-readable one-line description of what is owed
     * @param reason     why it is owed, for the audit trail (e.g. {@code "PURCHASE"},
     *                   {@code "CANCELLED"})
     * @param nowEpochMs when the debt was recorded, epoch milliseconds
     * @return a future completing with the new {@code pending_items} row id
     */
    public CompletableFuture<Long> holdForClaim(UUID owner, byte[] itemBytes, int amount,
            String summary, String reason, long nowEpochMs) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(itemBytes, "itemBytes");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(reason, "reason");
        return executor.submit(() -> market.insertPending(new PendingItemRow(0L, owner, itemBytes,
                amount, summary, reason, nowEpochMs, null)));
    }

    // ---------------------------------------------------------------- placeBid

    /**
     * Escrows {@code priceEach x qty} diamonds out of {@code buyer}'s balance and rests an
     * {@code ACTIVE} bid for {@code qty} units of {@code materialKey}, returning the new offer's id.
     * The XP fee is <em>not</em> charged here -- it is stored on the row as {@code xp_paid} and
     * deducted by the command layer after this future succeeds, exactly as {@link #list}'s listing
     * fee is.
     *
     * <p>The whole escrow-and-insert is one transaction so a storage failure of the insert cannot
     * leave the buyer's balance debited with no bid to show for it. Overflow is refused, not
     * wrapped, and translated at this boundary just as {@link #buy}'s is: {@link Diamonds#times}
     * raises {@link LedgerException} on an absurd bid value, which fires inside the transaction,
     * rolls it back whole, and arrives here as {@link MarketException.Reason#AMOUNT_TOO_LARGE}.
     *
     * @param buyer       the player placing the bid
     * @param materialKey the material the bid is for
     * @param qty         how many units the bid wants
     * @param priceEach   the per-unit price the buyer will pay
     * @param xpFee       the experience the command layer will deduct after this succeeds, recorded
     *                    on the row for the refund audit trail
     * @param nowEpochMs  when the bid was placed, epoch milliseconds
     * @return a future completing with the new offer id, or failing with a {@link MarketException}
     *         whose reason is {@link MarketException.Reason#INSUFFICIENT_FUNDS} or
     *         {@link MarketException.Reason#AMOUNT_TOO_LARGE}
     */
    public CompletableFuture<Long> placeBid(UUID buyer, String materialKey, int qty,
            Diamonds priceEach, int xpFee, long nowEpochMs) {
        Objects.requireNonNull(buyer, "buyer");
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(priceEach, "priceEach");
        return executor.submit(() -> {
            try {
                return transactions.inTransaction(() -> {
                    // Raw Diamonds arithmetic, no inline overflow catch: an overflow rolls the
                    // whole transaction back and is translated at this boundary below.
                    Diamonds escrow = priceEach.times(qty);
                    Diamonds balance = Diamonds.ofDust(accounts.balanceDust(buyer));
                    Diamonds after = balance.minus(escrow);
                    if (after.isNegative()) {
                        throw new MarketException(MarketException.Reason.INSUFFICIENT_FUNDS,
                                buyer + " holds " + balance.format() + " but the bid escrows "
                                        + escrow.format());
                    }
                    accounts.upsertBalance(buyer, after.dust());
                    return market.insertOffer(new CommodityOfferRow(0L, buyer, materialKey, qty,
                            priceEach.dust(), escrow.dust(), xpFee, nowEpochMs, OfferStatus.ACTIVE));
                });
            } catch (LedgerException overflow) {
                if (overflow.reason() == LedgerException.Reason.AMOUNT_TOO_LARGE) {
                    throw new MarketException(MarketException.Reason.AMOUNT_TOO_LARGE,
                            "the bid of " + qty + " x " + priceEach.format() + " overflowed", overflow);
                }
                throw overflow;
            } catch (ArithmeticException overflow) {
                throw new MarketException(MarketException.Reason.AMOUNT_TOO_LARGE,
                        "the bid of " + qty + " x " + priceEach.format() + " overflowed", overflow);
            }
        });
    }

    // -------------------------------------------------------------- marketSell

    /**
     * The atomic multi-fill: sells up to {@code qty} units of {@code spec} against the resting bid
     * book best-first, taxing each player fill, then dumps whatever the book could not absorb to the
     * community-pot floor at {@code floorPriceDust}, untaxed and bounded by the pot's balance. The
     * whole sale is one transaction that commits or rolls back whole.
     *
     * <p>Every diamond move goes through {@link Diamonds} -- {@code .plus}, {@code .minus},
     * {@code .times} -- never a raw {@code long} add, so an overflow refuses with
     * {@link MarketException.Reason#AMOUNT_TOO_LARGE} (translated at this boundary, exactly as
     * {@link #buy}'s is) instead of wrapping a balance negative. A seller never fills their own
     * bid. When the rolling buy-limit stops a bidder short of their bid, that bid's remainder is
     * cancelled and its escrow refunded in the same transaction. The floor's bought stock is not
     * destroyed: it is credited to the pot's {@code pending_items} so item conservation mirrors
     * money conservation.
     *
     * @param seller            the player selling into the book
     * @param spec              the commodity being sold, in canonical single-unit form
     * @param qty               how many units the seller offered
     * @param salesTaxPercent   the sales-tax rate on player fills, as a config-supplied percentage
     * @param taxBurnShare      the fraction of the tax to burn, as a config-supplied share of one
     * @param floorPriceDust    the per-unit price the pot pays for the remainder; {@code 0} disables
     *                          the floor entirely
     * @param buyLimitEnabled   whether the rolling per-buyer purchase limit is in force
     * @param buyLimitCap       the most units one buyer may buy in the window; negative means no cap
     * @param buyLimitWindowMs  the width of the rolling buy-limit window, in milliseconds
     * @param nowEpochMs        when the sale completes, epoch milliseconds
     * @return a future completing with the {@link CommoditySaleResult}, or failing with a
     *         {@link MarketException} whose reason is {@link MarketException.Reason#NOTHING_WRITTEN}
     *         or {@link MarketException.Reason#AMOUNT_TOO_LARGE}
     */
    public CompletableFuture<CommoditySaleResult> marketSell(UUID seller, CommoditySpec spec, int qty,
            double salesTaxPercent, double taxBurnShare, long floorPriceDust,
            boolean buyLimitEnabled, int buyLimitCap, long buyLimitWindowMs, long nowEpochMs) {
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(spec, "spec");
        return executor.submit(() -> {
            try {
                return transactions.inTransaction(() -> {
                    int remaining = qty;
                    Diamonds proceeds = Diamonds.ZERO;

                    for (CommodityOfferRow bid : market.bestActiveBids(spec.materialKey(), 256)) {
                        if (remaining == 0) {
                            break;
                        }
                        if (bid.buyer().equals(seller)) {
                            continue; // a seller may never fill their own bid
                        }
                        int allowance = Integer.MAX_VALUE;
                        if (buyLimitEnabled) {
                            int used = market.buyLimitUsage(bid.buyer(), spec.materialKey(),
                                    nowEpochMs - buyLimitWindowMs);
                            allowance = CommodityMath.remainingBuyAllowance(buyLimitCap, used);
                        }
                        int take = Math.min(Math.min(remaining, bid.qtyRemaining()), allowance);
                        if (take > 0) {
                            Diamonds gross = Diamonds.ofDust(bid.priceEachDust()).times(take);
                            MarketMath.TaxSplit split =
                                    MarketMath.taxOnSale(gross, salesTaxPercent, taxBurnShare);

                            accounts.upsertBalance(seller,
                                    Diamonds.ofDust(accounts.balanceDust(seller)).plus(split.net()).dust());
                            accounts.upsertBalance(SystemAccounts.COMMUNITY_POT,
                                    Diamonds.ofDust(accounts.balanceDust(SystemAccounts.COMMUNITY_POT))
                                            .plus(split.toPot()).dust());
                            // split.burned() is credited to nobody -- that is the sink.

                            if (market.spendFromOffer(bid.id(), take, gross.dust()) != 1) {
                                throw new MarketException(MarketException.Reason.NOTHING_WRITTEN,
                                        "offer " + bid.id() + " left ACTIVE mid-fill; rolling back");
                            }
                            market.insertPending(new PendingItemRow(0L, bid.buyer(), spec.oneItemBytes(),
                                    take, take + "x " + spec.displayName(), "commodity purchase",
                                    nowEpochMs, null));
                            market.insertTrade(new TradeRow(0L, nowEpochMs, bid.buyer(), seller,
                                    ItemClass.COMMODITY, spec.itemKey(), spec.materialKey(), take,
                                    gross.dust(), split.tax().dust(), split.burned().dust(),
                                    split.toPot().dust(), split.net().dust(), null));

                            proceeds = proceeds.plus(split.net());
                            remaining -= take;
                        }
                        // fill-to-cap-then-cancel: if the buy limit stopped this bidder short of
                        // their bid, cancel the bid's remainder and refund its escrow.
                        if (buyLimitEnabled && take < bid.qtyRemaining()) {
                            CommodityOfferRow live = market.findActiveOffer(bid.id()).orElse(null);
                            if (live != null && CommodityMath.remainingBuyAllowance(buyLimitCap,
                                    market.buyLimitUsage(bid.buyer(), spec.materialKey(),
                                            nowEpochMs - buyLimitWindowMs)) == 0) {
                                accounts.upsertBalance(bid.buyer(),
                                        Diamonds.ofDust(accounts.balanceDust(bid.buyer()))
                                                .plus(Diamonds.ofDust(live.escrowedDust())).dust());
                                if (market.cancelOffer(bid.id()) != 1) {
                                    throw new MarketException(MarketException.Reason.NOTHING_WRITTEN,
                                            "offer " + bid.id() + " could not be cancelled at cap");
                                }
                            }
                        }
                    }

                    // Floor: sell the remainder to the pot at the floor price, bounded by the pot
                    // balance. Untaxed -- the floor is a price support, not a taxed trade.
                    if (remaining > 0 && floorPriceDust > 0) {
                        long potDust = accounts.balanceDust(SystemAccounts.COMMUNITY_POT);
                        int affordable = CommodityMath.floorFillableByPot(remaining, potDust, floorPriceDust);
                        if (affordable > 0) {
                            Diamonds gross = Diamonds.ofDust(floorPriceDust).times(affordable);
                            accounts.upsertBalance(SystemAccounts.COMMUNITY_POT,
                                    Diamonds.ofDust(potDust).minus(gross).dust());
                            accounts.upsertBalance(seller,
                                    Diamonds.ofDust(accounts.balanceDust(seller)).plus(gross).dust());
                            market.insertPending(new PendingItemRow(0L, SystemAccounts.COMMUNITY_POT,
                                    spec.oneItemBytes(), affordable, affordable + "x " + spec.displayName(),
                                    "floor buyback", nowEpochMs, null));
                            market.insertTrade(new TradeRow(0L, nowEpochMs, SystemAccounts.COMMUNITY_POT,
                                    seller, ItemClass.COMMODITY, spec.itemKey(), spec.materialKey(),
                                    affordable, gross.dust(), 0L, 0L, 0L, gross.dust(), null)); // untaxed
                            proceeds = proceeds.plus(gross);
                            remaining -= affordable;
                        }
                    }

                    return new CommoditySaleResult(qty - remaining, remaining, proceeds);
                });
            } catch (LedgerException overflow) {
                if (overflow.reason() == LedgerException.Reason.AMOUNT_TOO_LARGE) {
                    throw new MarketException(MarketException.Reason.AMOUNT_TOO_LARGE,
                            "a commodity sale of " + spec.materialKey() + " overflowed a balance",
                            overflow);
                }
                throw overflow;
            } catch (ArithmeticException overflow) {
                throw new MarketException(MarketException.Reason.AMOUNT_TOO_LARGE,
                        "the tax split for a commodity sale of " + spec.materialKey() + " overflowed",
                        overflow);
            }
        });
    }

    // --------------------------------------------------------------- cancelBid

    /**
     * Takes {@code buyer}'s resting bid down and refunds whatever escrow it still holds. The
     * find-check-refund-cancel runs in one transaction so the offer's status cannot change under it,
     * mirroring {@link #cancel}'s structure. The XP fee the buyer paid to place the bid is <em>not</em>
     * refunded -- it was an anti-spam cost, not escrow.
     *
     * @param buyer      the player cancelling; must own the offer
     * @param offerId    the offer to cancel
     * @param nowEpochMs when the cancellation happened, epoch milliseconds
     * @return a future completing with {@code null}, or failing with a {@link MarketException} whose
     *         reason is {@link MarketException.Reason#LISTING_UNAVAILABLE},
     *         {@link MarketException.Reason#NOT_YOUR_LISTING}, or
     *         {@link MarketException.Reason#NOTHING_WRITTEN}
     */
    public CompletableFuture<Void> cancelBid(UUID buyer, long offerId, long nowEpochMs) {
        Objects.requireNonNull(buyer, "buyer");
        return executor.submit(() -> transactions.inTransaction(() -> {
            CommodityOfferRow offer = market.findActiveOffer(offerId)
                    .orElseThrow(() -> new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                            "offer " + offerId + " is not active"));
            if (!offer.buyer().equals(buyer)) {
                throw new MarketException(MarketException.Reason.NOT_YOUR_LISTING,
                        "offer " + offerId + " belongs to someone else");
            }
            accounts.upsertBalance(buyer, Diamonds.ofDust(accounts.balanceDust(buyer))
                    .plus(Diamonds.ofDust(offer.escrowedDust())).dust());
            if (market.cancelOffer(offerId) != 1) {
                // Same single-writer anomaly guard as the sale's spendFromOffer==1.
                throw new MarketException(MarketException.Reason.NOTHING_WRITTEN,
                        "offer " + offerId + " left ACTIVE mid-cancel; rolling back");
            }
            return null;
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

    /**
     * A buyer's still-{@code ACTIVE} bids, newest first -- the ones they can still cancel.
     *
     * @param buyer the buyer to read
     * @return a future completing with the buyer's active bids
     */
    public CompletableFuture<List<CommodityOfferRow>> myBids(UUID buyer) {
        Objects.requireNonNull(buyer, "buyer");
        return executor.submit(() -> market.offersByBuyer(buyer, OfferStatus.ACTIVE));
    }

    /**
     * The highest price any {@code ACTIVE} bid is offering for {@code materialKey}, or
     * {@link Optional#empty()} if the book holds no resting bid for it.
     *
     * @param materialKey the material to read the best bid for
     * @return a future completing with the best bid price in dust, or empty when the book is empty
     */
    public CompletableFuture<Optional<Long>> bestBidDust(String materialKey) {
        Objects.requireNonNull(materialKey, "materialKey");
        return executor.submit(() -> market.bestBidPriceDust(materialKey));
    }
}
