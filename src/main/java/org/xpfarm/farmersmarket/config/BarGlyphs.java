/*
 * FarmersMarket - which block-glyph ramp the market bar charts render with.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.config;

import java.util.Locale;
import java.util.Optional;

/**
 * Glyph ramp used to render in-chat bar charts.
 *
 * <p>{@link #DENSITY_4} uses the block characters confirmed present in Bedrock's
 * CP437-derived glyph sheet. {@link #RAMP_8} uses {@code U+2581}-{@code U+2587}, which are
 * almost certainly absent there -- see {@code ui.bar-glyphs} in {@code config.yml} for the
 * full rationale. Both values parse case-insensitively from their kebab-case config string
 * via {@link #parse}.
 */
public enum BarGlyphs {

    DENSITY_4,
    RAMP_8;

    /**
     * Resolves the kebab-case {@code config.yml} spelling of this enum (for example
     * {@code "density-4"}) to its constant, case-insensitively. Empty when {@code value} is
     * {@code null} or matches none of the constants.
     */
    public static Optional<BarGlyphs> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BarGlyphs candidate : values()) {
            if (candidate.kebabName().equals(normalized)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private String kebabName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
