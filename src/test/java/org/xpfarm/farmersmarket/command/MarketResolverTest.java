/*
 * FarmersMarket - tests for every decision /market makes without a running server.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import org.xpfarm.farmersmarket.command.MarketResolver.Outcome;
import org.xpfarm.farmersmarket.command.MarketResolver.Resolution;
import org.xpfarm.farmersmarket.ledger.Diamonds;
import org.xpfarm.farmersmarket.ledger.LedgerException;
import org.xpfarm.farmersmarket.market.MarketException;

/**
 * Everything {@code /market} decides before it touches a server.
 *
 * <p>{@link MarketCommand} itself is not tested here and cannot be: dispatch, the scheduler hop,
 * and the inventory reads and writes all need a live Paper server, and this module deliberately
 * has no mocking framework. What is testable is the part that decides -- argument parsing,
 * permission gating, the console case, the ledger-reason-to-sentence mapping, and the arithmetic
 * of how many diamonds fit in an inventory -- and all of it lives in {@link MarketResolver}
 * precisely so that it can be pinned here.
 */
final class MarketResolverTest {

    /** A sender holding exactly the listed nodes and nothing else. */
    private static Predicate<String> holding(String... nodes) {
        Set<String> granted = Set.of(nodes);
        return granted::contains;
    }

    private static final Predicate<String> ORDINARY_PLAYER =
            holding(MarketResolver.USE_PERMISSION);
    private static final Predicate<String> ADMIN =
            holding(MarketResolver.USE_PERMISSION, MarketResolver.RELOAD_PERMISSION);
    private static final Predicate<String> NOBODY = holding();

    private static final boolean PLAYER = true;
    private static final boolean CONSOLE = false;

    /** A use-permission player running {@code /market <args>}. */
    private static Resolution resolvePlayer(String... args) {
        return MarketResolver.resolve(args, PLAYER, ORDINARY_PLAYER);
    }

    /** The console running {@code /market <args>} with the same use permission granted. */
    private static Resolution resolveConsole(String... args) {
        return MarketResolver.resolve(args, CONSOLE, ORDINARY_PLAYER);
    }

    @Nested
    final class Dispatch {

        @Test
        void bareMarketMeansBalance() {
            assertEquals(Outcome.BALANCE,
                    MarketResolver.resolve(new String[0], PLAYER, ORDINARY_PLAYER).outcome());
        }

        @Test
        void nullArgumentsMeanBalanceRatherThanAnException() {
            assertEquals(Outcome.BALANCE,
                    MarketResolver.resolve(null, PLAYER, ORDINARY_PLAYER).outcome());
        }

        @Test
        void subcommandsAreCaseInsensitive() {
            assertEquals(Outcome.BALANCE,
                    MarketResolver.resolve(new String[] {"BaLaNcE"}, PLAYER, ORDINARY_PLAYER).outcome());
        }

        @Test
        void anUnknownSubcommandIsNamedBackToTheSender() {
            Resolution resolved =
                    MarketResolver.resolve(new String[] {"brwose"}, PLAYER, ORDINARY_PLAYER);

            assertEquals(Outcome.UNKNOWN_SUBCOMMAND, resolved.outcome());
            assertTrue(resolved.message().contains("brwose"),
                    "the message must quote what was typed: " + resolved.message());
        }

        @Test
        void surplusArgumentsAreRefusedRatherThanIgnored() {
            // Quietly dropping a token hides typos in exactly the commands run least often.
            assertEquals(Outcome.TOO_MANY_ARGUMENTS,
                    MarketResolver.resolve(new String[] {"balance", "now"}, PLAYER, ADMIN).outcome());
            assertEquals(Outcome.TOO_MANY_ARGUMENTS,
                    MarketResolver.resolve(new String[] {"reload", "now"}, CONSOLE, ADMIN).outcome());
            assertEquals(Outcome.TOO_MANY_ARGUMENTS,
                    MarketResolver.resolve(new String[] {"deposit", "1", "2"}, PLAYER, ADMIN).outcome());
            assertEquals(Outcome.TOO_MANY_ARGUMENTS,
                    MarketResolver.resolve(new String[] {"withdraw", "1", "2"}, PLAYER, ADMIN).outcome());
        }
    }

    @Nested
    final class Permissions {

