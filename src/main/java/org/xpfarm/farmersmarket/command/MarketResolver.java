/*
 * FarmersMarket - every decision /market makes, as pure functions over strings and integers.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.xpfarm.farmersmarket.ledger.Diamonds;
import org.xpfarm.farmersmarket.ledger.LedgerException;

/**
 * Every decision {@code /market} makes, as pure functions.
 *
 * <p>This class imports nothing from {@code org.bukkit}. Argument parsing, permission gating,
 * the console-has-no-inventory case, tab completion, the mapping from a
 * {@link LedgerException.Reason} to a player-facing sentence, and the arithmetic of how many
 * diamonds fit in an inventory all resolve here; {@link MarketCommand} is left with dispatch,
 * the scheduler hop, and the calls that genuinely need a live server.
 *
 * <p>That split exists because a rule reachable only through a live {@code Player} is a rule no
 * test can check, and the rules below are the ones that move money. It is the same seam
 * {@code FmConfig} and {@code Diamonds} use, and the same one the sibling plugin's
 * {@code CommandResolver} uses.
 *
 * <p><b>Bedrock safety.</b> Every string produced here is plain text: no hover events, no click
 * events, no hex colours, no strikethrough or underline, and no block-ramp glyphs. Colour is
 * applied by the caller from the sixteen legacy names, which is all Geyser carries intact.
 * Messages are kept short and literal because a large share of the players reading them are
 * young.
 */
public final class MarketResolver {

    /** The node every player-facing subcommand requires. */
    public static final String USE_PERMISSION = "farmersmarket.use";

    /** The node {@code reload} requires. */
    public static final String RELOAD_PERMISSION = "farmersmarket.admin.reload";

    /**
     * What a player is told when a ledger call fails with something that is not a
     * {@link LedgerException}.
     *
     * <p>Any other cause means the outcome is <b>unknown</b>, not failed: there is a narrow path
     * where the database commits and the connection cleanup afterwards throws, so the money moved
     * and an exception still surfaced. Nothing is compensated and nothing is retried on this
     * path -- a retry there would charge the player twice -- so the only honest thing to say is
     * that the result is uncertain and where to look.
     */
    public static final String UNCERTAIN_MESSAGE =
            "We could not confirm that. Check /market balance before trying again, "
                    + "and tell an admin if it looks wrong.";

    private MarketResolver() {
    }

    /** The four subcommands M1 implements. */
    public enum Sub {

        /** {@code /market balance}, and the meaning of a bare {@code /market}. */
        BALANCE("balance", USE_PERMISSION, true),

        /** {@code /market deposit [qty]}. */
        DEPOSIT("deposit", USE_PERMISSION, true),

        /** {@code /market withdraw <qty>}. */
        WITHDRAW("withdraw", USE_PERMISSION, true),

        /** {@code /market reload}, deliberately runnable from the console over RCON. */
        RELOAD("reload", RELOAD_PERMISSION, false);

        private final String token;
        private final String permission;
        private final boolean needsPlayer;

        Sub(String token, String permission, boolean needsPlayer) {
            this.token = token;
            this.permission = permission;
            this.needsPlayer = needsPlayer;
        }

        /** The lowercase token a player types. */
        public String token() {
            return token;
        }

        /** The permission node this subcommand requires. */
        public String permission() {
            return permission;
        }

        /** Whether this subcommand needs an inventory and an experience bar to act on. */
        public boolean needsPlayer() {
            return needsPlayer;
        }
    }

    /** What the command should do, or why it should not. */
    public enum Outcome {

        /** First argument is not a known subcommand. */
        UNKNOWN_SUBCOMMAND,

        /** A known subcommand with more arguments than it accepts. */
        TOO_MANY_ARGUMENTS,

        /** Sender lacks the permission the resolved subcommand requires. */
        NO_PERMISSION,

        /** A player-only subcommand run from the console. */
        CONSOLE_NEEDS_PLAYER,

        /** {@code withdraw} with no amount, which has no sensible default. */
        MISSING_AMOUNT,

        /** An amount that is not a whole, positive, representable number of diamonds. */
        BAD_AMOUNT,

        /** Show the sender's balance and experience. */
        BALANCE,

        /** Deposit every diamond in the sender's inventory. */
        DEPOSIT_ALL,

        /** Deposit {@link Resolution#diamonds()} diamonds. */
        DEPOSIT_AMOUNT,

        /** Withdraw {@link Resolution#diamonds()} diamonds. */
        WITHDRAW_AMOUNT,

        /** Re-read {@code config.yml}. */
        RELOAD;

        /** Whether this outcome is a message to report rather than an action to run. */
        public boolean isError() {
            return this == UNKNOWN_SUBCOMMAND || this == TOO_MANY_ARGUMENTS
                    || this == NO_PERMISSION || this == CONSOLE_NEEDS_PLAYER
                    || this == MISSING_AMOUNT || this == BAD_AMOUNT;
        }
    }

