/*
 * FarmersMarket - the well-known non-player accounts the market pays into.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.util.UUID;

/**
 * The well-known, non-player accounts the market moves money into.
 *
 * <p>These are real {@code accounts} rows keyed on a reserved UUID, so the same ledger and the
 * same non-negative {@code CHECK} that guard player balances guard them too -- the community pot
 * is not a special case in the money model, only in who owns it.
 */
public final class SystemAccounts {

    /** The community pot: the nil UUID, which no real or Floodgate-synthetic player is assigned. */
    public static final UUID COMMUNITY_POT = new UUID(0L, 0L);

    private SystemAccounts() {
    }

    /**
     * Whether {@code uuid} is one of the market's own accounts rather than a player's.
     *
     * @param uuid the account to test
     * @return {@code true} if {@code uuid} is a reserved system account
     */
    public static boolean isSystem(UUID uuid) {
        return COMMUNITY_POT.equals(uuid);
    }
}
