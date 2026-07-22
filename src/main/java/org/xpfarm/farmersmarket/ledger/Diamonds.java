/*
 * FarmersMarket - the integer money type: parsing, formatting, and overflow-refusing arithmetic.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.ledger;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money, held as an exact {@code long} count of dust.
 *
 * <p>One diamond is {@value #DUST_PER_DIAMOND} dust. Three decimal places of headroom is what
 * lets a later milestone take a 7% cut of a small price without rounding drift accumulating
 * into the ledger. <b>No {@code double}, {@code float}, or {@code BigDecimal} appears anywhere
 * in this class, including as an intermediate</b> -- binary floating point cannot represent
 * {@code 0.1} exactly, and money that cannot be represented exactly is money that eventually
 * disagrees with itself.
 *
 * <p>Two rules make this type safe to hand user input to:
 *
 * <ul>
 *   <li><b>{@link #parse} validates by regex before parsing anything numerically.</b> Handing
 *       player text straight to a numeric parser is what let {@code 1e9} mint money in
 *       EssentialsX-family economies: the parser accepts exponential notation, so a "price" of
 *       {@code 1e9} is a billion. {@link #AMOUNT} admits digits, one optional dot, and at most
 *       three decimals -- nothing else. Exponents, hex, signs, {@code Infinity}, and
 *       {@code NaN} never reach a parser at all.
 *   <li><b>Arithmetic refuses to overflow.</b> Every operation goes through {@code Math.*Exact}
 *       and converts the resulting {@link ArithmeticException} into a {@link LedgerException}
 *       with {@link LedgerException.Reason#AMOUNT_TOO_LARGE}. Silent two's-complement wrapping
 *       would turn a large balance negative, and a negative balance is money invented from
 *       nothing.
 * </ul>
 *
 * <p>Instances are immutable and may go negative: {@link #minus} is allowed to produce a
 * negative result so a caller can compute the outcome first and then decide, via
 * {@link #isNegative}, whether it is affordable. {@link Ledger} never writes a negative value
 * to the database, and the {@code accounts} table's own {@code CHECK} constraint refuses one
 * even if it tried.
 */
public final class Diamonds implements Comparable<Diamonds> {

    /** Dust in one diamond. Three decimal places of headroom for later fee arithmetic. */
    public static final long DUST_PER_DIAMOND = 1_000L;

    /** Nothing. Handy as an accumulator seed and as the balance of an account nobody has touched. */
    public static final Diamonds ZERO = new Diamonds(0L);

    /**
     * The only shape of text {@link #parse} accepts: digits, optionally followed by a dot and
     * one to three more digits. Deliberately anchored and deliberately narrow -- see the class
     * javadoc for why an exponent must never reach a numeric parser.
     */
    private static final Pattern AMOUNT = Pattern.compile("^\\d+(\\.\\d{1,3})?$");

    /** Decimal places {@link #AMOUNT} allows, and the width fractional digits are padded to. */
    private static final int DUST_DIGITS = 3;

    private final long dust;

    private Diamonds(long dust) {
        this.dust = dust;
    }

    /**
     * An amount of exactly {@code dust} dust.
     *
     * <p>Accepts negative values: this is the raw constructor, and callers such as
     * {@link #minus} need to be able to express a shortfall. Operations that require a
     * non-negative amount check for one themselves.
     *
     * @param dust the amount, in dust
     * @return the amount; never {@code null}
     */
    public static Diamonds ofDust(long dust) {
        return new Diamonds(dust);
    }

    /**
     * An amount of exactly {@code diamonds} whole diamonds.
     *
     * @param diamonds the amount, in whole diamonds
     * @return the amount; never {@code null}
     * @throws LedgerException with {@link LedgerException.Reason#AMOUNT_TOO_LARGE} if that many
     *                          diamonds is more dust than a {@code long} can hold
     */
    public static Diamonds ofDiamonds(long diamonds) {
        try {
            return new Diamonds(Math.multiplyExact(diamonds, DUST_PER_DIAMOND));
        } catch (ArithmeticException e) {
            throw tooLarge(diamonds + " diamonds is more dust than a long can hold", e);
        }
    }

