/*
 * FarmersMarket - vanilla experience level and points conversion, in integer arithmetic.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.ledger;

/**
 * Converts between a player's experience level and their total experience points, using
 * vanilla's piecewise formulas exactly.
 *
 * <p>This exists because {@code Player#getTotalExperience()} is not a reliable reading of a
 * player's actual points across implementations and is not the inverse of
 * {@code setTotalExperience}. The dependable inputs are the level and the {@code getExp()}
 * progress bar, so points are computed from those.
 *
 * <p><b>The two formulas break at different levels, and that is not a typo.</b> Cumulative
 * totals change formula between 16 and 17 and again between 31 and 32; the cost of advancing
 * one level changes between 15 and 16 and again between 30 and 31. The offset is inherent --
 * the cost table is the first difference of the totals table, and a first difference shifts the
 * breakpoint down by one. Aligning them "for consistency" is the classic experience-math bug,
 * which is why {@link #costOfNextLevel} is pinned by its own tests rather than derived from
 * {@link #totalPoints}.
 *
 * <p>Everything here is integer arithmetic. Vanilla's fractional coefficients are multiplied
 * through by two and the halving is done last, where it divides exactly for every integer level
 * in range. The one floating-point value in the class is the {@code progress} parameter itself,
 * which necessarily arrives as a {@code float} because that is what Bukkit hands over; it is
 * rounded to an {@code int} immediately and never touches a cumulative total. No money is
 * involved here -- these are experience points, not {@link Diamonds}.
 */
public final class ExperienceMath {

    private ExperienceMath() {
    }

    /**
     * Total experience points held by a player at {@code level} with {@code progress} of the way
     * through the bar toward the next one.
     *
     * <p>The progress fraction is applied to {@link #costOfNextLevel} for the level currently
     * being worked through -- not to the level below or above -- and rounded to the nearest
     * whole point.
     *
     * @param level    the player's experience level; must not be negative
     * @param progress the fraction of the way to the next level, as {@code Player#getExp()}
     *                 reports it: {@code 0.0} through {@code 1.0} inclusive
     * @return the total points; exact, never an estimate
     * @throws IllegalArgumentException if {@code level} is negative, or {@code progress} is
     *                                   outside {@code [0, 1]}, {@code NaN}, or infinite
     * @throws ArithmeticException      if the total does not fit in an {@code int}, which no
     *                                   reachable player level can cause; refused rather than
     *                                   silently wrapped, both for a level whose cumulative total
     *                                   alone overflows and for the boundary level where only the
     *                                   added progress tips it over
     */
    public static int totalPoints(int level, float progress) {
        requireLevel(level);
        if (Float.isNaN(progress) || progress < 0f || progress > 1f) {
            throw new IllegalArgumentException("progress must be between 0 and 1, got " + progress);
        }
        int base = Math.toIntExact(cumulativePoints(level));
        // addExact, not +, and the two guards catch different levels: toIntExact above catches a
        // level whose own cumulative total does not fit, while this catches the last level that
        // does fit but overflows once a full bar of progress is added to it.
        return Math.addExact(base, Math.round(progress * costOfNextLevel(level)));
    }

    /**
     * The highest level a player holding {@code points} total experience has reached.
     *
     * <p>Floors: points part-way through a level report the level below, which is exactly what
     * a player sees on their own experience bar.
     *
     * @param points total experience points; must not be negative
     * @return the level, such that {@code totalPoints(level, 0f) <= points} and
     *         {@code totalPoints(level + 1, 0f) > points}
     * @throws IllegalArgumentException if {@code points} is negative
     */
    public static int levelForTotal(int points) {
        if (points < 0) {
            throw new IllegalArgumentException("points must not be negative, got " + points);
        }

        // Bracket by doubling, then bisect. Both halves are integer-only, and the bracket keeps
        // the search away from any assumption about how high a level can go -- inverting the
        // quadratic directly would need a square root, and a square root means floating point.
        int high = 1;
        while (cumulativePoints(high) <= points) {
            high *= 2;
        }
        int low = high / 2;
        while (low < high - 1) {
            int middle = low + (high - low) / 2;
            if (cumulativePoints(middle) <= points) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Points needed to advance from {@code level} to {@code level + 1}.
     *
     * <p>Note the breakpoints: 0-15, 16-30, 31 and up. They sit one level below
     * {@link #totalPoints}' breakpoints, which is correct -- see the class javadoc.
     *
     * @param level the level being worked through; must not be negative
     * @return the cost of the next level, in points
     * @throws IllegalArgumentException if {@code level} is negative
     */
    public static int costOfNextLevel(int level) {
        requireLevel(level);
        if (level <= 15) {
            return 2 * level + 7;
        }
        if (level <= 30) {
            return 5 * level - 38;
        }
        return 9 * level - 158;
    }

    /**
     * Cumulative points to reach {@code level} from zero, in {@code long} so an absurd level
     * overflows at the {@link Math#toIntExact} boundary in {@link #totalPoints} rather than
     * wrapping silently mid-calculation.
     *
     * <p>Vanilla states the upper two pieces with fractional coefficients
     * ({@code 2.5·L² − 40.5·L + 360} and {@code 4.5·L² − 162.5·L + 2220}). They are written here
     * doubled, with the halving last; both numerators are even for every integer level in their
     * range, so the division is exact and no floating point is needed to express them.
     */
    private static long cumulativePoints(int level) {
        long l = level;
        if (level <= 16) {
            return l * l + 6L * l;
        }
        if (level <= 31) {
            return (5L * l * l - 81L * l + 720L) / 2L;
        }
        return (9L * l * l - 325L * l + 4440L) / 2L;
    }

    private static void requireLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("level must not be negative, got " + level);
        }
    }
}
