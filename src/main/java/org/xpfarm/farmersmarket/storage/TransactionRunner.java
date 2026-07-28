/*
 * FarmersMarket - the money-safe SQL transaction primitive shared across the plugin.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Runs a body of work inside one SQL transaction on the shared connection, committing on success
 * and rolling back on any failure whatsoever.
 *
 * <p>This is the plugin's single copy of the commit/rollback/autocommit-restore dance. It lives
 * here, in one place, on purpose: a second hand-rolled copy is the precise shape that already bit
 * this plugin once, when {@code Migrations} shipped the same {@code Error}-skips-rollback bug the
 * ledger had already fixed, because the discipline lived in two places instead of one. Anything
 * that needs a transaction -- the ledger's transfer and merge, an M2 sale that moves money and
 * writes an escrow row and a trade-log row together -- runs through this class rather than
 * reimplementing it.
 *
 * <p><b>Guarded on the connection's autocommit state, not on instance state.</b> The nesting
 * refusal below reads {@link Connection#getAutoCommit()} on the one shared connection, so any
 * number of {@code TransactionRunner} instances over that connection still correctly refuse to
 * nest. That is what lets the ledger hold one and an M2 {@code MarketService} hold another without
 * a shared re-entrancy count.
 */
public final class TransactionRunner {

    private final Database database;

    public TransactionRunner(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Runs {@code work} inside one SQL transaction on the connection's own thread, committing on
     * success and rolling back on any failure whatsoever.
     *
     * <p><b>{@code Throwable}, not {@code Exception}, and that distinction is money.</b> An
     * {@link Error} -- an {@link OutOfMemoryError} between a transfer's debit and its credit, say
     * -- would skip a narrower {@code catch}, and restoring autocommit on the way out is
     * implemented by the driver as a {@code COMMIT} of the still-open transaction. The debit
     * would be committed with no credit and the player's diamonds destroyed, with nothing but an
     * exceptionally-completed future to show for it, because {@code DatabaseExecutor#submit}
     * catches {@code Throwable} and the writer thread survives. Every failure rolls back here,
     * and every failure is rethrown -- an {@code Error} is never swallowed.
     *
     * <p>Autocommit is restored on the way out, success or failure, so this never leaves the
     * shared connection in a mode a later caller did not expect. Neither the rollback nor that
     * restoration may replace the failure that caused them: the caller switches on the failure's
     * type to choose what to tell the player, and a {@link SQLException} thrown out of cleanup
     * would hide it. Both are attached to the original as suppressed exceptions instead. On the
     * success path there is no original to hide, so a failed restoration propagates rather than
     * leaving the connection silently stuck outside autocommit.
     *
     * <p><b>Nesting is refused outright.</b> There is one connection, so an inner call's
     * {@code commit()} would commit whatever the outer call had applied so far and then hand the
     * outer call a transaction it no longer owns -- reopening exactly the half-applied-transfer
     * window this primitive exists to close. JDBC offers no way to detect that other than
     * autocommit already being off, so that is the check, made before anything is written.
     *
     * <p><b>M2 is the milestone that makes nesting reachable, and the ledger's transfer and merge
     * are where it will be reached from.</b> Each opens its own transaction; an M2 sale that wants
     * the money move, the escrow row, and the immutable trade-log row to commit or fail together
     * cannot get that by wrapping either of them, and the guard above turns the attempt into an
     * {@link IllegalStateException} rather than a silently half-applied trade. The way to compose
     * is to write the whole operation as one {@code inTransaction} body over {@link AccountDao},
     * reusing the {@code Diamonds} arithmetic for the overflow checks, or to add a method that owns
     * the whole operation. Do not add a re-entrancy count or a savepoint to make nesting work:
     * SQLite savepoints would let an outer failure keep an inner success, which for a trade log is
     * worse than refusing.
     *
     * @param work the body to run inside the transaction
     * @param <T>  the type {@code work} returns
     * @return whatever {@code work} returned, once the transaction has committed
     * @throws IllegalStateException if the shared connection is already inside a transaction
     * @throws Exception             rethrown from {@code work}, or a {@link SQLException} from the
     *                               commit or from restoring autocommit on the success path
     */
    public <T> T inTransaction(Callable<T> work) throws Exception {
        Connection connection = database.connection();
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("nested transaction: this connection is already "
                    + "inside one, and the inner commit would commit the outer caller's "
                    + "half-applied work");
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try {
            T result = work.call();
            connection.commit();
            return result;
        } catch (Throwable t) {
            failure = t;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                t.addSuppressed(rollbackFailure);
            }
            throw t;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreFailure) {
                if (failure == null) {
                    throw restoreFailure;
                }
                failure.addSuppressed(restoreFailure);
            }
        }
    }
}