    /**
     * A resolved invocation.
     *
     * @param outcome  what to do, or why not
     * @param diamonds the whole number of diamonds involved, for {@link Outcome#DEPOSIT_AMOUNT}
     *                 and {@link Outcome#WITHDRAW_AMOUNT}; {@code 0} otherwise
     * @param message  the message to show, for every {@linkplain Outcome#isError() error}
     *                 outcome and {@code null} otherwise
     */
    public record Resolution(Outcome outcome, long diamonds, String message) {
    }

    /** The usage line, matching {@code plugin.yml}'s {@code usage:} exactly. */
    public static String usage() {
        return "Usage: /market [balance | deposit | withdraw | reload]";
    }

    /** Resolves a typed token to a subcommand, case-insensitively. */
    public static Optional<Sub> subOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (Sub sub : Sub.values()) {
            if (sub.token().equals(normalized)) {
                return Optional.of(sub);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a raw invocation. Never throws.
     *
     * <p>A bare {@code /market} resolves exactly as {@code /market balance} does, permission
     * check and console check included, rather than short-circuiting to its own outcome -- a
     * second path to the same action is a second place for the permission check to be forgotten.
     *
     * <p>Order matters. The subcommand is resolved first, so a sender who mistyped is told about
     * the typo rather than about a permission; then permission is checked against the resolved
     * subcommand's own node, so renaming a node fails the tests instead of silently opening the
     * command up; then the console check, so an operator without permission is not told to go
     * find a player first.
     *
     * @param args           the raw arguments, possibly empty or {@code null}
     * @param senderIsPlayer whether the sender has an inventory and an experience bar
     * @param hasPermission  the sender's permission check
     * @return the resolution; never {@code null}
     */
    public static Resolution resolve(String[] args, boolean senderIsPlayer,
                                     Predicate<String> hasPermission) {
        Objects.requireNonNull(hasPermission, "hasPermission");
        String[] tokens = args == null || args.length == 0 ? new String[] {Sub.BALANCE.token()} : args;

        Optional<Sub> resolved = subOf(tokens[0]);
        if (resolved.isEmpty()) {
            return error(Outcome.UNKNOWN_SUBCOMMAND,
                    "There is no '" + tokens[0] + "' here. " + usage());
        }
        Sub sub = resolved.get();
        if (!hasPermission.test(sub.permission())) {
            return error(Outcome.NO_PERMISSION, "You do not have permission to do that.");
        }
        if (sub.needsPlayer() && !senderIsPlayer) {
            return error(Outcome.CONSOLE_NEEDS_PLAYER,
                    "'" + sub.token() + "' needs a player with an inventory, so the console "
                            + "cannot run it. Console can run /market reload.");
        }
        return switch (sub) {
            case BALANCE -> tokens.length > 1
                    ? tooMany(sub)
                    : new Resolution(Outcome.BALANCE, 0L, null);
            case DEPOSIT -> resolveDeposit(tokens);
            case WITHDRAW -> resolveWithdraw(tokens);
            case RELOAD -> tokens.length > 1
                    ? tooMany(sub)
                    : new Resolution(Outcome.RELOAD, 0L, null);
        };
    }

    private static Resolution resolveDeposit(String[] tokens) {
        if (tokens.length > 2) {
            return tooMany(Sub.DEPOSIT);
        }
        if (tokens.length == 1) {
            return new Resolution(Outcome.DEPOSIT_ALL, 0L, null);
        }
        return amount(tokens[1], Outcome.DEPOSIT_AMOUNT);
    }

    private static Resolution resolveWithdraw(String[] tokens) {
        if (tokens.length > 2) {
            return tooMany(Sub.WITHDRAW);
        }
        if (tokens.length == 1) {
            // Deliberately not "withdraw everything". Deposit has an obvious whole-inventory
            // default; withdraw does not, and guessing at one empties an account on a typo.
            return error(Outcome.MISSING_AMOUNT,
                    "How many? Try /market withdraw 64.");
        }
        return amount(tokens[1], Outcome.WITHDRAW_AMOUNT);
    }

    /**
     * Parses a typed quantity as a whole number of physical diamonds.
     *
     * <p>Both subcommands that take a quantity move real items, and there is no such item as
     * half a diamond, so a fractional amount is refused here rather than silently floored --
     * flooring {@code 0.5} to {@code 0} would report success having moved nothing. The parse
     * itself goes through {@link Diamonds#parse}, so exponents, signs, hex, and over-long
     * numbers are refused by the same grammar the ledger uses rather than by a second one
     * written here.
     */
    static Resolution amount(String raw, Outcome onSuccess) {
        Diamonds parsed;
        try {
            parsed = Diamonds.parse(raw);
        } catch (LedgerException e) {
            return error(Outcome.BAD_AMOUNT, messageFor(e.reason(), null));
        }
        if (parsed.dust() % Diamonds.DUST_PER_DIAMOND != 0L) {
            return error(Outcome.BAD_AMOUNT,
                    "Whole diamonds only -- there is no such item as half a diamond.");
        }
        long diamonds = parsed.dust() / Diamonds.DUST_PER_DIAMOND;
        if (diamonds <= 0L) {
            return error(Outcome.BAD_AMOUNT, "Amount must be at least 1.");
        }
        return new Resolution(onSuccess, diamonds, null);
    }

    /**
     * The one sentence a player is shown for each way the ledger can refuse.
     *
     * @param reason why the ledger refused; never {@code null}
     * @param held   the balance the account actually holds, used only by
     *               {@link LedgerException.Reason#INSUFFICIENT_FUNDS}; {@code null} when it could
     *               not be read, which falls back to a message that names no number rather than
     *               naming a wrong one
     * @return the message; plain text, no colour codes -- the caller owns presentation
     */
    public static String messageFor(LedgerException.Reason reason, Diamonds held) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case INSUFFICIENT_FUNDS -> held == null
                    ? "You do not have that many diamonds."
                    : "You only have " + held.format() + " diamonds.";
            case MALFORMED_AMOUNT -> "That is not a valid amount.";
            case AMOUNT_TOO_LARGE -> "That amount is too large.";
            case NEGATIVE_AMOUNT -> "Amount must be positive.";
            // Deliberately definite where UNCERTAIN_MESSAGE is deliberately not: the ledger only
            // reports this reason when it knows the write never started, so telling the player
            // to go and check their balance would send them looking for a problem that is not
            // there. "Nothing changed" is the whole difference between the two sentences.
            case NOTHING_WRITTEN -> "The market could not be reached, so nothing was changed. "
                    + "Try again in a moment.";
        };
    }

