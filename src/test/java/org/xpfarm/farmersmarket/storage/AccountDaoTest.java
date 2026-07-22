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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
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
    void upsertAccountPersistsTheRowsOwnTimestampsRatherThanTheClock() {
        // The merge path computes min(created_at) and max(updated_at) across the two accounts
        // being folded together. If this method stamped its own clock -- as upsertBalance does --
        // both computed values would be discarded on the way to the database and the merge rule
        // would be decoration.
        UUID player = UUID.randomUUID();
        AccountRow row = new AccountRow(player, 7L, 111L, 222L);

        assertDoesNotThrow(() -> dao.upsertAccount(row));

        AccountRow stored = assertDoesNotThrow(() -> dao.findAccount(player).orElseThrow());
        assertEquals(7L, stored.diamondsDust());
        assertEquals(111L, stored.createdAtEpochMs());
        assertEquals(222L, stored.updatedAtEpochMs());
    }

    @Test
    void upsertAccountOverwritesAnExistingRowsCreatedAtWhichUpsertBalanceRefusesTo() throws SQLException {
        // The two upserts differ here and nowhere else. A linking Bedrock player's older
        // creation time has to be able to replace the Java row's newer one.
        UUID player = UUID.randomUUID();
        dao.upsertAccount(new AccountRow(player, 1L, 9_000L, 9_000L));

        dao.upsertAccount(new AccountRow(player, 2L, 1_000L, 9_500L));

        AccountRow stored = dao.findAccount(player).orElseThrow();
        assertEquals(2L, stored.diamondsDust());
        assertEquals(1_000L, stored.createdAtEpochMs(), "an earlier creation time must be able to win");
        assertEquals(9_500L, stored.updatedAtEpochMs());
        assertEquals(1, countAccountRows());
    }

    @Test
    void upsertAccountRejectsANegativeBalanceLikeEveryOtherWrite() {
        UUID player = UUID.randomUUID();

        assertThrows(SQLException.class, () -> dao.upsertAccount(new AccountRow(player, -1L, 1L, 1L)));
    }

    @Test
    void findLinkReturnsTheJavaUuidForARecordedLink() throws SQLException {
        UUID floodgate = UUID.randomUUID();
        UUID java = UUID.randomUUID();
        dao.insertLink(floodgate, java, 1L);

        assertEquals(Optional.of(java), dao.findLink(floodgate));
    }

    @Test
    void findLinkIsEmptyForAnUnlinkedUuidAndDoesNotMatchTheJavaSideOfALink() throws SQLException {
        // Keyed on the Floodgate UUID only. Answering "yes" for the Java UUID of an existing
        // link would make a second merge think it had already run and strand a balance.
        UUID floodgate = UUID.randomUUID();
        UUID java = UUID.randomUUID();
        dao.insertLink(floodgate, java, 1L);

        assertTrue(dao.findLink(UUID.randomUUID()).isEmpty());
        assertTrue(dao.findLink(java).isEmpty());
    }

    @Test
    void findLinkSeesTheCorrectedTargetAfterALinkIsOverwritten() throws SQLException {
        UUID floodgate = UUID.randomUUID();
        UUID firstJava = UUID.randomUUID();
        UUID secondJava = UUID.randomUUID();
        dao.insertLink(floodgate, firstJava, 1L);

        dao.insertLink(floodgate, secondJava, 2L);

        assertEquals(Optional.of(secondJava), dao.findLink(floodgate));
    }

    @Test
    void findLinkAndAllLinksAgreeOnEveryRecordedLink() throws SQLException {
        // findLink replaced a scan of allLinks() on the join path; if the two ever disagreed,
        // the merge would either run twice or never.
        for (int i = 0; i < 5; i++) {
            dao.insertLink(UUID.randomUUID(), UUID.randomUUID(), i);
        }

        for (UUID[] link : dao.allLinks()) {
            assertEquals(Optional.of(link[1]), dao.findLink(link[0]));
        }
        assertEquals(5, dao.allLinks().size());
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
