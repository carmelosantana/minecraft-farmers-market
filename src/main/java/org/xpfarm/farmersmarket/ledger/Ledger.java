/*
 * FarmersMarket - balances, deposits, withdrawals, atomic transfers, and account merges.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import org.xpfarm.farmersmarket.identity.AccountMerge;
import org.xpfarm.farmersmarket.storage.AccountDao;
import org.xpfarm.farmersmarket.storage.AccountRow;
import org.xpfarm.farmersmarket.storage.Database;
import org.xpfarm.farmersmarket.storage.DatabaseExecutor;

/**
 * The only thing in this plugin that moves money.
 *
 * <p>Every method returns a {@link CompletableFuture} and does all of its work on
 * {@link DatabaseExecutor}'s single writer thread, never on the caller's. Callers on the server
 * main thread must not block on the returned future; they hand it a continuation and, if that
 * continuation touches the Bukkit API, bounce back to the main thread themselves. Nothing in
 * this package knows what a main thread is.
 *
 * <p><b>Failures arrive as the future's cause, not as a throw from the call itself.</b> A
 * refused amount, an insufficient balance, and a SQL error all complete the future
 * exceptionally, so a caller has exactly one place to handle failure instead of two. The only
 * things thrown synchronously are {@link NullPointerException}s for null arguments, which are
 * programming errors rather than outcomes.
 *
 * <p><b>The cause's type is the contract, and it says whether anything was written.</b> A
 * {@link LedgerException} means the operation was <em>refused</em> and nothing in the database
 * changed, so the caller may safely compensate -- hand the items back, drop them, keep them.
 * Any other cause means the outcome is <em>unknown</em>, not failed: a commit followed by a
 * failing cleanup moves the money and still throws. Nothing may be compensated there, in either
 * direction. This class widens the first set only where it can prove the write never started;
 * see {@link LedgerException.Reason#NOTHING_WRITTEN}.
 *
 * <p>Accounts are keyed on UUID and never on username: Floodgate's username prefix is
 * configurable and Java names change, so a name-keyed balance is a balance waiting to be lost.
 *
 * <p><b>Atomicity.</b> {@link #transfer} and {@link #mergeAccounts} each run inside one SQL
 * transaction and roll back completely on any failure, so a half-applied transfer -- money
 * debited from one account and never credited to the other -- cannot be committed. Single-account
 * operations do not need an explicit transaction: {@link AccountDao#upsertBalance} is one
 * statement, and because every ledger operation runs on the same single writer thread, nothing
 * else can interleave between its read and its write.
 */
public final class Ledger {

    private final Database database;
    private final AccountDao accounts;
    private final DatabaseExecutor executor;

