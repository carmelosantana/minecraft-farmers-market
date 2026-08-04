/*
 * FarmersMarket - the lifecycle state of a commodity buy offer.
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
 * The lifecycle state of a {@code commodity_offers} row, stored as {@code TEXT}.
 *
 * <p>{@code ACTIVE} is the only state the fill and book-reading paths read from; {@code FILLED}
 * and {@code CANCELLED} are the two terminal states an offer leaves {@code ACTIVE} for and never
 * returns from.
 */
public enum OfferStatus {
    ACTIVE,
    FILLED,
    CANCELLED;

    /**
     * Parses the stored {@code TEXT} back into a status, case-insensitively.
     *
     * @param stored the {@code status} column value read from SQLite
     * @return the matching status
     * @throws NullPointerException     if {@code stored} is {@code null}
     * @throws IllegalArgumentException if {@code stored} names no known status
     */
    public static OfferStatus fromStored(String stored) {
        Objects.requireNonNull(stored, "stored");
        return valueOf(stored.toUpperCase(Locale.ROOT));
    }
}
