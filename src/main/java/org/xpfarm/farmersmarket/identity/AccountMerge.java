/*
 * FarmersMarket - pure rules for folding a Floodgate account into its linked Java account.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.identity;

import java.util.Objects;
import org.xpfarm.farmersmarket.storage.AccountRow;

/**
 * Computes the surviving {@link AccountRow} when a Bedrock player links their Java account.
 *
 * <p>An unlinked Bedrock player gets a synthetic, XUID-derived UUID from Floodgate. Once they
 * link a Java account, Floodgate reports their real Java UUID instead -- Floodgate itself does
 * not migrate any plugin data, so without this rule a linking player's balance is simply
 * orphaned under a UUID nothing ever looks up again.
 *
 * <p>This class is deliberately I/O-free: {@link #merge} touches no database and no Bukkit API,
 * which is what makes it exhaustively testable over the whole space of balance pairs. Applying
 * the result -- deleting the Floodgate row, upserting the Java row, recording the link -- is the
 * caller's job, not this class's.
 */
public final class AccountMerge {

    private AccountMerge() {
    }

    /**
     * Folds {@code from} (the Floodgate-keyed account being retired) into {@code into} (the
     * Java-keyed account that survives linking).
     *
     * <p>The surviving row keeps {@code into}'s UUID -- the real Java identity -- and sums both
     * balances so the merge is lossless. It keeps the earliest of the two creation timestamps,
     * since the player's account genuinely existed from that point on, and the latest of the two
     * update timestamps, since that is the more recent true state.
     *
     * @param from the Floodgate-keyed account, about to be retired
     * @param into the Java-keyed account the player linked to; may already hold a balance of
     *             its own if the player played on Java before ever connecting via Bedrock
     * @return a new {@link AccountRow} keyed on {@code into}'s UUID with both balances combined
     */
    public static AccountRow merge(AccountRow from, AccountRow into) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
        return new AccountRow(
                into.uuid(),
                from.diamondsDust() + into.diamondsDust(),
                Math.min(from.createdAtEpochMs(), into.createdAtEpochMs()),
                Math.max(from.updatedAtEpochMs(), into.updatedAtEpochMs()));
    }
}