    /**
     * @param database the open database, needed for transaction control -- {@link AccountDao}
     *                 deliberately runs no transactions of its own, so the boundary of one has
     *                 to be drawn here where the multi-statement operations live
     * @param accounts the DAO every read and write goes through
     * @param executor the single writer thread every operation runs on
     */
    public Ledger(Database database, AccountDao accounts, DatabaseExecutor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * The balance held by {@code player}.
     *
     * @param player the account to read
     * @return a future completing with the balance, which is {@link Diamonds#ZERO} for a player
     *         who has never held anything
     */
    public CompletableFuture<Diamonds> balance(UUID player) {
        Objects.requireNonNull(player, "player");
        return executor.submit(() -> Diamonds.ofDust(accounts.balanceDust(player)));
    }

    /**
     * Adds {@code amount} to {@code player}'s balance.
     *
     * <p><b>A failure of the balance read is reported as a refusal, not as an unknown outcome.</b>
     * The read happens before the write is even prepared, so a {@link SQLException} out of it has
     * provably changed nothing; it arrives as
     * {@link LedgerException.Reason#NOTHING_WRITTEN}. That distinction is what lets the command
     * layer hand a depositing player their items straight back instead of holding them pending a
     * human. Everything from the write onwards keeps its raw {@link SQLException}, because a
     * statement that may have committed is an unknown outcome and must stay one.
     *
     * @param player the account to credit
     * @param amount the amount to add; must not be negative
     * @return a future completing with the new balance, or failing with a
     *         {@link LedgerException} whose reason is
     *         {@link LedgerException.Reason#NEGATIVE_AMOUNT},
     *         {@link LedgerException.Reason#AMOUNT_TOO_LARGE}, or
     *         {@link LedgerException.Reason#NOTHING_WRITTEN}
     */
    public CompletableFuture<Diamonds> deposit(UUID player, Diamonds amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        return executor.submit(() -> {
            requireNonNegative(amount);
            Diamonds updated = readBeforeWriting(player).plus(amount);
            accounts.upsertBalance(player, updated.dust());
            return updated;
        });
    }

    /**
     * The balance read that {@code deposit} performs before its write, with a storage failure
     * converted into a {@link LedgerException.Reason#NOTHING_WRITTEN} refusal.
     *
     * <p><b>Only the read is inside the {@code try}, and that is the whole safety argument.</b>
     * A failure here happened before any statement that could change a balance was even prepared,
     * so "nothing was written" is a fact rather than an assumption. Widening this to cover the
     * {@code upsertBalance} that follows would claim the same fact about a statement that may
     * have committed and then failed on the way out -- and the command layer would return the
     * player's items on top of a credit that landed. Never wrap a write in this.
     */
    private Diamonds readBeforeWriting(UUID player) {
        try {
            return Diamonds.ofDust(accounts.balanceDust(player));
        } catch (SQLException readFailure) {
            throw new LedgerException(LedgerException.Reason.NOTHING_WRITTEN,
                    "could not read the balance of " + player + " before crediting it; the write "
                            + "was never attempted", readFailure);
        }
    }

    /**
     * Removes {@code amount} from {@code player}'s balance.
     *
     * <p>A withdrawal larger than the balance is refused outright rather than clamped: the
     * caller is about to hand the player physical diamonds, and a partial withdrawal it did not
     * ask for would hand over the wrong number of them.
     *
     * @param player the account to debit
     * @param amount the amount to remove; must not be negative
     * @return a future completing with the new balance, or failing with a
     *         {@link LedgerException} whose reason is
     *         {@link LedgerException.Reason#NEGATIVE_AMOUNT},
     *         {@link LedgerException.Reason#INSUFFICIENT_FUNDS}, or
     *         {@link LedgerException.Reason#AMOUNT_TOO_LARGE}
     */
    public CompletableFuture<Diamonds> withdraw(UUID player, Diamonds amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        return executor.submit(() -> {
            requireNonNegative(amount);
            Diamonds held = Diamonds.ofDust(accounts.balanceDust(player));
            Diamonds remaining = held.minus(amount);
            if (remaining.isNegative()) {
                throw insufficient(player, held, amount);
            }
            accounts.upsertBalance(player, remaining.dust());
            return remaining;
        });
    }

    /**
     * Moves {@code amount} from {@code from} to {@code to}, atomically.
     *
     * <p>The debit is written before the credit is computed, and both live inside one
     * transaction, so a credit that fails -- because the recipient's balance would overflow, or
     * because the database refuses the write -- rolls the debit back with it. The total held
     * across both accounts is identical before and after a failed transfer, to the dust.
     *
     * <p>A transfer to oneself short-circuits before anything is read, and does nothing. Run
     * through the real path it would debit and then credit the same row, and while the debit-first
     * ordering happens to leave the balance intact, it would also refuse a self-transfer larger
     * than the balance for no reason and would mint outright under any other ordering. Task 5
     * should still reject paying yourself at the command layer, because a silent success is a
     * confusing answer to a command a player did not mean to type.
     *
     * <p><b>M2: this method opens its own transaction. Do not call it from inside one.</b> A sale
     * that wants to move money and write a listing, an escrow row, and a trade-log row atomically
     * cannot wrap this call in {@link #inTransaction} -- there is one connection, and the guard
     * there refuses the nesting outright. Put the whole sale inside one {@link #inTransaction}
     * and use {@link AccountDao} plus the {@link Diamonds} arithmetic directly, or give this
     * class a method that does the whole sale. See {@link #inTransaction} for why.
     *
     * @param from   the account to debit
     * @param to     the account to credit
     * @param amount the amount to move; must not be negative
     * @return a future completing when the transfer is committed, or failing with a
     *         {@link LedgerException} whose reason is
     *         {@link LedgerException.Reason#NEGATIVE_AMOUNT},
     *         {@link LedgerException.Reason#INSUFFICIENT_FUNDS}, or
     *         {@link LedgerException.Reason#AMOUNT_TOO_LARGE}
     */
    public CompletableFuture<Void> transfer(UUID from, UUID to, Diamonds amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        return executor.submit(() -> {
            requireNonNegative(amount);
            if (from.equals(to)) {
                return null;
            }
            // Opens a transaction. M2 is the milestone that makes nesting reachable -- a sale
            // needs the money move, the escrow row, and the trade-log row in one transaction --
            // so if you are here to compose this call into a larger operation, stop: one
            // connection means the inner commit would commit the outer caller's half-applied
            // work, and inTransaction refuses the nesting rather than allowing it. Compose
            // inside a single inTransaction over the DAO instead.
            return inTransaction(() -> {
                Diamonds held = Diamonds.ofDust(accounts.balanceDust(from));
                Diamonds remaining = held.minus(amount);
                if (remaining.isNegative()) {
                    throw insufficient(from, held, amount);
                }
                accounts.upsertBalance(from, remaining.dust());

                Diamonds credited = Diamonds.ofDust(accounts.balanceDust(to)).plus(amount);
                accounts.upsertBalance(to, credited.dust());
                return null;
            });
        });
    }

    /**
     * Folds the balance held under a Bedrock player's pre-link Floodgate UUID into the Java
     * account they linked, atomically and idempotently.
     *
     * <p>Floodgate hands an unlinked Bedrock player a synthetic, XUID-derived UUID. Once they
     * link a Java account it reports the real Java UUID instead, and migrates none of the
     * plugin's data -- so without this the player's balance is stranded under a UUID nothing
     * will ever look up again.
     *
     * <p><b>Idempotent by the link row, not by the balances.</b> This runs on player join, so it
     * will be called again on every subsequent login of the same player. If a row already exists
     * in {@code account_links} for {@code floodgateUuid} the merge is a complete no-op: the
     * Floodgate account is left exactly as it is, not swept a second time. Anything less and a
     * second login would double-credit.
     *
     * @param floodgateUuid the synthetic UUID the player held before linking
     * @param javaUuid      the real Java-edition UUID they linked to
     * @param nowEpochMs    when the merge happened, epoch milliseconds; recorded on the link row
     * @return a future completing when the merge is committed, or failing with a
     *         {@link LedgerException} whose reason is
     *         {@link LedgerException.Reason#AMOUNT_TOO_LARGE} if the two balances cannot be
     *         summed without overflowing
     */
    public CompletableFuture<Void> mergeAccounts(UUID floodgateUuid, UUID javaUuid, long nowEpochMs) {
        Objects.requireNonNull(floodgateUuid, "floodgateUuid");
        Objects.requireNonNull(javaUuid, "javaUuid");
        return executor.submit(() -> {
            // The two sides being the same UUID is not a merge. Left to run, it would read one
            // balance twice, sum it with itself, and double the account.
            if (floodgateUuid.equals(javaUuid)) {
                return null;
            }
            // Opens a transaction, exactly as transfer does, and with the same warning: this is
            // not composable into a larger one. When M2 gives a linking player their listings,
            // escrow, and open offers as well, the transaction has to be widened here rather
            // than by wrapping this call -- see inTransaction's nesting guard.
            return inTransaction(() -> {
                if (alreadyLinked(floodgateUuid)) {
                    return null;
                }

                AccountRow from = accountOrEmpty(floodgateUuid, nowEpochMs);
                AccountRow into = accountOrEmpty(javaUuid, nowEpochMs);

                // AccountMerge.merge sums two raw longs with no overflow guard, because it is a
                // pure rule with no opinion about representable ranges. Running the same sum
                // through Diamonds first is what makes an overflowing pair refuse with
                // AMOUNT_TOO_LARGE instead of wrapping into a negative balance -- which the
                // accounts table's CHECK constraint would then reject with a far less useful
                // error, after the delete had already been staged. This check runs BEFORE the
                // merge is applied and its result is what gets written: the merged row's own
                // balance is never trusted.
                Diamonds total = Diamonds.ofDust(from.diamondsDust()).plus(Diamonds.ofDust(into.diamondsDust()));
                AccountRow merged = AccountMerge.merge(from, into);
                AccountRow survivor = new AccountRow(merged.uuid(), total.dust(),
                        merged.createdAtEpochMs(), merged.updatedAtEpochMs());

                accounts.deleteAccount(floodgateUuid);
                // upsertAccount, not upsertBalance: the merged row's min(created_at) and
                // max(updated_at) are the point of the merge, and upsertBalance would stamp its
                // own clock over both -- silently replacing an older Bedrock account's creation
                // time with the Java row's.
                accounts.upsertAccount(survivor);
                accounts.insertLink(floodgateUuid, javaUuid, nowEpochMs);
                return null;
            });
        });
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
     * exceptionally-completed future to show for it, because {@link DatabaseExecutor#submit}
     * catches {@code Throwable} and the writer thread survives. Every failure rolls back here,
     * and every failure is rethrown -- an {@code Error} is never swallowed.
     *
     * <p>Autocommit is restored on the way out, success or failure, so this never leaves the
     * shared connection in a mode a later caller did not expect. Neither the rollback nor that
     * restoration may replace the failure that caused them: the caller switches on
     * {@link LedgerException#reason()} to choose what to tell the player, and a {@link SQLException}
     * thrown out of cleanup would hide it. Both are attached to the original as suppressed
     * exceptions instead. On the success path there is no original to hide, so a failed
     * restoration propagates rather than leaving the connection silently stuck outside autocommit.
     *
     * <p><b>Nesting is refused outright.</b> There is one connection, so an inner call's
     * {@code commit()} would commit whatever the outer call had applied so far and then hand the
     * outer call a transaction it no longer owns -- reopening exactly the half-applied-transfer
     * window this class exists to close. JDBC offers no way to detect that other than autocommit
     * already being off, so that is the check, made before anything is written.
     *
     * <p><b>M2 is the milestone that makes nesting reachable, and the two existing call sites are
     * where it will be reached from.</b> {@link #transfer} and {@link #mergeAccounts} each open
     * their own transaction; an M2 sale that wants the money move, the escrow row, and the
     * immutable trade-log row to commit or fail together cannot get that by wrapping either of
     * them, and the guard above turns the attempt into an {@link IllegalStateException} rather
     * than a silently half-applied trade. The way to compose is to write the whole operation as
     * one {@code inTransaction} body over {@link AccountDao}, reusing the {@link Diamonds}
     * arithmetic for the overflow checks, or to add a method here that owns the whole operation.
     * Do not add a re-entrancy count or a savepoint to make nesting work: SQLite savepoints would
     * let an outer failure keep an inner success, which for a trade log is worse than refusing.
     *
     * <p>Package-private rather than private purely so {@code LedgerTest} can drive a failure that
     * is not an {@code Exception} through it. There is no other way to reach that path -- the
     * classes this method calls into are all {@code final} -- and the alternative is a guarantee
     * about player money that nothing verifies. It is the narrowest seam that does the job: no
     * production caller outside this class exists, and the behaviour is identical either way.
     */
    <T> T inTransaction(Callable<T> work) throws Exception {
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

    /**
     * Whether {@code floodgateUuid} has already been merged into some Java account.
     *
     * <p>One primary-key lookup. This runs inside the merge transaction on every join of every
     * Bedrock player, so the full-table scan it used to do -- reading {@code allLinks()} and
     * walking it -- grew with the number of players who had ever linked, on the join path.
     */
    private boolean alreadyLinked(UUID floodgateUuid) throws SQLException {
        return accounts.findLink(floodgateUuid).isPresent();
    }

    /**
     * The stored row for {@code uuid}, or an empty one stamped {@code nowEpochMs} if the player
     * has no row at all -- a Bedrock player who linked before ever earning anything still needs
     * both sides of {@link AccountMerge#merge} to exist.
     */
    private AccountRow accountOrEmpty(UUID uuid, long nowEpochMs) throws SQLException {
        Optional<AccountRow> stored = accounts.findAccount(uuid);
        return stored.orElseGet(() -> new AccountRow(uuid, 0L, nowEpochMs, nowEpochMs));
    }

    private static void requireNonNegative(Diamonds amount) {
        if (amount.isNegative()) {
            throw new LedgerException(LedgerException.Reason.NEGATIVE_AMOUNT,
                    "amount must not be negative, got " + amount.format());
        }
    }

    private static LedgerException insufficient(UUID player, Diamonds held, Diamonds amount) {
        return new LedgerException(LedgerException.Reason.INSUFFICIENT_FUNDS,
                player + " holds " + held.format() + " but " + amount.format() + " was requested");
    }
}
