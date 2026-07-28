/*
 * FarmersMarket - unit tests for the ItemClass classification rule.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ItemClassTest {

    @Test
    void plainStackableIsCommodity() {
        assertEquals(ItemClass.COMMODITY, ItemClass.classify(64, false));
        assertEquals(ItemClass.COMMODITY, ItemClass.classify(16, false));
    }

    @Test
    void anythingWithMeaningfulComponentsIsUnique() {
        assertEquals(ItemClass.UNIQUE, ItemClass.classify(64, true));
        assertEquals(ItemClass.UNIQUE, ItemClass.classify(1, true));
    }

    @Test
    void aSingleStackItemIsUniqueEvenWhenPlain() {
        // A diamond sword stacks to 1; it cannot be pooled as a fungible commodity.
        assertEquals(ItemClass.UNIQUE, ItemClass.classify(1, false));
    }
}
