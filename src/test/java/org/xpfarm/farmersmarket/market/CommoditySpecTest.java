/*
 * FarmersMarket - unit tests for the CommoditySpec value type.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CommoditySpecTest {

    @Test
    void defensivelyCopiesBytesOnBothSides() {
        byte[] src = {1, 2, 3};
        CommoditySpec spec = new CommoditySpec("minecraft:iron_ingot", "abc123", src, "Iron Ingot");
        src[0] = 99;
        assertEquals(1, spec.oneItemBytes()[0], "compact ctor copied input");
        spec.oneItemBytes()[0] = 42;
        assertEquals(1, spec.oneItemBytes()[0], "accessor returns a copy");
    }

    @Test
    void rejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> new CommoditySpec(null, "k", new byte[]{1}, "n"));
    }
}
