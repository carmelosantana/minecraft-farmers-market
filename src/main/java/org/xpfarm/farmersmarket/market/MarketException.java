/*
 * FarmersMarket - typed failure for the market's list, buy, cancel, expire, and claim operations.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.util.Objects;

/**
 * Every way a market operation can refuse, carrying a {@link Reason} the command layer maps to a
 * player-facing message.
 *
 * <p>Modelled on {@code org.xpfarm.farmersmarket.ledger.LedgerException}, and for the same reasons.
 * The reason, not the message, is the contract: nothing in this package formats text for a player,
 * because the market has no idea what locale, colour scheme, or chat platform the caller is writing
 * to. The {@code message} on this exception is for server logs and stack traces.
 *
 * <p>Unchecked on purpose. These are refusals of a specific request, not conditions every caller up
 * the stack must declare -- {@code MarketService}'s public methods all return
 * {@link java.util.concurrent.CompletableFuture}, so a refusal arrives as the future's cause rather
 * than as a checked throw the caller could ignore.
 *
 * <p><b>The cause's type is the contract, and it says whether anything was written.</b> A
 * {@code MarketException} means the operation was <em>refused</em> and nothing in the database
 * changed, so the command layer may safely compensate -- hand the seller their item back, charge no
 * fee, leave the listing on sale. Any other cause means the outcome is <em>unknown</em>, not failed,
 * and nothing may be compensated in either direction. The sale is one transaction that rolls back
 * whole on any throw, so every {@code MarketException} it raises -- including the overflow refusals
 * translated at {@code MarketService}'s boundary -- is a genuine nothing-written refusal.
 */
public final class MarketException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a market operation refused. Task 5's command layer maps each to one player-facing message. */
    public enum Reason {

        /** The listing is not on sale: it never existed, or has already sold, cancelled, or expired. */
        LISTING_UNAVAILABLE,

        /** A seller tried to buy their own listing. */
        SELF_PURCHASE,

        /** The buyer holds less than the listing's gross price. */
        INSUFFICIENT_FUNDS,

        /** A player tried to cancel a listing that is not theirs. */
        NOT_YOUR_LISTING,

        /** The seller is already at the maximum number of active listings. */
        TOO_MANY_LISTINGS,

        /** A commodity listing was attempted before the commodity market is open. */
        COMMODITY_NOT_YET,

        /** A bid or sell names a material that is not a fungible commodity. */
        NOT_A_COMMODITY,

        /**
         * Storage failed, and the failure provably happened <b>before any write was attempted</b> --
         * so the operation is a definite refusal, not an unknown outcome. Set only where that
         * promise can be proved, exactly as the ledger's counterpart is: today, the active-listing
         * count that {@code list} reads before its insert.
         */
        NOTHING_WRITTEN,

        /**
         * A dust total would not fit in a {@code long}. Refused rather than allowed to wrap, because
         * a wrapped balance is a negative balance. Unreachable at this server's scale, but if it
         * fires it does so inside the sale's transaction, which rolls back whole, so it is a
         * refusal.
         */
        AMOUNT_TOO_LARGE
    }

    private final Reason reason;

    /**
     * @param reason  why the operation refused; never {@code null}
     * @param message detail for the server log, never shown to a player verbatim
     */
    public MarketException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * @param reason  why the operation refused; never {@code null}
     * @param message detail for the server log, never shown to a player verbatim
     * @param cause   the underlying failure, such as an {@code SQLException} from a pre-write read
     *                or an overflow raised by {@code Diamonds} arithmetic
     */
    public MarketException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Why this operation refused.
     *
     * @return the reason; never {@code null}
     */
    public Reason reason() {
        return reason;
    }
}
