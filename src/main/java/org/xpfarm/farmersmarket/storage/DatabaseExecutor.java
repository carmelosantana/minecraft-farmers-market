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
 * that flush: it stops accepting new work, waits up to {@link #SHUTDOWN_TIMEOUT_SECONDS}
 * for the queue to drain, and only if that bound is exceeded does it give up and log how
 * much work it had to abandon. It never drops queued work silently.
 */
public final class DatabaseExecutor implements AutoCloseable {

    /** How long {@link #close()} waits for the queue to drain before giving up on it. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    private static final Logger LOG = Logger.getLogger(DatabaseExecutor.class.getName());

    private final ExecutorService executor;

    public DatabaseExecutor() {
        this.executor = Executors.newSingleThreadExecutor(DatabaseExecutor::newWriterThread);
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
     * task has run, up to {@link #SHUTDOWN_TIMEOUT_SECONDS}.
     *
     * <p>On timeout, this logs a warning naming exactly how many tasks were still queued --
     * never silently -- and then calls {@link ExecutorService#shutdownNow()} to abandon
     * them. An interrupt while waiting is treated the same way: the wait is abandoned, the
     * interrupt flag is restored on this thread, and whatever remains queued is reported
     * before being dropped.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                abandon("did not finish draining its queue within " + SHUTDOWN_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon("was interrupted while waiting for its queue to drain");
        }
    }

    private void abandon(String reason) {
        List<Runnable> stillQueued = executor.shutdownNow();
        LOG.warning("FarmersMarket-DB " + reason + "; abandoning " + stillQueued.size()
                + " queued task(s).");
    }
}
