/*
 * FarmersMarket - typed failure for bad amounts and insufficient funds.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.ledger;

import java.util.Objects;

/**
 * Every way a ledger operation can refuse, carrying a {@link Reason} the command layer maps to
 * a player-facing message.
 *
 * <p>The reason, not the message, is the contract. Nothing in this package formats text for a
 * player: the ledger has no idea what locale, colour scheme, or chat platform the caller is
 * writing to, and a ledger that returns pre-rendered chat strings cannot be reused by anything
 * that is not chat. The {@code message} on this exception is for server logs and stack traces.
 *
 * <p>Unchecked on purpose. These are refusals of a specific request, not conditions every
 * caller up the stack must declare -- the ledger's public methods all return
 * {@link java.util.concurrent.CompletableFuture}, so a refusal arrives as the future's cause
 * rather than as a checked throw the caller could ignore.
 */
public final class LedgerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a ledger operation refused. Task 5 maps each of these to one player-facing message. */
    public enum Reason {

        /** The account holds less than the operation asked to remove from it. */
        INSUFFICIENT_FUNDS,

        /** The text handed to {@link Diamonds#parse} is not a plain decimal amount. */
        MALFORMED_AMOUNT,

        /**
         * The result would not fit in a {@code long} of dust. Refused rather than allowed to wrap,
         * because a wrapped balance is a negative balance.
         */
        AMOUNT_TOO_LARGE,

        /** A negative amount was handed to an operation that only accepts non-negative ones. */
        NEGATIVE_AMOUNT,

        /**
         * Storage failed, and the failure provably happened <b>before any write was attempted</b>
         * -- so the operation is a definite refusal, not an unknown outcome.
         *
         * <p><b>This reason is a promise about money, and it may only be set where that promise
         * can be proved.</b> The command layer treats every {@link LedgerException} as
         * definitely-not-applied and compensates accordingly: a deposit hands the player's items
         * straight back. If this reason were ever set for a failure that might have committed,
         * those items would be handed back on top of a credit that landed, and that is a dupe.
         * A dupe is silent, repeatable, and spreads through every later trade with no marker on
         * it; an undelivered stack self-reports within minutes. So the rule is one-directional:
         * anything not provably pre-write stays an ordinary {@code SQLException} and reaches the
         * player as an unknown outcome, with the items held and a log line for a human.
         *
         * <p>Today exactly one kind of site qualifies: the balance {@code SELECT} that
         * {@code deposit} and {@code withdraw} each run before their {@code UPDATE}. A read that
         * throws has written nothing. Both operations answer that question through the same
         * method, on purpose -- two paths answering "was anything written?" differently is the
         * shape that produced this plugin's one money-creation bug.
         */
        NOTHING_WRITTEN
    }

    private final Reason reason;

    /**
     * @param reason  why the operation refused; never {@code null}
     * @param message detail for the server log, never shown to a player verbatim
     */
    public LedgerException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * @param reason  why the operation refused; never {@code null}
     * @param message detail for the server log, never shown to a player verbatim
     * @param cause   the underlying failure, typically an {@link ArithmeticException} from
     *                {@code Math.addExact} and friends
     */
    public LedgerException(Reason reason, String message, Throwable cause) {
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