        @Test
        void aBareMarketStillChecksThePermission() {
            // Guards requirement: a bare /market must run the same gate /market balance does.
            // Short-circuiting the empty-argument case to BALANCE is the obvious "simplification"
            // that opens the command up to everyone.
            assertEquals(Outcome.NO_PERMISSION,
                    MarketResolver.resolve(new String[0], PLAYER, NOBODY).outcome());
        }

        @Test
        void balanceDepositAndWithdrawAllRequireTheUseNode() {
            for (String token : new String[] {"balance", "deposit", "withdraw"}) {
                assertEquals(Outcome.NO_PERMISSION,
                        MarketResolver.resolve(new String[] {token}, PLAYER, NOBODY).outcome(),
                        token + " must require " + MarketResolver.USE_PERMISSION);
            }
        }

        @Test
        void reloadRequiresItsOwnAdminNodeAndNotTheUseNode() {
            // Guards requirement: an ordinary player holding farmersmarket.use must not be able
            // to reload the configuration.
            assertEquals(Outcome.NO_PERMISSION,
                    MarketResolver.resolve(new String[] {"reload"}, PLAYER, ORDINARY_PLAYER).outcome());
            assertEquals(Outcome.RELOAD,
                    MarketResolver.resolve(new String[] {"reload"}, PLAYER, ADMIN).outcome());
        }

        @Test
        void theNodesResolvedAgainstAreTheOnesPluginYmlDeclares() {
            // PluginDescriptorTest asserts plugin.yml declares exactly these; this end pins the
            // code side, so renaming one without the other fails rather than silently ungating.
            assertEquals("farmersmarket.use", MarketResolver.USE_PERMISSION);
            assertEquals("farmersmarket.admin.reload", MarketResolver.RELOAD_PERMISSION);
            for (MarketResolver.Sub sub : MarketResolver.Sub.values()) {
                assertTrue(Set.of(MarketResolver.USE_PERMISSION, MarketResolver.RELOAD_PERMISSION)
                        .contains(sub.permission()),
                        sub + " checks an undeclared node: " + sub.permission());
            }
        }
    }

    @Nested
    final class Console {

        @Test
        void consoleCannotCheckABalanceDepositOrWithdraw() {
            // Guards requirement 6: these must answer with a message, never a ClassCastException
            // from casting the console sender to a Player.
            for (String token : new String[] {"balance", "deposit", "withdraw"}) {
                Resolution resolved =
                        MarketResolver.resolve(new String[] {token}, CONSOLE, ADMIN);
                assertEquals(Outcome.CONSOLE_NEEDS_PLAYER, resolved.outcome(),
                        token + " needs a player and must say so");
                assertTrue(resolved.outcome().isError(),
                        "an outcome the command must not dispatch on must be an error");
            }
        }

        @Test
        void bareMarketFromConsoleIsRefusedRatherThanCast() {
            assertEquals(Outcome.CONSOLE_NEEDS_PLAYER,
                    MarketResolver.resolve(new String[0], CONSOLE, ADMIN).outcome());
        }

        @Test
        void consoleCanReload() {
            // Guards requirement 6: gate 7a exercises reload over RCON, which is the console.
            Resolution resolved = MarketResolver.resolve(new String[] {"reload"}, CONSOLE, ADMIN);

            assertEquals(Outcome.RELOAD, resolved.outcome());
            assertFalse(resolved.outcome().isError());
        }
    }

    @Nested
    final class Amounts {

        @Test
        void depositWithoutAnAmountMeansEverything() {
            Resolution resolved =
                    MarketResolver.resolve(new String[] {"deposit"}, PLAYER, ORDINARY_PLAYER);

            assertEquals(Outcome.DEPOSIT_ALL, resolved.outcome());
            assertEquals(0L, resolved.diamonds());
        }

        @Test
        void withdrawWithoutAnAmountIsRefusedRatherThanTakingEverything() {
            // Deposit has an obvious whole-inventory default. Withdraw does not, and guessing at
            // one empties an account on a typo.
            Resolution resolved =
                    MarketResolver.resolve(new String[] {"withdraw"}, PLAYER, ORDINARY_PLAYER);

            assertEquals(Outcome.MISSING_AMOUNT, resolved.outcome());
            assertTrue(resolved.outcome().isError());
        }