    /**
     * Parses player-supplied text such as {@code "64"}, {@code "1.5"}, or {@code "0.001"}.
     *
     * <p>Validation is by {@link #AMOUNT} first and numeric parsing second, never the other way
     * round. Rejected outright, every one of them by the regex rather than by a parser
     * happening to fail: exponential notation ({@code 1e9}, {@code 1E9}), hex ({@code 0x10}),
     * signs ({@code -1}, {@code +1}), {@code Infinity}, {@code NaN}, grouping separators,
     * a bare or trailing dot, more than three decimals, and blank text.
     *
     * <p>Surrounding whitespace is trimmed, because a chat argument that arrived with a stray
     * space is a typing artefact rather than a different number. Nothing else about the text is
     * normalised.
     *
     * @param raw the text to parse; may be {@code null}, which is rejected like any other
     *            malformed input rather than thrown as a {@link NullPointerException}, since a
     *            missing command argument is user error and not a programming error
     * @return the parsed amount; never negative, since the grammar has no place for a sign
     * @throws LedgerException with {@link LedgerException.Reason#MALFORMED_AMOUNT} if the text
     *                          is not a plain decimal amount, or
     *                          {@link LedgerException.Reason#AMOUNT_TOO_LARGE} if it is
     *                          well-formed but too big to hold in dust
     */
    public static Diamonds parse(String raw) {
        if (raw == null) {
            throw malformed("no amount given");
        }
        String text = raw.trim();
        if (!AMOUNT.matcher(text).matches()) {
            throw malformed("'" + text + "' is not a plain amount like 64 or 1.5");
        }

        int dot = text.indexOf('.');
        String wholeText = dot < 0 ? text : text.substring(0, dot);
        String fractionText = dot < 0 ? "" : text.substring(dot + 1);

        long whole;
        try {
            whole = Long.parseLong(wholeText);
        } catch (NumberFormatException e) {
            // The regex already proved these are all digits, so the only way this fails is length.
            throw tooLarge("'" + text + "' is larger than this ledger can hold", e);
        }

        // Fractional digits are positional, not numeric: ".5" is 500 dust and ".05" is 50, so the
        // text is right-padded to the full dust width before it is read as a number.
        long fraction = 0L;
        if (!fractionText.isEmpty()) {
            StringBuilder padded = new StringBuilder(fractionText);
            while (padded.length() < DUST_DIGITS) {
                padded.append('0');
            }
            fraction = Long.parseLong(padded.toString());
        }

        try {
            return new Diamonds(Math.addExact(Math.multiplyExact(whole, DUST_PER_DIAMOND), fraction));
        } catch (ArithmeticException e) {
            throw tooLarge("'" + text + "' is larger than this ledger can hold", e);
        }
    }

    /**
     * This amount, in dust.
     *
     * @return the exact dust count; may be negative for a computed shortfall
     */
    public long dust() {
        return dust;
    }

    /**
     * This amount plus {@code other}.
     *
     * @param other the amount to add
     * @return the sum; never {@code null}
     * @throws LedgerException with {@link LedgerException.Reason#AMOUNT_TOO_LARGE} if the sum
     *                          would not fit in a {@code long}, rather than wrapping into a
     *                          negative balance
     */
    public Diamonds plus(Diamonds other) {
        Objects.requireNonNull(other, "other");
        try {
            return new Diamonds(Math.addExact(dust, other.dust));
        } catch (ArithmeticException e) {
            throw tooLarge(format() + " + " + other.format() + " does not fit in this ledger", e);
        }
    }

    /**
     * This amount minus {@code other}.
     *
     * <p>May return a negative amount; that is the point. Callers decide what a shortfall means
     * by testing {@link #isNegative} on the result, rather than this method guessing.
     *
     * @param other the amount to subtract
     * @return the difference, possibly negative; never {@code null}
     * @throws LedgerException with {@link LedgerException.Reason#AMOUNT_TOO_LARGE} if the
     *                          difference would not fit in a {@code long}
     */
    public Diamonds minus(Diamonds other) {
        Objects.requireNonNull(other, "other");
        try {
            return new Diamonds(Math.subtractExact(dust, other.dust));
        } catch (ArithmeticException e) {
            throw tooLarge(format() + " - " + other.format() + " does not fit in this ledger", e);
        }
    }

    /**
     * Whether this amount is below zero.
     *
     * @return {@code true} only for a strictly negative amount; zero is not negative
     */
    public boolean isNegative() {
        return dust < 0L;
    }

    @Override
    public int compareTo(Diamonds other) {
        return Long.compare(dust, other.dust);
    }

    /**
     * This amount as the shortest exact decimal string: {@code "1"}, {@code "1.5"},
     * {@code "0.001"}, {@code "0"}.
     *
     * <p>Trailing zeros in the fraction are dropped and a whole amount carries no dot at all, so
     * the common case reads like a number a player would say out loud. The result always
     * round-trips through {@link #parse} for a non-negative amount.
     *
     * @return the formatted amount; plain text with no colour codes or units, because the
     *         command layer owns presentation
     */
    public String format() {
        // Long.MIN_VALUE has no positive counterpart, so the sign is split off first and the
        // magnitudes are taken after the division, where both quotient and remainder fit.
        String sign = dust < 0L ? "-" : "";
        long whole = Math.abs(dust / DUST_PER_DIAMOND);
        long fraction = Math.abs(dust % DUST_PER_DIAMOND);
        if (fraction == 0L) {
            return sign + whole;
        }

        StringBuilder digits = new StringBuilder(Long.toString(fraction));
        while (digits.length() < DUST_DIGITS) {
            digits.insert(0, '0');
        }
        while (digits.charAt(digits.length() - 1) == '0') {
            digits.setLength(digits.length() - 1);
        }
        return sign + whole + "." + digits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Diamonds diamonds && diamonds.dust == dust;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(dust);
    }

    @Override
    public String toString() {
        return format() + " diamonds (" + dust + " dust)";
    }

    private static LedgerException malformed(String detail) {
        return new LedgerException(LedgerException.Reason.MALFORMED_AMOUNT, detail);
    }

    private static LedgerException tooLarge(String detail, Throwable cause) {
        return new LedgerException(LedgerException.Reason.AMOUNT_TOO_LARGE, detail, cause);
    }
}