    /**
     * How many more diamonds an inventory can accept.
     *
     * <p>An empty slot takes a full stack; a slot already holding diamonds takes the rest of
     * that stack. Nothing else contributes, which is why this takes counts rather than an
     * inventory: it is arithmetic, and arithmetic reachable only through a live {@code Player}
     * is arithmetic no test can check.
     *
     * <p>Returned as a {@code long} so a caller comparing it against a requested amount cannot be
     * fooled by an {@code int} overflow, even though no real inventory comes close.
     *
     * @param emptySlots            how many storage slots hold nothing at all
     * @param diamondStackSizes     the size of each storage slot already holding plain diamonds;
     *                              may be empty, must not be {@code null}
     * @param maxStackSize          the most diamonds one slot can hold, normally 64
     * @return the number of diamonds that would fit; never negative
     * @throws IllegalArgumentException if {@code maxStackSize} is not positive or
     *                                   {@code emptySlots} is negative
     */
    public static long diamondCapacity(int emptySlots, int[] diamondStackSizes, int maxStackSize) {
        Objects.requireNonNull(diamondStackSizes, "diamondStackSizes");
        if (maxStackSize <= 0) {
            throw new IllegalArgumentException("maxStackSize must be positive, got " + maxStackSize);
        }
        if (emptySlots < 0) {
            throw new IllegalArgumentException("emptySlots must not be negative, got " + emptySlots);
        }
        long capacity = (long) emptySlots * maxStackSize;
        for (int size : diamondStackSizes) {
            // An over-full stack (possible with a server that raised the limit and then lowered
            // it again) has no room rather than negative room.
            capacity += Math.max(0L, (long) maxStackSize - size);
        }
        return capacity;
    }

    /**
     * The subcommand tokens a sender may actually run, in declaration order.
     *
     * <p>Tab completion must not advertise a subcommand the sender cannot use, and must not
     * advertise a player-only one to the console.
     */
    public static List<String> allowedSubcommandTokens(boolean senderIsPlayer,
                                                       Predicate<String> hasPermission) {
        Objects.requireNonNull(hasPermission, "hasPermission");
        List<String> tokens = new ArrayList<>();
        for (Sub sub : Sub.values()) {
            if (sub.needsPlayer() && !senderIsPlayer) {
                continue;
            }
            if (hasPermission.test(sub.permission())) {
                tokens.add(sub.token());
            }
        }
        return List.copyOf(tokens);
    }

    /**
     * Completions for a partially typed argument array.
     *
     * <p>Only the first argument completes. The second is a quantity, and there is no useful
     * fixed list of numbers to offer -- suggesting {@code 64} to a player who holds three
     * diamonds is worse than suggesting nothing.
     *
     * @param args           the arguments so far; the last element is the partial token
     * @param senderIsPlayer whether the sender has an inventory
     * @param hasPermission  the sender's permission check
     * @return matching completions, never {@code null}
     */
    public static List<String> complete(String[] args, boolean senderIsPlayer,
                                        Predicate<String> hasPermission) {
        Objects.requireNonNull(hasPermission, "hasPermission");
        if (args == null || args.length != 1) {
            return List.of();
        }
        String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String token : allowedSubcommandTokens(senderIsPlayer, hasPermission)) {
            if (token.startsWith(prefix)) {
                matches.add(token);
            }
        }
        return List.copyOf(matches);
    }

    private static Resolution tooMany(Sub sub) {
        return error(Outcome.TOO_MANY_ARGUMENTS,
                "Too many arguments for '" + sub.token() + "'. " + usage());
    }

    private static Resolution error(Outcome outcome, String message) {
        return new Resolution(outcome, 0L, message);
    }
}
