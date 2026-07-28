/*
 * FarmersMarket - unit tests for the ItemKey content hash.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ItemKeyTest {

    @Test
    void identicalBytesHashIdentically() {
        byte[] a = {1, 2, 3, 4};
        assertEquals(ItemKey.forUnique(a), ItemKey.forUnique(new byte[] {1, 2, 3, 4}));
    }

    @Test
    void differentBytesHashDifferently() {
        assertNotEquals(ItemKey.forUnique(new byte[] {1, 2, 3}), ItemKey.forUnique(new byte[] {1, 2, 4}));
    }

    @Test
    void aUniqueKeyIsMarkedAsOne() {
        assertTrue(ItemKey.forUnique(new byte[] {9}).startsWith("u:"));
    }

    @Test
    void theHashIsStableAcrossRuns() {
        // Pinned so a switch of hash algorithm is a visible, deliberate change, never an accident:
        // stored item_keys in a live database would otherwise silently stop matching.
        // The literal is the true SHA-256 of a single zero byte, verified with
        // `printf '\0' | sha256sum`; the task brief's draft value was incorrect and corrected here.
        assertEquals("u:6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
                ItemKey.forUnique(new byte[] {0}));
    }
}
