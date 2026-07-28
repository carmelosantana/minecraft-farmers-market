/*
 * FarmersMarket - the lifecycle state of a market listing.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.util.Locale;
import java.util.Objects;

/**
 * The lifecycle state of a {@code listings} row, stored as {@code TEXT}.
 *
 * <p>{@code ACTIVE} is the only state the sale and browse paths read from; {@code SOLD},
 * {@code CANCELLED}, and {@code EXPIRED} are the three terminal states a listing leaves
 * {@code ACTIVE} for and never returns from.
 */
public enum ListingStatus {
    ACTIVE,
    SOLD,
    CANCELLED,
    EXPIRED;

    /**
     * Parses the stored {@code TEXT} back into a status, case-insensitively.
     *
     * @param stored the {@code status} column value read from SQLite
     * @return the matching status
     * @throws NullPointerException     if {@code stored} is {@code null}
     * @throws IllegalArgumentException if {@code stored} names no known status
     */
    public static ListingStatus fromStored(String stored) {
        Objects.requireNonNull(stored, "stored");
        return valueOf(stored.toUpperCase(Locale.ROOT));
    }
}
