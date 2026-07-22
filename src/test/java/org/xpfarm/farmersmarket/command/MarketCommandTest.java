/*
 * FarmersMarket - tests for the parts of the /market executor reachable without a server.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import org.xpfarm.farmersmarket.ledger.LedgerException;

/**
 * The two things in {@link MarketCommand} that do not need a running server.
 *
 * <p>Everything else in that class -- dispatch, the scheduler hop, the inventory reads and
 * writes, and the compensation branches -- genuinely requires a live Paper server and is listed
 * for the runtime pass instead. This module has no mocking framework by design.
 */
final class MarketCommandTest {

    /**
     * {@link MarketCommand#unwrap} decides whether a failure is recognised as a
     * {@link LedgerException}, and therefore whether the player gets a one-line refusal or an
     * alarming go-and-find-an-admin message. Nothing currently completes a ledger future with a
     * wrapper -- {@code DatabaseExecutor} calls {@code completeExceptionally} directly -- so
     * these pin defence rather than live behaviour. The first chained stage anyone adds would
     * start wrapping silently, and the symptom would be the wrong message, not a crash.
     */
    @Test
    void aBareThrowablePassesStraightThrough() {
        LedgerException refused = new LedgerException(
                LedgerException.Reason.INSUFFICIENT_FUNDS, "three diamonds short");

        assertSame(refused, MarketCommand.unwrap(refused));
    }

    @Test
    void aCompletionExceptionWrapperIsPeeledOff() {
        LedgerException refused = new LedgerException(
                LedgerException.Reason.INSUFFICIENT_FUNDS, "three diamonds short");

        assertSame(refused, MarketCommand.unwrap(new CompletionException(refused)));
    }

    @Test
    void anExecutionExceptionWrapperIsPeeledOff() {
        LedgerException refused = new LedgerException(
                LedgerException.Reason.MALFORMED_AMOUNT, "not a number");

        assertSame(refused, MarketCommand.unwrap(new ExecutionException(refused)));
    }

    @Test
    void nestedWrappersArePeeledAllTheWayDown() {
        LedgerException refused = new LedgerException(
                LedgerException.Reason.AMOUNT_TOO_LARGE, "too big");

        assertSame(refused, MarketCommand.unwrap(
                new CompletionException(new ExecutionException(new CompletionException(refused)))));
    }

    @Test
    void aWrapperWithNoCauseIsReturnedRatherThanNullOrAnException() {
        // CompletionException permits a null cause. Looping on getCause() without this guard
        // would either spin or dereference null, on the one path that only runs when something
        // has already gone wrong.
        CompletionException causeless = new CompletionException((Throwable) null);

        assertSame(causeless, MarketCommand.unwrap(causeless));
    }

    @Test
    void aNullFailureStaysNull() {
        // Every success path calls this with null; it must not become an exception.
        assertNull(MarketCommand.unwrap(null));
    }

    @Test
    void aNonWrapperCauseIsNotPeeled() {
        // Only the two wrapper types are unwrapped. Peeling a cause off an arbitrary exception
        // would discard the context of the failure that actually happened.
        IllegalStateException inner = new IllegalStateException("inner");
        IllegalStateException outer = new IllegalStateException("outer", inner);

        assertSame(outer, MarketCommand.unwrap(outer));
    }

    /**
     * The block-ramp characters {@code U+2581}-{@code U+2587} are absent from Bedrock's glyph
     * sheet and render as <em>blank space</em> rather than as a visible error, so a message built
     * from them arrives with its meaning silently missing. A raw section sign would smuggle in
     * legacy formatting, which is where strikethrough and underline -- both stripped by Geyser --
     * would have to come from.
     *
     * <p>{@code MarketResolverTest} guards {@link MarketResolver}'s output behaviourally. This
     * guards every string constant in both command classes, including the ones built inside
     * {@link MarketCommand}'s methods on failure branches that no test can reach without a
     * server.
     *
     * <p><b>This scans the compiled class files, not the source, and the difference is the whole
     * point.</b> Java decodes {@code \\uXXXX} in the lexer, before a string literal is even a
     * token, so {@code "full \\u2586 make room"} is a source file containing no glyph at all and
     * a compiled string containing one. A source scan reports that as clean. The constant pool
     * holds what the player will actually receive, in modified UTF-8, whose encoding of every
     * character below {@code U+FFFF} that matters here is byte-identical to standard UTF-8 --
     * so searching the class bytes for the encoded forms catches the escape, the pasted
     * character, and anything else that ends up in a constant.
     */
    @Test
    void noStringConstantInTheCommandPackageUsesAGlyphGeyserCannotRender() throws IOException {
        List<Path> classes = List.of(
                compiled("MarketCommand.class"), compiled("MarketResolver.class"));

        for (Path file : classes) {
            assertTrue(Files.exists(file),
                    "cannot find " + file + " to scan; this test needs compiled classes");
            byte[] bytecode = Files.readAllBytes(file);

            for (char forbidden = '▁'; forbidden <= '▇'; forbidden++) {
                assertFalse(contains(bytecode, utf8(forbidden)),
                        "block-ramp glyph U+" + Integer.toHexString(forbidden) + " is compiled "
                                + "into " + file.getFileName() + "; it renders as blank space on "
                                + "Bedrock, so the message would silently lose its meaning");
            }
            assertFalse(contains(bytecode, utf8('§')),
                    "a raw legacy formatting code is compiled into " + file.getFileName()
                            + "; colour belongs in NamedTextColor, and this is how strikethrough "
                            + "and underline would get in");
        }
    }

    /**
     * Proves the scanner above can actually see a glyph, rather than passing because it looks in
     * the wrong place. Without this, deleting the whole search would still leave a green test.
     */
    @Test
    void theGlyphScannerDetectsAGlyphWhenThereIsOne() {
        byte[] haystack = "harmless ▅ text".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(contains(haystack, utf8('▅')), "the scanner cannot find a glyph that is there");
        assertFalse(contains(haystack, utf8('▁')), "the scanner reports a glyph that is not there");
    }

    private static Path compiled(String name) {
        return Path.of("target", "classes", "org", "xpfarm", "farmersmarket", "command", name);
    }

    private static byte[] utf8(char c) {
        return String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