        @Test
        void aWholeAmountCarriesThroughAsACountOfDiamonds() {
            assertEquals(64L,
                    MarketResolver.resolve(new String[] {"deposit", "64"}, PLAYER, ORDINARY_PLAYER)
                            .diamonds());
            assertEquals(64L,
                    MarketResolver.resolve(new String[] {"withdraw", "64"}, PLAYER, ORDINARY_PLAYER)
                            .diamonds());
        }

        @Test
        void aFractionalAmountIsRefusedRatherThanFloored() {
            // Guards requirement: flooring 0.5 to 0 would report success having moved nothing,
            // and flooring 1.5 to 1 would quietly move less than the player asked for.
            for (String typed : new String[] {"0.5", "1.5", "64.001"}) {
                Resolution resolved =
                        MarketResolver.resolve(new String[] {"withdraw", typed}, PLAYER, ORDINARY_PLAYER);

                assertEquals(Outcome.BAD_AMOUNT, resolved.outcome(), typed + " is not whole");
                assertEquals(0L, resolved.diamonds(),
                        typed + " must not carry a floored count through");
            }
        }

        @Test
        void zeroIsRefused() {
            Resolution resolved =
                    MarketResolver.resolve(new String[] {"deposit", "0"}, PLAYER, ORDINARY_PLAYER);

            assertEquals(Outcome.BAD_AMOUNT, resolved.outcome());
            assertTrue(resolved.message().contains("at least 1"), resolved.message());
        }

        @Test
        void exponentialNotationNeverReachesANumericParser() {
            // The EssentialsX-family money-minting bug: a parser that accepts 1e9 turns a
            // one-diamond "price" into a billion. Diamonds.parse refuses it by grammar, and this
            // pins that /market inherits that refusal rather than parsing amounts itself.
            for (String typed : new String[] {"1e9", "0x10", "-1", "+1", "Infinity", "NaN", "1,000", ""}) {
                Resolution resolved =
                        MarketResolver.resolve(new String[] {"withdraw", typed}, PLAYER, ORDINARY_PLAYER);

                assertEquals(Outcome.BAD_AMOUNT, resolved.outcome(), typed + " must be refused");
                assertEquals("That is not a valid amount.", resolved.message(), typed);
            }
        }

        @Test
        void anAmountTooBigToHoldIsRefusedAsTooLargeRatherThanAsNonsense() {
            Resolution resolved = MarketResolver.resolve(
                    new String[] {"withdraw", "99999999999999999999"}, PLAYER, ORDINARY_PLAYER);

            assertEquals(Outcome.BAD_AMOUNT, resolved.outcome());
            assertEquals("That amount is too large.", resolved.message());
        }
    }

    @Nested
    final class Messages {

        @Test
        void everyLedgerReasonMapsToItsOwnSentence() {
            assertEquals("That is not a valid amount.",
                    MarketResolver.messageFor(LedgerException.Reason.MALFORMED_AMOUNT, null));
            assertEquals("That amount is too large.",
                    MarketResolver.messageFor(LedgerException.Reason.AMOUNT_TOO_LARGE, null));
            assertEquals("Amount must be positive.",
                    MarketResolver.messageFor(LedgerException.Reason.NEGATIVE_AMOUNT, null));
            assertEquals("You only have 3 diamonds.",
                    MarketResolver.messageFor(LedgerException.Reason.INSUFFICIENT_FUNDS,
                            Diamonds.ofDiamonds(3)));
        }

        @Test
        void insufficientFundsNamesNoNumberWhenTheBalanceCouldNotBeRead() {
            // Naming a wrong number is worse than naming none: a player told "you only have 0"
            // when the read failed will believe their diamonds are gone.
            String message =
                    MarketResolver.messageFor(LedgerException.Reason.INSUFFICIENT_FUNDS, null);

            assertEquals("You do not have that many diamonds.", message);
        }

        @Test
        void nothingWrittenTellsThePlayerNothingChangedRatherThanThatItIsUncertain() {
            // The ledger sets this reason only when it knows the write never started, so the
            // player is owed a definite answer. UNCERTAIN_MESSAGE says the opposite -- go and
            // check your balance -- and sending someone looking for a problem that is not there
            // is how a plugin teaches its players to distrust it.
            String message = MarketResolver.messageFor(LedgerException.Reason.NOTHING_WRITTEN, null);

            assertEquals("The market could not be reached, so nothing was changed. "
                    + "Try again in a moment.", message);
            assertNotEquals(MarketResolver.UNCERTAIN_MESSAGE, message,
                    "a definite refusal must not read like an unknown outcome");
        }

