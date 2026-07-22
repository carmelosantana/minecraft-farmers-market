/*
 * FarmersMarket - immutable snapshot of one row of the accounts table.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code accounts}, read back verbatim from SQLite.
 *
 * @param uuid              the account owner; always a Java-edition UUID once merged, see
 *                           {@code identity.AccountMerge}
 * @param diamondsDust      the balance in dust, as stored; never negative, enforced by the
 *                           table's own {@code CHECK (diamonds_dust >= 0)} constraint
 * @param createdAtEpochMs  when the row was first inserted, epoch milliseconds
 * @param updatedAtEpochMs  when the row was last written, epoch milliseconds
 */
public record AccountRow(UUID uuid, long diamondsDust, long createdAtEpochMs, long updatedAtEpochMs) {

    public AccountRow {
        Objects.requireNonNull(uuid, "uuid");
    }
}
