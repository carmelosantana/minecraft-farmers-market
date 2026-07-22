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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DatabaseExecutor} directly -- no database involved, since the guarantee
 * under test is the queue's flush-on-close behaviour, not anything SQL-specific.
 */
class DatabaseExecutorTest {

    @Test
    void closeFlushesEveryQueuedTaskRatherThanAbandoningIt() {
        DatabaseExecutor executor = new DatabaseExecutor();
        AtomicInteger counter = new AtomicInteger();

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                counter.incrementAndGet();
                return null;
            });
        }
        executor.close();

        assertEquals(100, counter.get());
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
}