        @Test
        void everyReasonIsCoveredAndDistinct() {
            // A new Reason added upstream must not silently fall into some other reason's text.
            Set<String> distinct = new java.util.HashSet<>();
            for (LedgerException.Reason reason : LedgerException.Reason.values()) {
                String message = MarketResolver.messageFor(reason, null);
                assertNotNull(message, reason + " has no message");
                assertTrue(distinct.add(message), reason + " reuses another reason's message");
            }
        }

        @Test
        void everyPlayerFacingStringAvoidsTheGlyphsGeyserCannotRender() {
            // The block-ramp characters U+2581..U+2587 are absent from Bedrock's glyph sheet and
            // render as blank space rather than as a visible error, so a message built from them
            // arrives empty with nothing to notice. Section signs would smuggle in raw legacy
            // formatting, which is where strikethrough and underline would come from.
            List<String> everything = new java.util.ArrayList<>(List.of(
                    MarketResolver.usage(), MarketResolver.UNCERTAIN_MESSAGE));
            for (LedgerException.Reason reason : LedgerException.Reason.values()) {
                everything.add(MarketResolver.messageFor(reason, Diamonds.ofDiamonds(1)));
            }
            for (String typed : new String[] {"balance x", "withdraw", "withdraw 0.5", "nope"}) {
                Resolution resolved =
                        MarketResolver.resolve(typed.split(" "), PLAYER, ADMIN);
                if (resolved.message() != null) {
                    everything.add(resolved.message());
                }
            }

            for (String message : everything) {
                for (char c = '▁'; c <= '▇'; c++) {
                    assertFalse(message.indexOf(c) >= 0,
                            "block-ramp glyph U+" + Integer.toHexString(c) + " in: " + message);
                }
                assertFalse(message.indexOf('§') >= 0,
                        "raw legacy formatting code in: " + message);
            }
        }
    }

    @Nested
    final class InventorySpace {

        private static final int STACK = 64;

        @Test
        void anEmptyInventoryTakesAFullStackPerSlot() {
            assertEquals(36L * STACK,
                    MarketResolver.diamondCapacity(36, new int[0], STACK));
        }

        @Test
        void aFullInventoryTakesNothing() {
            // Guards requirement 2: this is the case withdraw must refuse without debiting.
            assertEquals(0L, MarketResolver.diamondCapacity(0, new int[0], STACK));
        }

        @Test
        void aPartialDiamondStackTakesTheRestOfItsStack() {
            assertEquals(STACK - 20L, MarketResolver.diamondCapacity(0, new int[] {20}, STACK));
        }

        @Test
        void emptySlotsAndPartialStacksAddUp() {
            assertEquals(2L * STACK + (STACK - 60L) + (STACK - 1L),
                    MarketResolver.diamondCapacity(2, new int[] {60, 1}, STACK));
        }

        @Test
        void aFullDiamondStackAddsNoRoom() {
            assertEquals(0L, MarketResolver.diamondCapacity(0, new int[] {STACK, STACK}, STACK));
        }

        @Test
        void anOverFullStackHasNoRoomRatherThanNegativeRoom() {
            // A server that raised the stack limit and lowered it again leaves stacks above the
            // limit. Subtracting without a floor would make one such stack cancel out the room
            // in a genuinely empty slot, and the player would be told less fits than does.
            assertEquals((long) STACK,
                    MarketResolver.diamondCapacity(1, new int[] {STACK + 40}, STACK));
        }

        @Test
        void capacityRefusesNonsenseRatherThanReturningIt() {
            assertThrows(IllegalArgumentException.class,
                    () -> MarketResolver.diamondCapacity(1, new int[0], 0));
            assertThrows(IllegalArgumentException.class,
                    () -> MarketResolver.diamondCapacity(-1, new int[0], STACK));
        }
    }

    @Nested
    final class TabCompletion {

