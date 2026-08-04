/*
 * FarmersMarket - the stable content-hash identity key a listed item is grouped under.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.market;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives the stable identity key the market groups an item under, from the exact serialized
 * bytes of the stack.
 *
 * <p>The key is a SHA-256 content hash: the same bytes always hash to the same key, different
 * bytes to a different key. A {@code "u:"} prefix marks the key as a <em>unique</em> item's key so
 * a later commodity key path (Part 2) cannot collide with it. The hash is pinned by a test because
 * these keys are stored in a live database; changing the algorithm would silently stop stored keys
 * matching newly computed ones, so any such change must be a deliberate, visible one.
 */
public final class ItemKey {

    private ItemKey() {
    }

    /**
     * The unique-item key for {@code itemBytes}: {@code "u:"} followed by the SHA-256 hex digest.
     *
     * @param itemBytes the serialized item stack
     * @return the prefixed content-hash key
     */
    public static String forUnique(byte[] itemBytes) {
        return "u:" + sha256Hex(itemBytes);
    }

    /**
     * The commodity-item key for {@code itemBytes}: {@code "c:"} followed by the SHA-256 hex digest.
     *
     * @param itemBytes the serialized item stack
     * @return the prefixed content-hash key
     */
    public static String forCommodity(byte[] itemBytes) {
        return "c:" + sha256Hex(itemBytes);
    }

    /**
     * The lower-case hex SHA-256 digest of {@code bytes}.
     *
     * @param bytes the input to hash
     * @return a 64-character lower-case hex string
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; its absence is not a runtime outcome to handle.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
