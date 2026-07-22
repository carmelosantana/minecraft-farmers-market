/*
 * FarmersMarket - unit tests for the ledger's balances, transfers, and account merges.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.ledger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xpfarm.farmersmarket.storage.AccountDao;
import org.xpfarm.farmersmarket.storage.AccountRow;
import org.xpfarm.farmersmarket.storage.Database;
import org.xpfarm.farmersmarket.storage.DatabaseExecutor;
import org.xpfarm.farmersmarket.storage.Migrations;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link Ledger} against a real, migrated SQLite file under a JUnit
 * {@code @TempDir} and a real {@link DatabaseExecutor}. Nothing is mocked: the properties
 * under test here -- that a failed transfer rolls back completely, that a repeated merge does
 * not double-credit -- are properties of the SQL that actually runs, and a mocked connection
 * would assert nothing about any of them.
 */
class LedgerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID FLOODGATE_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private Database database;
    private DatabaseExecutor executor;
    private AccountDao dao;
    private Ledger ledger;

    @BeforeEach
    void openMigratedDatabase(@TempDir Path dir) throws Exception {
        database = Database.open(dir.resolve("market.db"), dir.resolve("tmp").toString(), 5000);
        Migrations.applyTo(database.connection());
        executor = new DatabaseExecutor();
        dao = new AccountDao(database);
        ledger = new Ledger(database, dao, executor);
    }

    @AfterEach
    void closeDatabase() throws Exception {
        executor.close();
        database.close();
    }

    @Test
    void depositThenWithdrawSameAmountLeavesBalanceUnchanged() throws Exception {
        Diamonds start = ledger.balance(PLAYER).get();
        ledger.deposit(PLAYER, Diamonds.ofDiamonds(64)).get();
        ledger.withdraw(PLAYER, Diamonds.ofDiamonds(64)).get();

        assertEquals(start.dust(), ledger.balance(PLAYER).get().dust());
    }

    @Test
    void withdrawingMoreThanHeldFailsAndChangesNothing() throws Exception {
        ledger.deposit(PLAYER, Diamonds.ofDiamonds(5)).get();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> ledger.withdraw(PLAYER, Diamonds.ofDiamonds(6)).get());

        assertInstanceOf(LedgerException.class, thrown.getCause());
        assertEquals(5_000L, ledger.balance(PLAYER).get().dust());
    }

    @Test
    void transferMovesExactlyTheAmountAndConservesTotal() throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(10)).get();
        ledger.deposit(BOB, Diamonds.ofDiamonds(3)).get();

        ledger.transfer(ALICE, BOB, Diamonds.ofDiamonds(4)).get();

        assertEquals(6_000L, ledger.balance(ALICE).get().dust());
        assertEquals(7_000L, ledger.balance(BOB).get().dust());
    }

    @Test
    void failedTransferLeavesBothBalancesExactlyAsTheyWere() throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(2)).get();
        ledger.deposit(BOB, Diamonds.ofDiamonds(3)).get();

        assertThrows(ExecutionException.class,
                () -> ledger.transfer(ALICE, BOB, Diamonds.ofDiamonds(99)).get());

        assertEquals(2_000L, ledger.balance(ALICE).get().dust());
        assertEquals(3_000L, ledger.balance(BOB).get().dust());
    }

    @Test
    void mergeMovesTheFloodgateBalanceOntoTheJavaAccountAndRecordsTheLink() throws Exception {
        ledger.deposit(FLOODGATE_UUID, Diamonds.ofDiamonds(12)).get();
        ledger.deposit(JAVA_UUID, Diamonds.ofDiamonds(3)).get();

        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 1_700_000_000_000L).get();

        assertEquals(15_000L, ledger.balance(JAVA_UUID).get().dust());
        assertEquals(0L, ledger.balance(FLOODGATE_UUID).get().dust());
        assertEquals(1, dao.allLinks().size());
    }

    @Test
    void mergeIsIdempotentAndDoesNotDoubleCredit() throws Exception {
        ledger.deposit(FLOODGATE_UUID, Diamonds.ofDiamonds(12)).get();

        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 1L).get();
        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 2L).get();

        assertEquals(12_000L, ledger.balance(JAVA_UUID).get().dust());
    }

    // --- Beyond the brief -----------------------------------------------------------------

    /**
     * The rollback test the brief pins ({@link #failedTransferLeavesBothBalancesExactlyAsTheyWere})
     * fails on the insufficient-funds check, which happens before either write -- so it proves the
     * pre-flight check works, not that the transaction rolls back. This one fails on the
     * <em>credit</em> side, after the debit has already been written inside the transaction, so
     * the only thing that can restore Alice's balance is a genuine {@code ROLLBACK}.
     */
    @Test
    void transferThatFailsAfterTheDebitIsWrittenRollsThatDebitBack() throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(10)).get();
        seedRaw(BOB, Long.MAX_VALUE);

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> ledger.transfer(ALICE, BOB, Diamonds.ofDiamonds(4)).get());

        assertEquals(LedgerException.Reason.AMOUNT_TOO_LARGE,
                assertInstanceOf(LedgerException.class, thrown.getCause()).reason());
        assertEquals(10_000L, ledger.balance(ALICE).get().dust());
        assertEquals(Long.MAX_VALUE, ledger.balance(BOB).get().dust());
    }

    @Test
    void transferOfMoreThanHeldReportsInsufficientFunds() throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(2)).get();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> ledger.transfer(ALICE, BOB, Diamonds.ofDiamonds(3)).get());

        assertEquals(LedgerException.Reason.INSUFFICIENT_FUNDS,
                assertInstanceOf(LedgerException.class, thrown.getCause()).reason());
    }

    @Test
    void negativeAmountsAreRefusedOnEveryWritePath() throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(5)).get();
        Diamonds negative = Diamonds.ofDust(-1L);

        assertEquals(LedgerException.Reason.NEGATIVE_AMOUNT, reasonOfFailure(
                () -> ledger.deposit(ALICE, negative).get()));
        assertEquals(LedgerException.Reason.NEGATIVE_AMOUNT, reasonOfFailure(
                () -> ledger.withdraw(ALICE, negative).get()));
        assertEquals(LedgerException.Reason.NEGATIVE_AMOUNT, reasonOfFailure(
                () -> ledger.transfer(ALICE, BOB, negative).get()));

        assertEquals(5_000L, ledger.balance(ALICE).get().dust());
        assertEquals(0L, ledger.balance(BOB).get().dust());
    }

    @Test
    void depositThatWouldOverflowIsRefusedAndChangesNothing() throws Exception {
        seedRaw(ALICE, Long.MAX_VALUE);

        assertEquals(LedgerException.Reason.AMOUNT_TOO_LARGE, reasonOfFailure(
                () -> ledger.deposit(ALICE, Diamonds.ofDiamonds(1)).get()));

        assertEquals(Long.MAX_VALUE, ledger.balance(ALICE).get().dust());
    }

    @Test
    void unknownAccountReadsAsEmptyRatherThanFailing() throws Exception {
        assertEquals(0L, ledger.balance(UUID.randomUUID()).get().dust());
    }

    @Test
    void depositAndWithdrawReturnTheNewBalance() throws Exception {
        assertEquals(7_000L, ledger.deposit(PLAYER, Diamonds.ofDiamonds(7)).get().dust());
        assertEquals(2_500L, ledger.withdraw(PLAYER, Diamonds.parse("4.5")).get().dust());
    }

    /**
     * Paying yourself short-circuits before the ledger reads anything, and this pins the
     * short-circuit rather than its arithmetic.
     *
     * <p>The conservation assertion alone proves nothing here: because the debit is written before
     * the credit is read, a self-transfer that ran the full path would read back its own
     * uncommitted debit and land on the original balance anyway. The amount above the balance is
     * the load-bearing case -- it can only succeed if the guard returned before the
     * insufficient-funds check ever ran.
     */
    @Test
    void transferToSelfShortCircuitsBeforeReadingAnything() throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(10)).get();

        ledger.transfer(ALICE, ALICE, Diamonds.ofDiamonds(99)).get();
        ledger.transfer(ALICE, ALICE, Diamonds.ofDiamonds(4)).get();

        assertEquals(10_000L, ledger.balance(ALICE).get().dust());
    }

    /**
     * An {@link Error} between the debit and the credit must roll back like anything else.
     *
     * <p>A {@code catch (Exception)} would skip the rollback, and restoring autocommit on the way
     * out commits the still-open transaction -- so the debit would be committed with no credit and
     * the money destroyed outright. {@code DatabaseExecutor} catches {@code Throwable}, so the
     * writer thread survives and nothing else would ever report it.
     *
     * <p>This drives {@code inTransaction} directly because no production path can be made to
     * throw an {@code Error} on demand: {@code AccountDao}, {@code Database}, and
     * {@code DatabaseExecutor} are all {@code final} and cannot be subclassed to inject one.
     */
    @Test
    void anErrorMidTransactionRollsBackInsteadOfCommittingTheDebit() throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(10)).get();
        ledger.deposit(BOB, Diamonds.ofDiamonds(3)).get();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> executor.submit(() -> ledger.inTransaction(() -> {
                    dao.upsertBalance(ALICE, 6_000L);
                    throw new StackOverflowError("simulated JVM-level failure between debit and credit");
                })).get());

        assertInstanceOf(StackOverflowError.class, thrown.getCause());
        assertEquals(10_000L, ledger.balance(ALICE).get().dust());
        assertEquals(3_000L, ledger.balance(BOB).get().dust());
    }

    /**
     * A nested {@code inTransaction} must be refused before it writes anything.
     *
     * <p>There is one connection, so the inner call's {@code commit()} commits whatever the
     * outer call had applied so far -- here, a debit with no matching credit -- and then leaves
     * the outer call finishing a transaction it no longer owns. That is exactly the
     * half-applied-transfer window this class already had to close once. {@code inTransaction}
     * is package-private and M2 adds callers to this package, so the guard is what stops the
     * next caller reopening it.
     */
    @Test
    void aNestedTransactionIsRefusedRatherThanCommittingTheOuterOnesHalfAppliedWork()
            throws Exception {
        ledger.deposit(ALICE, Diamonds.ofDiamonds(10)).get();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> executor.submit(() -> ledger.inTransaction(() -> {
                    dao.upsertBalance(ALICE, 6_000L);
                    return ledger.inTransaction(() -> {
                        dao.upsertBalance(BOB, 1_000L);
                        return null;
                    });
                })).get());

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        // Without the guard the inner commit lands ALICE's debit; with it, nothing is committed.
        assertEquals(10_000L, ledger.balance(ALICE).get().dust());
        assertEquals(0L, ledger.balance(BOB).get().dust());
    }

    @Test
    void mergeThatWouldOverflowIsRefusedAndLeavesBothAccountsAlone() throws Exception {
        seedRaw(FLOODGATE_UUID, Long.MAX_VALUE);
        seedRaw(JAVA_UUID, 1L);

        assertEquals(LedgerException.Reason.AMOUNT_TOO_LARGE, reasonOfFailure(
                () -> ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 1L).get()));

        assertEquals(Long.MAX_VALUE, ledger.balance(FLOODGATE_UUID).get().dust());
        assertEquals(1L, ledger.balance(JAVA_UUID).get().dust());
        assertEquals(0, dao.allLinks().size());
    }

    @Test
    void mergeOfAnAccountThatNeverExistedStillRecordsTheLink() throws Exception {
        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 42L).get();

        assertEquals(0L, ledger.balance(JAVA_UUID).get().dust());
        assertEquals(1, dao.allLinks().size());
        assertEquals(FLOODGATE_UUID, dao.allLinks().get(0)[0]);
        assertEquals(JAVA_UUID, dao.allLinks().get(0)[1]);
    }

    /**
     * A merge whose two sides are the same UUID would read one balance twice and write their sum,
     * doubling the account. It is not a merge at all, so it does nothing -- not even record a link
     * pointing a UUID at itself.
     */
    @Test
    void mergeOfAnAccountIntoItselfIsARefusedNoOp() throws Exception {
        ledger.deposit(JAVA_UUID, Diamonds.ofDiamonds(9)).get();

        ledger.mergeAccounts(JAVA_UUID, JAVA_UUID, 1L).get();

        assertEquals(9_000L, ledger.balance(JAVA_UUID).get().dust());
        assertEquals(0, dao.allLinks().size());
    }

    /**
     * The second merge is a no-op because a link row already exists, not because the balances
     * happened to work out -- so a balance earned on the Bedrock UUID after the first merge stays
     * where it is rather than being swept a second time.
     */
    @Test
    void mergeAfterTheLinkExistsLeavesTheFloodgateAccountUntouched() throws Exception {
        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 1L).get();
        ledger.deposit(FLOODGATE_UUID, Diamonds.ofDiamonds(4)).get();

        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 2L).get();

        assertEquals(4_000L, ledger.balance(FLOODGATE_UUID).get().dust());
        assertEquals(0L, ledger.balance(JAVA_UUID).get().dust());
        assertEquals(1, dao.allLinks().size());
    }

    /**
     * The merged row's timestamps must reach the database.
     *
     * <p>{@code AccountMerge.merge} computes {@code min(created_at)} and {@code max(updated_at)}
     * across the two accounts. Writing the result through {@code upsertBalance} discarded both --
     * that method stamps its own clock -- so a Bedrock player who had played for a year and then
     * linked a fresh Java account came out of the merge looking newly created. Nothing in
     * production observed the merge rule at all, which is why it is checked here and not only in
     * {@code AccountMergeTest}.
     */
    @Test
    void mergeWritesTheMergedRowsOwnTimestampsSoTheOlderCreationTimeSurvives() throws Exception {
        seedRow(new AccountRow(FLOODGATE_UUID, 2_000L, 1_000L, 5_000L));
        seedRow(new AccountRow(JAVA_UUID, 1_000L, 3_000L, 4_000L));

        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 1_700_000_000_000L).get();

        AccountRow survivor = readRow(JAVA_UUID);
        assertEquals(3_000L, survivor.diamondsDust());
        assertEquals(1_000L, survivor.createdAtEpochMs(),
                "the earlier of the two creation times must survive the merge");
        assertEquals(5_000L, survivor.updatedAtEpochMs(),
                "the later of the two update times must survive the merge");
    }

    /**
     * A merge into an account with no row at all still records a creation time from the
     * Floodgate side, rather than the merge's own clock, for the same reason.
     */
    @Test
    void mergeIntoAnAbsentJavaAccountKeepsTheFloodgateAccountsCreationTime() throws Exception {
        seedRow(new AccountRow(FLOODGATE_UUID, 500L, 1_000L, 2_000L));

        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 8_000L).get();

        AccountRow survivor = readRow(JAVA_UUID);
        assertEquals(500L, survivor.diamondsDust());
        assertEquals(1_000L, survivor.createdAtEpochMs());
        assertEquals(8_000L, survivor.updatedAtEpochMs(),
                "the absent side is stamped with the merge time, so that is the later of the two");
    }

    @Test
    void mergeSumsBothSidesWhenBothHeldABalance() throws Exception {
        ledger.deposit(FLOODGATE_UUID, Diamonds.parse("0.750")).get();
        ledger.deposit(JAVA_UUID, Diamonds.parse("0.251")).get();

        ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 5L).get();

        assertEquals(1_001L, ledger.balance(JAVA_UUID).get().dust());
    }

    /** Writes a balance straight through the DAO, on the executor's thread, bypassing the ledger. */
    private void seedRaw(UUID uuid, long dust) throws Exception {
        executor.submit(() -> {
            dao.upsertBalance(uuid, dust);
            return null;
        }).get();
    }

    /** Writes a whole row, timestamps included, on the executor's thread. */
    private void seedRow(AccountRow row) throws Exception {
        executor.submit(() -> {
            dao.upsertAccount(row);
            return null;
        }).get();
    }

    /** Reads a whole row on the executor's thread; the connection belongs to that thread. */
    private AccountRow readRow(UUID uuid) throws Exception {
        return executor.submit(() -> dao.findAccount(uuid).orElseThrow()).get();
    }

    private LedgerException.Reason reasonOfFailure(org.junit.jupiter.api.function.Executable call) {
        ExecutionException thrown = assertThrows(ExecutionException.class, call);
        return assertInstanceOf(LedgerException.class, thrown.getCause()).reason();
    }
}