        @Test
        void onlySubcommandsTheSenderMayRunAreOffered() {
            assertEquals(
                    List.of("balance", "deposit", "withdraw", "sell", "browse", "info", "buy",
                            "cancel", "mine", "claim", "pot"),
                    MarketResolver.complete(new String[] {""}, PLAYER, ORDINARY_PLAYER));
            assertEquals(
                    List.of("balance", "deposit", "withdraw", "sell", "browse", "info", "buy",
                            "cancel", "mine", "claim", "pot", "reload"),
                    MarketResolver.complete(new String[] {""}, PLAYER, ADMIN));
            assertEquals(List.of(),
                    MarketResolver.complete(new String[] {""}, PLAYER, NOBODY));
        }

        @Test
        void theConsoleIsOfferedOnlyWhatItCanActuallyRun() {
            // Only the read-only views and reload run without an inventory to act on.
            assertEquals(List.of("browse", "info", "pot", "reload"),
                    MarketResolver.complete(new String[] {""}, CONSOLE, ADMIN));
        }

        @Test
        void completionsAreFilteredByWhatHasBeenTyped() {
            assertEquals(List.of("withdraw"),
                    MarketResolver.complete(new String[] {"with"}, PLAYER, ADMIN));
            assertEquals(List.of(),
                    MarketResolver.complete(new String[] {"zzz"}, PLAYER, ADMIN));
        }

        @Test
        void quantitiesAreNotCompleted() {
            // Suggesting "64" to a player holding three diamonds is worse than suggesting nothing.
            assertEquals(List.of(),
                    MarketResolver.complete(new String[] {"withdraw", ""}, PLAYER, ADMIN));
            assertEquals(List.of(),
                    MarketResolver.complete(new String[0], PLAYER, ADMIN));
        }
    }

    @Nested
    final class Market {

        @Test
        void sellParsesADiamondPrice() {
            MarketResolver.Resolution r = resolvePlayer("sell", "100");
            assertEquals(MarketResolver.Outcome.SELL, r.outcome());
            assertEquals(100_000L, r.priceDust());
        }

        @Test
        void sellRejectsAZeroOrMissingPrice() {
            assertEquals(MarketResolver.Outcome.BAD_AMOUNT, resolvePlayer("sell", "0").outcome());
            assertEquals(MarketResolver.Outcome.MISSING_AMOUNT, resolvePlayer("sell").outcome());
        }

        @Test
        void buyAndInfoAndCancelParseAPositiveId() {
            assertEquals(7L, resolvePlayer("buy", "7").listingId());
            assertEquals(7L, resolvePlayer("info", "7").listingId());
            assertEquals(7L, resolvePlayer("cancel", "7").listingId());
            assertEquals(MarketResolver.Outcome.BAD_ID, resolvePlayer("buy", "0").outcome());
            assertEquals(MarketResolver.Outcome.BAD_ID, resolvePlayer("buy", "-3").outcome());
            assertEquals(MarketResolver.Outcome.BAD_ID, resolvePlayer("buy", "notanumber").outcome());
            assertEquals(MarketResolver.Outcome.MISSING_ID, resolvePlayer("buy").outcome());
        }

        @Test
        void browseDefaultsToPageOneAndParsesAPage() {
            assertEquals(1, resolvePlayer("browse").page());
            assertEquals(3, resolvePlayer("browse", "3").page());
            assertEquals(MarketResolver.Outcome.BAD_PAGE, resolvePlayer("browse", "0").outcome());
        }

        @Test
        void infoAndBrowseAndPotAreAllowedFromTheConsole() {
            // Read-only market views do not need an inventory; only sell/buy/cancel/claim do.
            assertNotEquals(MarketResolver.Outcome.CONSOLE_NEEDS_PLAYER, resolveConsole("browse").outcome());
            assertNotEquals(MarketResolver.Outcome.CONSOLE_NEEDS_PLAYER, resolveConsole("pot").outcome());
            assertEquals(MarketResolver.Outcome.CONSOLE_NEEDS_PLAYER, resolveConsole("sell", "10").outcome());
            assertEquals(MarketResolver.Outcome.CONSOLE_NEEDS_PLAYER, resolveConsole("buy", "1").outcome());
        }

        @Test
        void eachMarketRefusalHasItsOwnSentence() {
            for (MarketException.Reason reason : MarketException.Reason.values()) {
                assertNotNull(MarketResolver.messageFor(reason));
                assertFalse(MarketResolver.messageFor(reason).isBlank());
            }
            assertTrue(MarketResolver.messageFor(MarketException.Reason.NOT_A_COMMODITY)
                    .toLowerCase(Locale.ROOT).contains("commodity"));
        }
    }
}
