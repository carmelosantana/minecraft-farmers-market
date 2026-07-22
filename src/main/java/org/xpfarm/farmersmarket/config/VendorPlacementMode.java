/*
 * FarmersMarket - where a player is permitted to place a vendor.
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
 * Where a player is permitted to place a vendor, per {@code vendor.placement-mode} in
 * {@code config.yml}. Parses case-insensitively from its kebab-case config string via
 * {@link #parse}.
 */
public enum VendorPlacementMode {

    /** Only inside land the player owns and inside their district. */
    OWNED_AND_DISTRICT,
    /** Only inside a district, regardless of ownership. */
    DISTRICT_ONLY,
    /** No placement restriction beyond the usual world protections. */
    ANYWHERE;

    /**
     * Resolves the kebab-case {@code config.yml} spelling of this enum (for example
     * {@code "owned-and-district"}) to its constant, case-insensitively. Empty when
     * {@code value} is {@code null} or matches none of the constants.
     */
    public static Optional<VendorPlacementMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (VendorPlacementMode candidate : values()) {
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
