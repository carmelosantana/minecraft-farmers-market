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
import org.xpfarm.farmersmarket.market.MarketException;

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

    /** Every subcommand {@code /market} understands. */
    public enum Sub {

        /** {@code /market balance}, and the meaning of a bare {@code /market}. */
        BALANCE("balance", USE_PERMISSION, true),

        /** {@code /market deposit [qty]}. */
        DEPOSIT("deposit", USE_PERMISSION, true),

        /** {@code /market withdraw <qty>}. */
        WITHDRAW("withdraw", USE_PERMISSION, true),

        /** {@code /market sell <price>}, listing the held item; needs an inventory to take it from. */
        SELL("sell", USE_PERMISSION, true),

        /** {@code /market browse [page]}, a read-only view the console may run. */
        BROWSE("browse", USE_PERMISSION, false),

        /** {@code /market info <id>}, a read-only view the console may run. */
        INFO("info", USE_PERMISSION, false),

        /** {@code /market buy <id>}, which moves money and so needs a player. */
        BUY("buy", USE_PERMISSION, true),

        /** {@code /market cancel <id>}, which returns an item and so needs a player. */
        CANCEL("cancel", USE_PERMISSION, true),

        /** {@code /market mine}, listing the sender's own active listings; needs a player. */
        MINE("mine", USE_PERMISSION, true),

        /** {@code /market claim}, collecting proceeds and returns into an inventory. */
        CLAIM("claim", USE_PERMISSION, true),

        /** {@code /market pot}, a read-only view of the community pot the console may run. */
        POT("pot", USE_PERMISSION, false),

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

        /** {@code withdraw} or {@code sell} with no amount, which has no sensible default. */
        MISSING_AMOUNT,

        /** An amount that is not a positive, representable number of diamonds. */
        BAD_AMOUNT,

        /** A subcommand that needs a listing id was given none. */
        MISSING_ID,

        /** A listing id that is not a positive whole number. */
        BAD_ID,

        /** A browse page that is not a positive whole number. */
        BAD_PAGE,

        /** Show the sender's balance and experience. */
        BALANCE,

        /** Deposit every diamond in the sender's inventory. */
        DEPOSIT_ALL,

        /** Deposit {@link Resolution#diamonds()} diamonds. */
        DEPOSIT_AMOUNT,

        /** Withdraw {@link Resolution#diamonds()} diamonds. */
        WITHDRAW_AMOUNT,

        /** List the held item at {@link Resolution#priceDust()} dust. */
        SELL,

        /** Show {@link Resolution#page()} of the listings on sale. */
        BROWSE,

        /** Show the listing with {@link Resolution#listingId()}. */
        INFO,

        /** Buy the listing with {@link Resolution#listingId()}. */
        BUY,

        /** Cancel the listing with {@link Resolution#listingId()}. */
        CANCEL,

        /** Show the sender's own active listings. */
        MINE,

        /** Collect the sender's proceeds and returns. */
        CLAIM,

        /** Show the community pot. */
        POT,

        /** Re-read {@code config.yml}. */
        RELOAD;

        /** Whether this outcome is a message to report rather than an action to run. */
        public boolean isError() {
            return this == UNKNOWN_SUBCOMMAND || this == TOO_MANY_ARGUMENTS
                    || this == NO_PERMISSION || this == CONSOLE_NEEDS_PLAYER
                    || this == MISSING_AMOUNT || this == BAD_AMOUNT
                    || this == MISSING_ID || this == BAD_ID || this == BAD_PAGE;
        }
    }

    /**
     * A resolved invocation.
     *
     * <p>Only one of the numeric carriers is meaningful for any given outcome, and which one is
     * fixed by the outcome: a resolution never has both a listing id and a page. The unused
     * carriers are zero rather than an {@code Optional} each, because the caller already switches
     * on {@link #outcome()} and reads only the field that outcome documents.
     *
     * @param outcome   what to do, or why not
     * @param diamonds  the whole number of diamonds involved, for {@link Outcome#DEPOSIT_AMOUNT}
     *                  and {@link Outcome#WITHDRAW_AMOUNT}; {@code 0} otherwise
     * @param priceDust the sell price in dust, for {@link Outcome#SELL}; {@code 0} otherwise. A
     *                  sell price may be fractional, so this is carried as dust rather than as a
     *                  whole-diamond count
     * @param listingId the listing id, for {@link Outcome#INFO}, {@link Outcome#BUY}, and
     *                  {@link Outcome#CANCEL}; {@code 0} otherwise
     * @param page      the one-based page, for {@link Outcome#BROWSE}; {@code 0} otherwise
     * @param message   the message to show, for every {@linkplain Outcome#isError() error}
     *                  outcome and {@code null} otherwise
     */
    public record Resolution(Outcome outcome, long diamonds, long priceDust, long listingId,
                             int page, String message) {

        /**
         * A resolution that carries only a diamond count or a message, for the balance, deposit,
         * withdraw, reload, and every-error path that predates the market subcommands.
         */
        public Resolution(Outcome outcome, long diamonds, String message) {
            this(outcome, diamonds, 0L, 0L, 0, message);
        }
    }

    /** The usage line, naming the subcommands that exist. */
    public static String usage() {
        return "Usage: /market [balance | deposit | withdraw | sell | browse | info | buy "
                + "| cancel | mine | claim | pot | reload]";
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
            case SELL -> resolveSell(tokens);
            case BROWSE -> resolveBrowse(tokens);
            case INFO -> resolveId(tokens, Sub.INFO, Outcome.INFO);
            case BUY -> resolveId(tokens, Sub.BUY, Outcome.BUY);
            case CANCEL -> resolveId(tokens, Sub.CANCEL, Outcome.CANCEL);
            case MINE -> tokens.length > 1
                    ? tooMany(sub)
                    : new Resolution(Outcome.MINE, 0L, null);
            case CLAIM -> tokens.length > 1
                    ? tooMany(sub)
                    : new Resolution(Outcome.CLAIM, 0L, null);
            case POT -> tokens.length > 1
                    ? tooMany(sub)
                    : new Resolution(Outcome.POT, 0L, null);
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

    private static Resolution resolveSell(String[] tokens) {
        if (tokens.length > 2) {
            return tooMany(Sub.SELL);
        }
        if (tokens.length == 1) {
            return error(Outcome.MISSING_AMOUNT, "For how much? Try /market sell 10.");
        }
        return price(tokens[1]);
    }

    private static Resolution resolveBrowse(String[] tokens) {
        if (tokens.length > 2) {
            return tooMany(Sub.BROWSE);
        }
        if (tokens.length == 1) {
            return new Resolution(Outcome.BROWSE, 0L, 0L, 0L, 1, null);
        }
        return page(tokens[1]);
    }

    private static Resolution resolveId(String[] tokens, Sub sub, Outcome onSuccess) {
        if (tokens.length > 2) {
            return tooMany(sub);
        }
        if (tokens.length == 1) {
            return error(Outcome.MISSING_ID,
                    "Which listing? Its number is shown by /market browse.");
        }
        return listingId(tokens[1], onSuccess);
    }

    /**
     * Parses a typed sell price as an amount of diamonds.
     *
     * <p>Unlike {@link #amount}, a price may be fractional: a price is a number the market records,
     * not a count of physical items to move, so {@code 1.5} is a real price of a diamond and a
     * half. The parse still goes through {@link Diamonds#parse}, so exponents, signs, hex, and
     * over-long numbers are refused by the same grammar the ledger uses. A price of zero moves an
     * item for nothing, which is a giveaway dressed as a sale, so it is refused rather than
     * accepted.
     */
    static Resolution price(String raw) {
        Diamonds parsed;
        try {
            parsed = Diamonds.parse(raw);
        } catch (LedgerException e) {
            return error(Outcome.BAD_AMOUNT, messageFor(e.reason(), null));
        }
        if (parsed.dust() <= 0L) {
            return error(Outcome.BAD_AMOUNT, "A price must be more than zero.");
        }
        return new Resolution(Outcome.SELL, 0L, parsed.dust(), 0L, 0, null);
    }

    /**
     * Parses a typed listing id as a positive {@code long}.
     *
     * <p>An id names a row the market assigns, so it is a whole number and it is positive -- id
     * {@code 0} is no listing and a negative id is nonsense. The number is parsed strictly: a
     * value too large for a {@code long} is refused rather than wrapped, exactly as an amount is.
     */
    static Resolution listingId(String raw, Outcome onSuccess) {
        long id;
        try {
            id = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return error(Outcome.BAD_ID, "A listing number is a whole number, like 7.");
        }
        if (id <= 0L) {
            return error(Outcome.BAD_ID, "A listing number is a whole number, like 7.");
        }
        return new Resolution(onSuccess, 0L, 0L, id, 0, null);
    }

    /**
     * Parses a typed browse page as a positive {@code int}.
     *
     * <p>Pages are one-based, so page {@code 0} does not exist and is refused rather than treated
     * as the first page -- a player who typed it meant something, and silently showing page one
     * hides the typo.
     */
    static Resolution page(String raw) {
        int page;
        try {
            page = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return error(Outcome.BAD_PAGE, "A page is a whole number, like 1.");
        }
        if (page <= 0) {
            return error(Outcome.BAD_PAGE, "A page is a whole number, like 1.");
        }
        return new Resolution(Outcome.BROWSE, 0L, 0L, 0L, page, null);
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
     * The one sentence a player is shown for each way a market operation can refuse.
     *
     * <p>The mapping lives here for the same reason its ledger counterpart does: a
     * {@link MarketException} carries a {@link MarketException.Reason}, not text, because the
     * market package has no idea what locale or chat platform it is writing to. Turning a reason
     * into a sentence is a decision, and every decision {@code /market} makes is a pure function
     * in this class so a test can pin it.
     *
     * @param reason why the market refused; never {@code null}
     * @return the message; plain text, no colour codes -- the caller owns presentation
     */
    public static String messageFor(MarketException.Reason reason) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case LISTING_UNAVAILABLE -> "That listing is no longer available.";
            case SELF_PURCHASE -> "You cannot buy your own listing.";
            case INSUFFICIENT_FUNDS ->
                    "You do not have enough diamonds. Deposit some with /market deposit.";
            case NOT_YOUR_LISTING -> "That is not your listing.";
            case TOO_MANY_LISTINGS -> "You have too many listings up. Cancel or sell one first.";
            case COMMODITY_NOT_YET -> "Only unique items -- enchanted, renamed, damaged, or "
                    + "custom -- can be listed right now. Bulk trading is coming soon.";
            case NOT_A_COMMODITY -> "That is not a tradable commodity.";
            // Same wording as the ledger's NOTHING_WRITTEN, and for the same reason: the market
            // sets it only when it knows the write never started, so the player is owed a definite
            // "nothing changed" rather than the go-and-check uncertainty message.
            case NOTHING_WRITTEN -> "The market could not be reached, so nothing was changed. "
                    + "Try again in a moment.";
            case AMOUNT_TOO_LARGE -> "That amount is too large.";
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
