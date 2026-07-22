/*
 * FarmersMarket - tests for the pure Floodgate-link account merge rules.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.xpfarm.farmersmarket.storage.AccountRow;

/**
 * Exhaustively covers {@link AccountMerge#merge}, the rule that prevents a linking Bedrock
 * player from losing their balance -- see the class javadoc on {@link AccountMerge} for why
 * this merge exists at all.
 */
final class AccountMergeTest {

    private static final UUID FLOODGATE_UUID = UUID.fromString("00000000-0000-0000-0000-00000000f10e");
    private static final UUID JAVA_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void mergeSumsBalancesAndKeepsEarliestCreation() {
        AccountRow floodgate = new AccountRow(FLOODGATE_UUID, 2_500L, 1_000L, 5_000L);
        AccountRow java = new AccountRow(JAVA_UUID, 1_000L, 3_000L, 4_000L);

        AccountRow merged = AccountMerge.merge(floodgate, java);

        assertEquals(JAVA_UUID, merged.uuid(), "the surviving identity is the Java UUID");
        assertEquals(3_500L, merged.diamondsDust());
        assertEquals(1_000L, merged.createdAtEpochMs(), "earliest creation survives");
        assertEquals(5_000L, merged.updatedAtEpochMs(), "latest update survives");
    }

    @Test
    void mergeIntoAnAbsentJavaAccountCarriesTheWholeBalance() {
        AccountRow floodgate = new AccountRow(FLOODGATE_UUID, 7_000L, 1_000L, 1_000L);
        AccountRow emptyJava = new AccountRow(JAVA_UUID, 0L, 9_000L, 9_000L);

        assertEquals(7_000L, AccountMerge.merge(floodgate, emptyJava).diamondsDust());
    }

    @Test
    void mergeIsLosslessForAnyPairOfNonNegativeBalances() {
        for (long a = 0; a < 1_000; a += 37) {
            for (long b = 0; b < 1_000; b += 41) {
                AccountRow merged = AccountMerge.merge(
                        new AccountRow(FLOODGATE_UUID, a, 1L, 1L),
                        new AccountRow(JAVA_UUID, b, 1L, 1L));
                assertEquals(a + b, merged.diamondsDust());
            }
        }
    }

    @Test
    void mergeRejectsNullArguments() {
        AccountRow row = new AccountRow(JAVA_UUID, 0L, 1L, 1L);

        assertThrows(NullPointerException.class, () -> AccountMerge.merge(null, row));
        assertThrows(NullPointerException.class, () -> AccountMerge.merge(row, null));
    }
}
