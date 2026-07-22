/*
 * FarmersMarket - unit tests for DatabaseExecutor.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DatabaseExecutor} directly -- no database involved, since the guarantee
 * under test is the queue's flush-on-close behaviour, not anything SQL-specific.
 */
class DatabaseExecutorTest {

    /**
     * {@code close()} must not return until every queued task has run. Paper cancels scheduled
     * plugin tasks at disable, so a queued write that {@code close()} walks away from is a
     * write that never happens -- "a correctness requirement, not politeness", in the plan's
     * words. This is the only test of that.
     *
     * <p><b>The blocking is what makes the test real.</b> A hundred instantaneous
     * {@code incrementAndGet} calls prove nothing: the writer thread drains them concurrently
     * with the submit loop, so a bare {@code shutdown()} with no {@code awaitTermination} would
     * pass too. Here the first task holds the writer thread until a second thread releases it,
     * so at the moment {@code close()} is called the queue provably cannot have drained -- and
     * the counter is asserted to still be zero to prove that, rather than assumed. An executor
     * that did not wait would return with the gate still shut and the counter still at zero.
     */
    @Test
    void closeFlushesEveryQueuedTaskRatherThanAbandoningIt() throws InterruptedException {
        DatabaseExecutor executor = new DatabaseExecutor();
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch firstTaskRunning = new CountDownLatch(1);

        executor.submit(() -> {
            firstTaskRunning.countDown();
            gate.await();
            counter.incrementAndGet();
            return null;
        });
        for (int i = 1; i < 100; i++) {
            executor.submit(() -> {
                counter.incrementAndGet();
                return null;
            });
        }
        assertTrue(firstTaskRunning.await(10, TimeUnit.SECONDS), "the writer thread never started");
        assertEquals(0, counter.get(), "precondition: nothing may have completed yet");

        // Releases the gate only once close() has had time to start blocking on it. The real
        // close() therefore returns after all 100 have run; a close() that did not wait would
        // return while the gate is still shut.
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        }, "gate-opener");
        release.start();

        executor.close();

        assertEquals(100, counter.get(), "close() returned before the queue had drained");
        release.join();
    }

    /**
     * The timeout path: a task that ignores its interrupt -- which is exactly what
     * {@code sqlite-jdbc} does mid-statement -- must be reported as still executing, alongside
     * the count of tasks that never started. {@code shutdownNow()} returns only the latter, so a
     * warning built from it alone says "abandoning 0 queued task(s)" in the one case that costs
     * money: a live write about to have its connection closed underneath it.
     */
    @Test
    void closeReportsBothTheDroppedQueueAndAWriteStillRunningWhenItGivesUp()
            throws InterruptedException {
        List<LogRecord> logged = captureExecutorLog();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        // Short bounds: the production ones add up to twelve seconds of waiting.
        DatabaseExecutor executor = new DatabaseExecutor(200, 200);
        try {
            executor.submit(() -> {
                running.countDown();
                // Ignores interrupts, as sqlite-jdbc does inside a statement.
                while (true) {
                    try {
                        if (release.await(10, TimeUnit.SECONDS)) {
                            return null;
                        }
                    } catch (InterruptedException ignoredExactlyAsTheDriverIgnoresIt) {
                        // Deliberately swallowed: that is the behaviour under test.
                    }
                }
            });
            assertTrue(running.await(10, TimeUnit.SECONDS), "the blocking task never started");
            executor.submit(() -> null);
            executor.submit(() -> null);

            executor.close();

            String warning = onlyWarning(logged);
            assertTrue(warning.contains("dropped 2 queued task(s)"),
                    "the warning must name the 2 queued tasks that were dropped: " + warning);
            assertTrue(warning.contains("STILL EXECUTING"),
                    "the warning must say a write was still running when we gave up: " + warning);
        } finally {
            release.countDown();
            removeCapturedHandlers();
        }
    }

    /**
     * The companion to the test above: when the queue does drain inside the timeout, nothing is
     * warned about at all. Without this, a warning hardcoded to claim a live write would pass.
     */
    @Test
    void aCleanCloseWarnsAboutNothing() {
        List<LogRecord> logged = captureExecutorLog();
        try {
            DatabaseExecutor executor = new DatabaseExecutor(10_000, 200);
            executor.submit(() -> null);
            executor.close();

            assertTrue(logged.isEmpty(), () -> "a clean close must be silent, got " + logged);
        } finally {
            removeCapturedHandlers();
        }
    }

    @Test
    void submitRunsOnADedicatedNamedThread() throws ExecutionException, InterruptedException {
        DatabaseExecutor executor = new DatabaseExecutor();
        try {
            CompletableFuture<String> future = executor.submit(() -> Thread.currentThread().getName());
            assertEquals("FarmersMarket-DB", future.get());
        } finally {
            executor.close();
        }
    }

    @Test
    void submitPropagatesTaskFailureThroughTheFuture() {
        DatabaseExecutor executor = new DatabaseExecutor();
        try {
            CompletableFuture<Void> future = executor.submit(() -> {
                throw new IllegalStateException("boom");
            });
            ExecutionException e = assertThrows(ExecutionException.class, future::get);
            assertTrue(e.getCause() instanceof IllegalStateException);
        } finally {
            executor.close();
        }
    }

    /**
     * An {@link Error} must reach the future too, and the writer thread must survive it.
     *
     * <p>{@code Ledger.inTransaction} rests its whole money argument on this: it rolls back on
     * {@code Throwable} and rethrows, and what makes that safe rather than silent is that
     * {@code submit} catches {@code Throwable}, completes the future, and keeps the one writer
     * thread alive for every queued write behind it. Narrowing {@code submit}'s catch to
     * {@code Exception} leaves the {@code IllegalStateException} test below green while the
     * future here never completes at all -- so this waits with a bound rather than forever, and
     * a timeout is a failure.
     */
    @Test
    void submitPropagatesAnErrorAndTheWriterThreadSurvivesIt() throws Exception {
        DatabaseExecutor executor = new DatabaseExecutor();
        try {
            CompletableFuture<Void> future = executor.submit(() -> {
                throw new StackOverflowError("simulated JVM-level failure");
            });

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> future.get(10, TimeUnit.SECONDS),
                    "the Error never reached the future; submit must catch Throwable");
            assertInstanceOf(StackOverflowError.class, thrown.getCause());

            // The thread that took the Error is the same one that must run the next write.
            assertEquals("FarmersMarket-DB",
                    executor.submit(() -> Thread.currentThread().getName()).get(10, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("the future never completed after an Error", e);
        } finally {
            executor.close();
        }
    }

    @Test
    void resultsCompleteInSubmissionOrder() throws ExecutionException, InterruptedException {
        DatabaseExecutor executor = new DatabaseExecutor();
        try {
            List<Integer> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int value = i;
                futures.add(executor.submit(() -> {
                    observed.add(value);
                    return null;
                }));
            }
            for (CompletableFuture<Void> f : futures) {
                f.get();
            }
            for (int i = 0; i < 20; i++) {
                assertEquals(i, observed.get(i));
            }
        } finally {
            executor.close();
        }
    }

    @Test
    void closeIsSafeToCallMoreThanOnce() {
        DatabaseExecutor executor = new DatabaseExecutor();
        executor.submit(() -> "value");
        executor.close();
        executor.close();
    }

    // ---- log capture -------------------------------------------------------------------
    //
    // The shutdown warning is the only reconciliation record a hung write ever produces, so
    // what it actually says has to be asserted, not assumed. java.util.logging is already on
    // the classpath and this module ships no mocking framework by design.

    private static final Logger EXECUTOR_LOG = Logger.getLogger(DatabaseExecutor.class.getName());

    private static List<LogRecord> captureExecutorLog() {
        List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        EXECUTOR_LOG.addHandler(handler);
        return records;
    }

    private static void removeCapturedHandlers() {
        for (Handler handler : new ArrayList<>(List.of(EXECUTOR_LOG.getHandlers()))) {
            EXECUTOR_LOG.removeHandler(handler);
        }
    }

    private static String onlyWarning(List<LogRecord> logged) {
        assertEquals(1, logged.size(), () -> "expected exactly one warning, got " + logged);
        String message = logged.get(0).getMessage();
        assertFalse(message == null || message.isBlank(), "the warning must say something");
        return message;
    }
}
