/*
 * FarmersMarket - unit tests for AccountDao.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AccountDao} against a real, migrated SQLite file under a JUnit
 * {@code @TempDir}. No mocking: the whole point of {@link AccountDao} is the SQL it runs,
 * and mocking the connection would prove nothing about whether that SQL is correct.
 */
class AccountDaoTest {

    private Database database;
    private AccountDao dao;

    @BeforeEach
    void openMigratedDatabase(@TempDir Path dir) throws Exception {
        database = Database.open(dir.resolve("market.db"), dir.resolve("tmp").toString(), 5000);
        Migrations.applyTo(database.connection());
        dao = new AccountDao(database);
    }

    @Test
    void unknownUuidReadsZeroBalance() throws SQLException {
        assertEquals(0L, dao.balanceDust(UUID.randomUUID()));
    }

    @Test
    void unknownUuidHasNoAccountRow() throws SQLException {
        assertTrue(dao.findAccount(UUID.randomUUID()).isEmpty());
    }

    @Test
    void upsertThenReadRoundTrips() throws SQLException {
        UUID player = UUID.randomUUID();
        dao.upsertBalance(player, 42L);

        assertEquals(42L, dao.balanceDust(player));
        Optional<AccountRow> row = dao.findAccount(player);
        assertTrue(row.isPresent());
        assertEquals(player, row.get().uuid());
        assertEquals(42L, row.get().diamondsDust());
        assertTrue(row.get().createdAtEpochMs() > 0);
        assertTrue(row.get().updatedAtEpochMs() > 0);
    }

    @Test
    void upsertTwiceUpdatesRatherThanDuplicating() throws SQLException {
        UUID player = UUID.randomUUID();
        dao.upsertBalance(player, 10L);
        dao.upsertBalance(player, 25L);

        assertEquals(25L, dao.balanceDust(player));
        assertEquals(1, countAccountRows());
    }

    @Test
    void upsertPreservesCreatedAtAcrossUpdates() throws SQLException, InterruptedException {
        UUID player = UUID.randomUUID();
        dao.upsertBalance(player, 10L);
        long createdAt = dao.findAccount(player).orElseThrow().createdAtEpochMs();

        Thread.sleep(5);
        dao.upsertBalance(player, 11L);

        assertEquals(createdAt, dao.findAccount(player).orElseThrow().createdAtEpochMs());
    }

    @Test
    void negativeBalanceIsRejectedByCheckConstraint() {
        UUID player = UUID.randomUUID();
        assertThrows(SQLException.class, () -> dao.upsertBalance(player, -1L));
    }

    @Test
    void linkInsertsAndReadsBack() throws SQLException {
        UUID floodgate = UUID.randomUUID();
        UUID java = UUID.randomUUID();
        long mergedAt = System.currentTimeMillis();

        dao.insertLink(floodgate, java, mergedAt);

        List<UUID[]> links = dao.allLinks();
        assertEquals(1, links.size());
        assertEquals(floodgate, links.get(0)[0]);
        assertEquals(java, links.get(0)[1]);
    }

    @Test
    void reinsertingSameFloodgateUuidOverwritesRatherThanFailing() throws SQLException {
        UUID floodgate = UUID.randomUUID();
        UUID firstJava = UUID.randomUUID();
        UUID secondJava = UUID.randomUUID();

        dao.insertLink(floodgate, firstJava, 1L);
        dao.insertLink(floodgate, secondJava, 2L);

        List<UUID[]> links = dao.allLinks();
        assertEquals(1, links.size());
        assertEquals(secondJava, links.get(0)[1]);
    }

    @Test
    void deleteAccountRemovesTheRow() throws SQLException {
        UUID player = UUID.randomUUID();
        dao.upsertBalance(player, 5L);

        dao.deleteAccount(player);

        assertFalse(dao.findAccount(player).isPresent());
        assertEquals(0L, dao.balanceDust(player));
    }

    private int countAccountRows() throws SQLException {
        try (var statement = database.connection().createStatement();
                var rs = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
