/*
 * FarmersMarket - single-writer thread for every database access, with a bounded shutdown flush.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Runs every database access on one dedicated thread, {@code FarmersMarket-DB}, so
 * {@link Database}'s single connection is never touched from two threads at once.
 *
 * <p>SQLite serialises writers regardless of connection count -- see the Global Constraints
 * in the M1 plan -- so a pool would buy nothing but contention. Routing every read and
 * write through this one queue instead makes that serialisation explicit rather than
 * accidental.
 *
 * <p><b>{@link #close()} is a correctness boundary, not tidiness.</b> Paper cancels every
 * scheduled plugin task at disable, so any write still sitting in this executor's queue at
 * that moment is lost unless something flushes it synchronously first. {@code close()} is
 * that flush: it stops accepting new work, waits up to {@link #SHUTDOWN_TIMEOUT_MILLIS}
 * for the queue to drain, and only if that bound is exceeded does it give up and log how
 * much work it had to abandon. It never drops queued work silently.
 */
public final class DatabaseExecutor implements AutoCloseable {

    /** How long {@link #close()} waits for the queue to drain before giving up on it. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 10_000;

    /**
     * How long {@link #close()} waits <em>after</em> {@code shutdownNow()} before concluding a
     * task is still executing. Short on purpose: this is not another chance to finish, it is
     * only long enough to tell "the interrupt worked" apart from "the interrupt was ignored",
     * and the caller is a server that is already stopping.
     */
    private static final long ABANDON_GRACE_MILLIS = 2_000;

    private static final Logger LOG = Logger.getLogger(DatabaseExecutor.class.getName());

    private final ExecutorService executor;
    private final long shutdownTimeoutMillis;
    private final long abandonGraceMillis;

    public DatabaseExecutor() {
        this(SHUTDOWN_TIMEOUT_MILLIS, ABANDON_GRACE_MILLIS);
    }

    /**
     * Package-private seam: the production bounds add up to twelve seconds, which no test can
     * afford to wait out, and the timeout path is the one that produces the only reconciliation
     * record a hung write ever leaves. Behaviour is identical either way.
     */
    DatabaseExecutor(long shutdownTimeoutMillis, long abandonGraceMillis) {
        this.executor = Executors.newSingleThreadExecutor(DatabaseExecutor::newWriterThread);
        this.shutdownTimeoutMillis = shutdownTimeoutMillis;
        this.abandonGraceMillis = abandonGraceMillis;
    }

    private static Thread newWriterThread(Runnable task) {
        Thread thread = new Thread(task, "FarmersMarket-DB");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Queues {@code task} to run on the writer thread and returns a future that completes
     * with its result, or exceptionally with whatever it threw.
     *
     * @param task the database access to run; never invoked on the calling thread
     * @return a future completed from the writer thread, in submission order relative to
     *         every other task queued through this same executor
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Stops accepting new work and blocks the calling thread until every already-queued
     * task has run, up to {@link #SHUTDOWN_TIMEOUT_MILLIS}.
     *
     * <p>On timeout, this logs a warning naming exactly what was lost -- never silently --
     * and then calls {@link ExecutorService#shutdownNow()} to abandon it. An interrupt while
     * waiting is treated the same way: the wait is abandoned, the interrupt flag is restored
     * on this thread, and whatever remains is reported before being dropped.
     *
     * <p><b>Two numbers, not one, and the second one is the important one.</b>
     * {@code shutdownNow()} returns only the tasks that had <em>not yet started</em>;
     * a write already inside {@code sqlite-jdbc} is neither in that list nor actually
     * interrupted, because the driver does not honour interrupts mid-statement. Reporting only
     * the returned list therefore prints "abandoning 0 queued task(s)" in exactly the case that
     * matters -- one live write, about to have its connection closed underneath it by
     * {@code onDisable}. So this waits a short grace period after {@code shutdownNow()} and says
     * whether the writer thread ever actually stopped. This warning is the only reconciliation
     * record a shutdown produces; it must not under-report.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                abandon("did not finish draining its queue within " + shutdownTimeoutMillis + "ms");
            }
        } catch (InterruptedException e) {
            // Restore the interrupt flag AFTER abandon(), not before. Restoring it first makes
            // awaitStop()'s awaitTermination throw immediately, so the warning would claim a task
            // was "STILL EXECUTING after <grace>ms" without ever having waited that long -- a true
            // enough conclusion reached by an untrue route, in the one log line an operator uses to
            // reconcile money. Deferring the restore buys the grace period a real chance to observe
            // the writer stopping.
            abandon("was interrupted while waiting for its queue to drain");
            Thread.currentThread().interrupt();
        }
    }

    private void abandon(String reason) {
        List<Runnable> neverStarted = executor.shutdownNow();
        boolean stillRunning = !awaitStop();
        LOG.warning("FarmersMarket-DB " + reason + "; dropped " + neverStarted.size()
                + " queued task(s) that had not started, and " + (stillRunning
                        ? "ONE task was STILL EXECUTING after " + abandonGraceMillis + "ms and an "
                                + "interrupt. sqlite-jdbc does not honour interrupts mid-statement, "
                                + "so that write may still be in progress as the database "
                                + "connection closes: its outcome is unknown. Reconcile the ledger "
                                + "by hand if a player reports a problem."
                        : "no task was left executing."));
    }

    /** Whether the writer thread stopped within the grace period; restores the interrupt flag. */
    private boolean awaitStop() {
        try {
            return executor.awaitTermination(abandonGraceMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }
}
