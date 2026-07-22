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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.xpfarm.farmersmarket.ledger.Ledger;
import org.xpfarm.farmersmarket.ledger.LedgerException;
import org.xpfarm.farmersmarket.storage.AccountDao;
import org.xpfarm.farmersmarket.storage.Database;
import org.xpfarm.farmersmarket.storage.DatabaseExecutor;
import org.xpfarm.farmersmarket.storage.Migrations;

/**
 * The parts of {@link MarketCommand} that do not need a running server: the two pure static
 * seams, and how the command is wired at construction.
 *
 * <p>Everything else in that class -- dispatch, the scheduler hop, the inventory reads and
 * writes, and the compensation branches -- genuinely requires a live Paper server and is listed
 * for the runtime pass instead. This module has no mocking framework by design; where a Bukkit
 * type is unavoidable it is a JDK dynamic proxy answering exactly the calls under test.
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
     * A ledger operation that settles after the plugin is disabled loses its handler: no items
     * are handed over, no message reaches the player, no compensation runs. The
     * <em>successful</em> case is the one that costs money -- Paper clears {@code isEnabled}
     * before {@code onDisable}, and {@code onDisable} flushes the executor, so a withdrawal
     * submitted a moment before a stop can commit with nobody left to deliver it.
     *
     * <p>These pin that the line is produced with a null cause at all (a success), and that it
     * carries everything an admin needs to reconcile by hand from the log alone.
     */
    @Test
    void aCommittedOperationLosingItsHandlerAtShutdownStillProducesAReconciliationLine() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

        String line = MarketCommand.shutdownReconciliationLine(
                player, "withdrawing 64 diamonds", null);

        assertTrue(line.contains(player.toString()), "must name the player: " + line);
        assertTrue(line.contains("withdrawing 64 diamonds"),
                "must name the operation and the amount: " + line);
        assertTrue(line.contains("APPLIED"),
                "a null cause means the ledger committed, and the line must say so: " + line);
    }

    @Test
    void aFailedOperationAtShutdownSaysTheOutcomeIsUnknownRatherThanApplied() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

        String line = MarketCommand.shutdownReconciliationLine(player, "depositing 8 diamonds",
                new IllegalStateException("connection closed"));

        assertTrue(line.contains(player.toString()), "must name the player: " + line);
        assertTrue(line.contains("depositing 8 diamonds"),
                "must name the operation and the amount: " + line);
        assertFalse(line.contains("APPLIED"),
                "a failure must not be reported as applied: " + line);
    }

    /**
     * Every console line this class writes must go through the <em>plugin's</em> logger.
     *
     * <p>Paper prefixes a plugin logger's output with {@code [FarmersMarket]}; a plain
     * {@code Logger.getLogger(MarketCommand.class.getName())} produces lines attributed to
     * nothing an operator recognises. Every line this class writes is written because money may
     * have moved, so attribution is not cosmetic -- during an incident it is the difference
     * between a grep that finds the record and one that does not.
     *
     * <p>Driving this needs a {@code Plugin}, and this module has no mocking framework by design,
     * so the plugin is a JDK dynamic proxy answering exactly one method. The ledger has to be
     * real -- it is {@code final} -- which is why there is a database here at all; nothing in the
     * test touches it.
     */
    @Test
    void theCommandLogsThroughThePluginsOwnLoggerSoConsoleLinesCarryItsPrefix(@TempDir Path dir)
            throws Exception {
        Logger pluginLogger = Logger.getLogger("FarmersMarketTest-" + UUID.randomUUID());
        Plugin plugin = pluginLoggingTo(pluginLogger);

        try (Database database = Database.open(dir.resolve("market.db"), dir.resolve("tmp").toString(), 5000);
                DatabaseExecutor executor = new DatabaseExecutor()) {
            Migrations.applyTo(database.connection());
            Ledger ledger = new Ledger(database, new AccountDao(database), executor);

            MarketCommand command = new MarketCommand(plugin, ledger, List::of);

            Field field = MarketCommand.class.getDeclaredField("log");
            field.setAccessible(true);
            assertSame(pluginLogger, field.get(command),
                    "the command must log through plugin.getLogger(), which is the only logger "
                            + "Paper prefixes with [FarmersMarket]");
        }

        for (Field field : MarketCommand.class.getDeclaredFields()) {
            assertFalse(Modifier.isStatic(field.getModifiers()) && field.getType() == Logger.class,
                    "a static logger cannot be the plugin's, so it cannot carry the prefix: "
                            + field.getName());
        }
    }

    /** A {@link Plugin} that answers {@code getLogger()} and nothing else. */
    private static Plugin pluginLoggingTo(Logger logger) {
        return (Plugin) Proxy.newProxyInstance(
                MarketCommandTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    if ("getLogger".equals(method.getName())) {
                        return logger;
                    }
                    throw new UnsupportedOperationException(
                            "the constructor must not call " + method.getName());
                });
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
        // Every compiled class in the package, listed rather than named. Naming them missed
        // MarketResolver$Sub, which carries string constants of its own, and would go on
        // missing every class M2-M5 add to this package -- silently, since an unscanned class
        // cannot fail a scan.
        List<Path> classes = compiledCommandClasses();
        assertTrue(classes.size() >= 2,
                "found only " + classes + " to scan; this test needs compiled classes");

        for (Path file : classes) {
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

    /** Every {@code *.class} the command package compiles to, including nested classes. */
    private static List<Path> compiledCommandClasses() throws IOException {
        Path dir = Path.of("target", "classes", "org", "xpfarm", "farmersmarket", "command");
        assertTrue(Files.isDirectory(dir),
                "cannot find " + dir + " to scan; this test needs compiled classes");
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList();
        }
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
