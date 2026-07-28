/*
 * FarmersMarket - the /market command tree: balance, deposit, withdraw, sell, browse, info, buy,
 *                 cancel, mine, claim, pot, and reload.
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import org.xpfarm.farmersmarket.config.FmConfig;
import org.xpfarm.farmersmarket.ledger.Diamonds;
import org.xpfarm.farmersmarket.ledger.ExperienceMath;
import org.xpfarm.farmersmarket.ledger.Ledger;
import org.xpfarm.farmersmarket.ledger.LedgerException;
import org.xpfarm.farmersmarket.market.BukkitItemCodec;
import org.xpfarm.farmersmarket.market.ItemClass;
import org.xpfarm.farmersmarket.market.ListedItem;
import org.xpfarm.farmersmarket.market.ListingRow;
import org.xpfarm.farmersmarket.market.MarketException;
import org.xpfarm.farmersmarket.market.MarketMath;
import org.xpfarm.farmersmarket.market.MarketService;
import org.xpfarm.farmersmarket.market.PendingItemRow;

/**
 * {@code /market [balance | deposit | withdraw | sell | browse | info | buy | cancel | mine |
 * claim | pot | reload]}, with tab completion.
 *
 * <p>Every decision this command makes lives in {@link MarketResolver} as a pure function. What
 * is left here is dispatch, the thread hop, and the three things that genuinely need a running
 * server: reading an inventory, writing to one, and reading an experience bar.
 *
 * <h2>Threading</h2>
 *
 * <p>Every {@link Ledger} call returns a {@link CompletableFuture} completed from the database
 * writer thread. Nothing in a completion here touches a {@code Player}, an inventory, or any
 * other Bukkit object directly: every one of them goes back through
 * {@link org.bukkit.scheduler.BukkitScheduler#runTask} first. Bukkit's inventory API is not
 * thread-safe and touching it from the writer thread is the kind of bug that corrupts an
 * inventory once a week and never reproduces.
 *
 * <p>A player can disconnect while a future is in flight, so every completion re-resolves the
 * {@link Player} from the {@link UUID} rather than capturing the object. Where an item must
 * still be handed over to a player who has since left, it is dropped at the location the command
 * was run from -- see {@link #giveOrDrop}.
 *
 * <h2>Never take without giving, never give without taking</h2>
 *
 * <p>{@code deposit} removes the items first and credits second, returning the items if the
 * credit is <em>refused</em>. {@code withdraw} debits first and hands over items second,
 * re-crediting anything that would not fit. Both leave the player exactly as they started on
 * every refusal.
 *
 * <p>The one path that does not compensate is a failure whose cause is not a
 * {@link LedgerException}: that outcome is unknown rather than failed, because a commit followed
 * by a failed connection cleanup moves the money and still throws. Compensating there would
 * either dupe the items or charge the player twice, so the command says plainly that it could
 * not confirm the result, logs the whole stack trace, and never retries.
 *
 * <p>That unknown set is as narrow as the ledger can prove and no narrower, and it is the same
 * width for every operation. A storage failure that happened before the ledger attempted any
 * write arrives as {@link LedgerException.Reason#NOTHING_WRITTEN} and is compensated like any
 * other refusal -- the depositing player gets their items back, and the withdrawing player is
 * told plainly that nothing changed rather than being sent to check a balance that provably did
 * not move. A storage failure that might have committed is still unknown in both, and the items
 * are still held.
 *
 * <h2>Bedrock safety</h2>
 *
 * <p>All output is plain chat text with legacy-named colours. No hover events, no click events
 * (a {@code copy_to_clipboard} click event breaks Bedrock chat outright), no hex or gradient
 * colours, no strikethrough or underline, and no block-ramp glyphs -- Geyser either strips or
 * silently blanks every one of those.
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    /** Listings shown per {@code /market browse} page. */
    private static final int BROWSE_PAGE_SIZE = 10;

    private final Plugin plugin;
    private final Logger log;
    private final Ledger ledger;
    private final MarketService market;
    private final Supplier<FmConfig> config;
    private final Supplier<List<String>> reloadAction;

    /**
     * @param plugin       the owning plugin: schedules work back onto the main thread, resolves
     *                     players by UUID, and supplies the logger every line here is written to
     * @param ledger       the one thing in the plugin that moves money for balance/deposit/withdraw
     * @param market       the market's list/buy/cancel/claim operations, all of which run on the
     *                     database writer thread and settle back through {@link #onMainThread}
     * @param config       the live configuration, read through a supplier so a {@code /market
     *                     reload} that swaps the snapshot is seen on the very next command
     * @param reloadAction re-reads {@code config.yml} and returns the validation warnings it
     *                     produced, empty when the file was clean
     */
    public MarketCommand(Plugin plugin, Ledger ledger, MarketService market,
            Supplier<FmConfig> config, Supplier<List<String>> reloadAction) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        // The plugin's own logger, not Logger.getLogger(MarketCommand.class.getName()): Paper
        // prefixes a plugin logger's output with [FarmersMarket], and every line this class
        // writes is written because money may be involved. An operator grepping the console
        // during an incident needs those lines attributed to this plugin, on the same line, with
        // no ambiguity about which of a dozen plugins produced them. The "FarmersMarket: "
        // message prefix stays as well, deliberately: it is what the incident notes tell the
        // operator to grep for, and a message that carries it survives being quoted out of the
        // console into a ticket.
        this.log = plugin.getLogger();
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.market = Objects.requireNonNull(market, "market");
        this.config = Objects.requireNonNull(config, "config");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        MarketResolver.Resolution resolved =
                MarketResolver.resolve(args, sender instanceof Player, sender::hasPermission);
        if (resolved.outcome().isError()) {
            error(sender, resolved.message());
            return true;
        }
        switch (resolved.outcome()) {
            case BALANCE -> balance((Player) sender);
            case DEPOSIT_ALL -> deposit((Player) sender, DEPOSIT_EVERYTHING);
            case DEPOSIT_AMOUNT -> deposit((Player) sender, resolved.diamonds());
            case WITHDRAW_AMOUNT -> withdraw((Player) sender, resolved.diamonds());
            case SELL -> sell((Player) sender, resolved.priceDust());
            case BROWSE -> browse(sender, resolved.page());
            case INFO -> info(sender, resolved.listingId());
            case BUY -> buy((Player) sender, resolved.listingId());
            case CANCEL -> cancel((Player) sender, resolved.listingId());
            case MINE -> mine((Player) sender);
            case CLAIM -> claim((Player) sender);
            case POT -> pot(sender);
            case RELOAD -> reload(sender);
            default -> error(sender, MarketResolver.usage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        return MarketResolver.complete(args, sender instanceof Player, sender::hasPermission);
    }

    // ---- balance ----------------------------------------------------------------------

    private void balance(Player player) {
        UUID id = player.getUniqueId();
        onMainThread(ledger.balance(id), id, "reading a balance", (held, failure) -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null) {
                return;
            }
            if (failure != null) {
                // A read moves nothing, so there is no outcome to be uncertain about and no
                // reconciliation to ask for -- and telling someone who just ran /market balance
                // to go and check /market balance is circular. A refusal still gets its own
                // sentence, per the same reason-to-message contract every other path follows.
                if (failure instanceof LedgerException refused) {
                    online.sendMessage(
                            text(MarketResolver.messageFor(refused.reason(), null), NamedTextColor.RED));
                } else {
                    online.sendMessage(text("Could not read your balance just now. "
                            + "Try again in a moment.", NamedTextColor.RED));
                    log.log(Level.WARNING, "FarmersMarket: could not read the balance of " + id
                            + ". Nothing was changed by this.", failure);
                }
                return;
            }
            online.sendMessage(text("Balance: " + held.format() + " diamonds", NamedTextColor.AQUA));
            online.sendMessage(text("XP: " + experiencePoints(online) + " points (level "
                    + online.getLevel() + ")", NamedTextColor.GREEN));
        });
    }

    /**
     * The player's total experience points.
     *
     * <p>Computed from the level and the progress bar rather than read from
     * {@code getTotalExperience()}, which is not a reliable reading of a player's actual points
     * and is not the inverse of {@code setTotalExperience}. The progress value is clamped before
     * it is used because {@code getExp()} is documented as {@code [0, 1)} but is a {@code float}
     * that a plugin or a rounding artefact can push marginally outside it, and
     * {@link ExperienceMath#totalPoints} refuses out-of-range input rather than guessing.
     */
    private int experiencePoints(Player player) {
        float progress = Math.max(0f, Math.min(1f, player.getExp()));
        try {
            return ExperienceMath.totalPoints(Math.max(0, player.getLevel()), progress);
        } catch (ArithmeticException | IllegalArgumentException e) {
            // Nothing a reachable level can cause. Showing a balance without an XP line beats
            // failing the whole command over the cosmetic half of it.
            log.log(Level.WARNING, "FarmersMarket: could not compute experience points for "
                    + player.getUniqueId(), e);
            return 0;
        }
    }

    // ---- deposit ----------------------------------------------------------------------

    /** Sentinel for {@code /market deposit} with no quantity: every diamond the player holds. */
    private static final long DEPOSIT_EVERYTHING = -1L;

    /**
     * Moves diamonds from the player's inventory into the ledger.
     *
     * <p>The items come out of the inventory <b>before</b> the credit is attempted, because the
     * alternative -- credit first, remove second -- mints diamonds for any player who logs out
     * in the window between the two. If the credit is refused, the exact number of items removed
     * goes straight back.
     */
    private void deposit(Player player, long requested) {
        UUID id = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        int held = countPlainDiamonds(inventory);
        if (held <= 0) {
            error(player, "You have no diamonds to deposit.");
            return;
        }
        long wanted = requested == DEPOSIT_EVERYTHING ? held : requested;
        if (wanted > held) {
            error(player, "You only have " + held + " diamonds on you.");
            return;
        }

        // Built before anything is removed: Diamonds.ofDiamonds refuses an amount too large to
        // represent, and refusing it after the items were taken would strand them.
        Diamonds amount;
        try {
            amount = Diamonds.ofDiamonds(wanted);
        } catch (LedgerException e) {
            error(player, MarketResolver.messageFor(e.reason(), null));
            return;
        }

        int moved = removePlainDiamonds(inventory, (int) wanted);
        if (moved <= 0) {
            error(player, "Could not take those diamonds out of your inventory. Nothing changed.");
            return;
        }
        // Credit what was actually taken, never what was asked for. The two differ only if the
        // inventory changed under us between the count and the removal, but crediting the
        // requested amount there would mint the difference.
        Diamonds credited = moved == wanted ? amount : Diamonds.ofDiamonds(moved);

        Location where = player.getLocation();
        onMainThread(ledger.deposit(id, credited), id, "depositing " + moved + " diamonds",
                (balance, failure) -> {
                    if (failure == null) {
                        message(id, text("Deposited " + moved + " diamonds. Balance: "
                                + balance.format(), NamedTextColor.GREEN));
                        return;
                    }
                    if (failure instanceof LedgerException refused) {
                        // A refusal is definite: nothing was written, so the items are safe to
                        // return. That now includes Reason.NOTHING_WRITTEN -- a storage failure
                        // the ledger can prove happened before it attempted any write, which
                        // used to arrive as a bare SQLException and cost the player their
                        // diamonds on the branch below. Widening this branch is only sound
                        // because the ledger sets that reason from the pre-write read and
                        // nowhere else.
                        giveOrDrop(id, where, moved);
                        message(id, text(MarketResolver.messageFor(refused.reason(), null)
                                + " Your " + moved + " diamonds were returned.", NamedTextColor.RED));
                        return;
                    }
                    // Unknown, not failed. Returning the items could dupe them; retrying could
                    // charge twice. Say so, log it, and leave it for a human.
                    reportUncertain(id, "depositing " + moved + " diamonds", failure,
                            "The diamonds have left your inventory and were NOT returned, "
                                    + "because putting them back could duplicate them.");
                });
    }

    // ---- withdraw ---------------------------------------------------------------------

    /**
     * Moves diamonds from the ledger back into the player's inventory.
     *
     * <p>The balance is debited <b>before</b> the items are handed over, because the alternative
     * -- items first, debit second -- mints diamonds for any player who logs out in the window
     * between the two. Inventory space is checked before the debit so a player with no room is
     * refused rather than debited, and re-checked after it, because the async window is long
     * enough for the player to have filled their inventory in the meantime. Anything that does
     * not fit is credited straight back.
     */
    private void withdraw(Player player, long wanted) {
        UUID id = player.getUniqueId();
        long capacity = capacityForDiamonds(player.getInventory());
        if (capacity <= 0L) {
            error(player, "Your inventory is full. Make some room and try again.");
            return;
        }
        if (wanted > capacity) {
            error(player, "Only " + capacity + " more diamonds fit in your inventory. "
                    + "Try /market withdraw " + capacity + ".");
            return;
        }

        Diamonds amount;
        try {
            amount = Diamonds.ofDiamonds(wanted);
        } catch (LedgerException e) {
            error(player, MarketResolver.messageFor(e.reason(), null));
            return;
        }

        int count = (int) wanted;
        Location where = player.getLocation();
        onMainThread(ledger.withdraw(id, amount), id, "withdrawing " + count + " diamonds",
                (balance, failure) -> {
                    if (failure == null) {
                        message(id, text("Withdrew " + count + " diamonds. Balance: "
                                + balance.format(), NamedTextColor.GREEN));
                        deliver(id, where, count);
                        return;
                    }
                    if (failure instanceof LedgerException refused) {
                        // A refusal means the debit never happened, so there is nothing to undo.
                        // The balance is only re-read to fill in the number the player wants to
                        // hear. That now includes Reason.NOTHING_WRITTEN on exactly the same
                        // terms as the deposit branch above: a failed pre-write read is a
                        // definite refusal in both operations, because it is answered in one
                        // place -- Ledger.readBeforeWriting -- for both of them.
                        explainRefusedWithdrawal(id, refused);
                        return;
                    }
                    reportUncertain(id, "withdrawing " + count + " diamonds", failure,
                            "No diamonds were handed over.");
                });
    }

    /**
     * Tells the player why a withdrawal was refused, naming their real balance when the reason
     * was insufficient funds.
     *
     * <p>The ledger reports the reason, never a number -- it formats nothing for players by
     * design -- so the number comes from a follow-up read. That read is safe to make: it writes
     * nothing, so a second failure costs only the specificity of the message.
     */
    private void explainRefusedWithdrawal(UUID id, LedgerException refused) {
        if (refused.reason() != LedgerException.Reason.INSUFFICIENT_FUNDS) {
            message(id, text(MarketResolver.messageFor(refused.reason(), null), NamedTextColor.RED));
            return;
        }
        onMainThread(ledger.balance(id), id, "reading a balance to explain a refused withdrawal",
                (held, readFailure) -> message(id, text(MarketResolver.messageFor(refused.reason(),
                        readFailure == null ? held : null), NamedTextColor.RED)));
    }

    /**
     * Hands over {@code count} withdrawn diamonds, crediting back anything that will not fit.
     *
     * <p>The player has already been debited by the time this runs, so a definite outcome has to
     * end with the value somewhere: in their inventory, back in their balance, or -- only when
     * the balance <em>definitely</em> refused it -- on the ground at their feet.
     *
     * <p><b>An unknown outcome is the exception, and it is the same policy the deposit path
     * applies.</b> If the re-credit commits and only the cleanup after it throws, the money is
     * already back in the balance; dropping the items as well would hand the player both, which
     * mints diamonds out of nothing. A dupe is silent, repeatable, and propagates through every
     * later trade with no marker on it, while an undelivered stack self-reports within minutes
     * and is reconcilable from the log line below. So this branch drops nothing, says plainly
     * that the diamonds were not handed over, and leaves it for a human.
     */
    private void deliver(UUID id, Location where, int count) {
        Player online = plugin.getServer().getPlayer(id);
        if (online == null) {
            dropAt(where, count);
            return;
        }
        int leftOver = addToInventory(online, count);
        if (leftOver <= 0) {
            return;
        }

        int returned = leftOver;
        onMainThread(ledger.deposit(id, Diamonds.ofDiamonds(returned)), id,
                "returning " + returned + " undelivered diamonds to the balance",
                (balance, failure) -> {
                    if (failure == null) {
                        message(id, text("Your inventory filled up, so " + returned
                                + " diamonds went back into the market.", NamedTextColor.YELLOW));
                        return;
                    }
                    if (failure instanceof LedgerException) {
                        // A refusal is definite: the credit was not written, so this side is
                        // unambiguously still holding the diamonds and handing them over
                        // physically cannot duplicate anything. It is not a retry of the credit.
                        Player again = plugin.getServer().getPlayer(id);
                        dropAt(again != null ? again.getLocation() : where, returned);
                        message(id, text("Your inventory filled up, so " + returned
                                + " diamonds were dropped at your feet.", NamedTextColor.YELLOW));
                        log.log(Level.WARNING, "FarmersMarket: " + id + "'s balance refused the "
                                + "return of " + returned + " undelivered diamonds, so they were "
                                + "dropped as items instead. The player still has them.", failure);
                        return;
                    }
                    reportUncertain(id, "returning " + returned
                                    + " undelivered diamonds to the balance", failure,
                            "Those " + returned + " diamonds were not handed over. They may or "
                                    + "may not be back in your balance.");
                });
    }

    // ---- sell -------------------------------------------------------------------------

    /**
     * Lists the item in the player's main hand for sale.
     *
     * <p><b>Never take without giving, never give without taking.</b> Nothing leaves the player
     * and no fee is charged until the escrow write is attempted: a commodity item, an unaffordable
     * fee, and an overflowing price all stop with the item still in hand and the XP untouched. The
     * item is removed from the hand <em>before</em> {@link MarketService#list} runs, because the
     * alternative -- list first, remove second -- lists an item the player could log out still
     * holding, and then the escrow row and the inventory both claim it. The XP fee is charged
     * <em>after</em> the escrow write succeeds, so a refused listing costs neither the item nor the
     * fee, and a successful one costs both.
     */
    private void sell(Player player, long priceDust) {
        UUID id = player.getUniqueId();
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType().isAir() || inHand.getAmount() <= 0) {
            error(player, "Hold the item you want to sell.");
            return;
        }

        ListedItem item = BukkitItemCodec.encode(inHand);
        if (item.itemClass() == ItemClass.COMMODITY) {
            // Nothing has been removed or charged.
            error(player, MarketResolver.messageFor(MarketException.Reason.COMMODITY_NOT_YET));
            return;
        }

        FmConfig cfg = config.get();
        Diamonds price = Diamonds.ofDust(priceDust);
        int fee;
        try {
            fee = MarketMath.listingFeeXp(price, cfg.listingFeePercent(), cfg.xpPerDiamond());
        } catch (ArithmeticException overflow) {
            // An absurd price whose fee will not fit in an int. Refused before anything moves.
            error(player, "That price is too large to list.");
            return;
        }
        int have = experiencePoints(player);
        if (have < fee) {
            error(player, "Listing costs " + fee + " XP and you have " + have + ".");
            return;
        }

        // Capture the exact stack that was encoded, then take it out of the hand. The clone is what
        // gets handed back on a refusal, so a refusal returns precisely what was listed.
        ItemStack removed = inHand.clone();
        player.getInventory().setItemInMainHand(null);
        Location where = player.getLocation();

        onMainThread(market.list(id, item, price, cfg.maxListingsPerPlayer(),
                System.currentTimeMillis(), cfg.listingDurationDays()), id,
                "listing " + item.summary(), (listingId, failure) -> {
                    if (failure == null) {
                        // Escrow committed: now, and only now, charge the fee. Re-resolve the
                        // player -- if they logged off in the window, the item is safely listed and
                        // the fee is simply not charged, which is the non-dangerous direction.
                        Player again = plugin.getServer().getPlayer(id);
                        if (again != null) {
                            again.giveExp(-fee);
                        }
                        message(id, text("Listed as #" + listingId + " for " + price.format()
                                + " diamonds. Fee: " + fee + " XP.", NamedTextColor.GREEN));
                        return;
                    }
                    if (failure instanceof MarketException refused) {
                        // A refusal is definite: nothing was written, so the item is safe to return
                        // and no fee was charged.
                        giveOrDrop(id, where, removed);
                        message(id, text(MarketResolver.messageFor(refused.reason()),
                                NamedTextColor.RED));
                        return;
                    }
                    // Unknown, not failed. The item left the hand and may or may not be listed;
                    // returning it could dupe it, so it is not returned. Same policy as deposit.
                    reportUncertain(id, "listing " + item.summary(), failure,
                            "Your item was taken out of your hand and may or may not be listed; "
                                    + "do not sell another copy until an admin checks.");
                });
    }

    // ---- buy --------------------------------------------------------------------------

    /**
     * Buys a listing. The sale -- the money move, the tax split, and the trade-log row -- commits
     * on the writer thread inside one transaction; only after it has committed is the item
     * delivered. If delivery cannot happen (the buyer is offline or full) the unique is held for
     * claim rather than dropped, because the sale is already final and a valuable unique must not
     * despawn on the ground.
     */
    private void buy(Player player, long listingId) {
        UUID id = player.getUniqueId();
        FmConfig cfg = config.get();
        onMainThread(market.buy(id, listingId, cfg.salesTaxPercent(), cfg.taxBurnShare(),
                System.currentTimeMillis()), id, "buying listing " + listingId,
                (result, failure) -> {
                    if (failure == null) {
                        message(id, text("Bought #" + listingId + " for "
                                + result.split().gross().format() + " diamonds (tax "
                                + result.split().tax().format() + "). Enjoy!", NamedTextColor.GREEN));
                        ItemStack bought;
                        try {
                            bought = BukkitItemCodec.decode(result.itemBytes());
                        } catch (RuntimeException corrupt) {
                            // The sale has ALREADY committed -- money moved, listing SOLD -- and the
                            // escrowed unique can no longer be built into an ItemStack (a Paper or
                            // registry change across a server upgrade while it sat listed). The buyer
                            // is charged; losing the item here would be a silent loss. The raw bytes
                            // still survive, so hold them for claim from the bytes themselves rather
                            // than decoding -- claimNext's own decode-guard skips-and-logs a
                            // permanently-undecodable row on the claim side. Same reconciliation
                            // standard as reportUncertain: name the player, listing, and operation.
                            log.log(Level.WARNING, "FarmersMarket: buyer " + id + " bought listing "
                                    + listingId + " but its escrowed item could not be decoded after "
                                    + "the sale committed; holding the raw bytes in /market claim for "
                                    + "an admin to recover. The buyer was charged and the sale is "
                                    + "final -- no money is compensated.", corrupt);
                            holdBytesForClaim(id, result.itemBytes(), result.amount(),
                                    result.summary(), "PURCHASE",
                                    "Your purchased item could not be delivered just now; it is "
                                            + "waiting in /market claim.");
                            return;
                        }
                        deliverItemOrHold(id, bought, result.amount(), result.summary(), "PURCHASE");
                        return;
                    }
                    if (failure instanceof MarketException refused) {
                        // Nothing was written -- the sale rolls back whole on any refusal.
                        message(id, text(MarketResolver.messageFor(refused.reason()),
                                NamedTextColor.RED));
                        return;
                    }
                    reportUncertain(id, "buying listing " + listingId, failure,
                            "You may or may not have been charged; check /market balance before "
                                    + "trying again.");
                });
    }

    // ---- cancel -----------------------------------------------------------------------

    /**
     * Cancels the player's own listing, taking it off sale and returning the escrowed item. The
     * seller is standing right there, so the item almost always lands back in their hand; if it
     * does not, it is held for claim like a purchase, never dropped.
     */
    private void cancel(Player player, long listingId) {
        UUID id = player.getUniqueId();
        onMainThread(market.cancel(id, listingId, System.currentTimeMillis()), id,
                "cancelling listing " + listingId, (bytes, failure) -> {
                    if (failure == null) {
                        message(id, text("Cancelled listing #" + listingId + ".",
                                NamedTextColor.GREEN));
                        ItemStack stack;
                        String summary;
                        try {
                            stack = BukkitItemCodec.decode(bytes);
                            summary = BukkitItemCodec.encode(stack).summary();
                        } catch (RuntimeException corrupt) {
                            // The cancel has ALREADY committed -- the listing is CANCELLED -- and the
                            // escrowed item can no longer be decoded (a Paper or registry change
                            // while it sat listed). Dropping it here would be a silent loss, so hold
                            // the raw bytes for claim rather than the decoded stack; claimNext's own
                            // decode-guard skips-and-logs a permanently-undecodable row on the claim
                            // side. amount 1 and a generic summary only satisfy the pending row's
                            // amount > 0 / NOT NULL constraints -- the true amount is unknown without
                            // a decodable stack, and the row is never actually handed over anyway.
                            // Same reconciliation standard as reportUncertain.
                            log.log(Level.WARNING, "FarmersMarket: seller " + id + " cancelled listing "
                                    + listingId + " but its escrowed item could not be decoded after "
                                    + "the cancel committed; holding the raw bytes in /market claim for "
                                    + "an admin to recover.", corrupt);
                            holdBytesForClaim(id, bytes, 1,
                                    "Unreadable cancelled item (listing #" + listingId + ")",
                                    "CANCELLED",
                                    "Your cancelled item could not be returned just now; it is "
                                            + "waiting in /market claim.");
                            return;
                        }
                        deliverItemOrHold(id, stack, stack.getAmount(), summary, "CANCELLED");
                        return;
                    }
                    if (failure instanceof MarketException refused) {
                        message(id, text(MarketResolver.messageFor(refused.reason()),
                                NamedTextColor.RED));
                        return;
                    }
                    reportUncertain(id, "cancelling listing " + listingId, failure,
                            "Your listing may or may not have been cancelled; check /market mine "
                                    + "before trying again.");
                });
    }

    // ---- browse / info / mine / pot (read-only) ---------------------------------------

    /** A page of the active unique listings. Read-only, so the console may run it. */
    private void browse(CommandSender sender, int page) {
        onMainThreadForSender(market.browse(null, page, BROWSE_PAGE_SIZE), sender, (rows, failure) -> {
            if (failure != null) {
                sender.sendMessage(text("Could not read the market just now.", NamedTextColor.RED));
                return;
            }
            if (rows.isEmpty()) {
                sender.sendMessage(text(page == 1 ? "Nothing is listed right now."
                        : "No listings on page " + page + ".", NamedTextColor.AQUA));
                return;
            }
            sender.sendMessage(text("Market listings, page " + page + ":", NamedTextColor.AQUA));
            for (ListingRow row : rows) {
                sender.sendMessage(text("#" + row.id() + "  " + row.summary() + "  -- "
                        + Diamonds.ofDust(row.priceDust()).format() + " diamonds",
                        NamedTextColor.YELLOW));
            }
            sender.sendMessage(text("Buy one with /market buy <number>.", NamedTextColor.GRAY));
        });
    }

    /** The full detail of one listing, including a container's content summary. Read-only. */
    private void info(CommandSender sender, long listingId) {
        onMainThreadForSender(market.findListing(listingId), sender, (found, failure) -> {
            if (failure != null) {
                sender.sendMessage(text("Could not read the market just now.", NamedTextColor.RED));
                return;
            }
            if (found.isEmpty()) {
                sender.sendMessage(text("There is no listing #" + listingId + ".",
                        NamedTextColor.RED));
                return;
            }
            ListingRow row = found.get();
            sender.sendMessage(text("Listing #" + row.id(), NamedTextColor.AQUA));
            sender.sendMessage(text("Item: " + row.summary(), NamedTextColor.YELLOW));
            sender.sendMessage(text("Price: " + Diamonds.ofDust(row.priceDust()).format()
                    + " diamonds", NamedTextColor.YELLOW));
            sender.sendMessage(text("Status: " + row.status(), NamedTextColor.GRAY));
        });
    }

    /** The player's own still-active listings -- the ones they can cancel. */
    private void mine(Player player) {
        UUID id = player.getUniqueId();
        onMainThreadForSender(market.myListings(id), player, (rows, failure) -> {
            if (failure != null) {
                player.sendMessage(text("Could not read the market just now.", NamedTextColor.RED));
                return;
            }
            if (rows.isEmpty()) {
                player.sendMessage(text("You have no active listings.", NamedTextColor.AQUA));
                return;
            }
            player.sendMessage(text("Your active listings:", NamedTextColor.AQUA));
            for (ListingRow row : rows) {
                player.sendMessage(text("#" + row.id() + "  " + row.summary() + "  -- "
                        + Diamonds.ofDust(row.priceDust()).format() + " diamonds",
                        NamedTextColor.YELLOW));
            }
            player.sendMessage(text("Cancel one with /market cancel <number>.", NamedTextColor.GRAY));
        });
    }

    /** The community pot balance. Read-only, so the console may run it. */
    private void pot(CommandSender sender) {
        onMainThreadForSender(market.communityPotBalance(), sender, (balance, failure) -> {
            if (failure != null) {
                sender.sendMessage(text("Could not read the market just now.", NamedTextColor.RED));
                return;
            }
            sender.sendMessage(text("Community pot: " + balance.format() + " diamonds",
                    NamedTextColor.AQUA));
        });
    }

    // ---- claim ------------------------------------------------------------------------

    /**
     * Hands the player everything the market owes them -- sold-listing items they were away for,
     * cancelled or expired items that would not fit -- one row at a time.
     *
     * <p><b>Claim before deliver, never the other way round.</b> Every other flow in this plugin
     * writes to the ledger or the market first and touches the inventory second, and claim is no
     * exception: for each owed row it marks the row claimed <em>before</em> putting the item in the
     * inventory. The alternative -- give first, mark claimed second -- lets two overlapping
     * {@code /market claim} runs each read the row as unclaimed, each hand over a copy, and only
     * then have one of the two {@code claimOne} calls fail: that is a duplicate. Marking claimed
     * first makes the second run's {@code claimOne} a definite refusal that hands over nothing.
     * Space is checked before the claim so a full inventory refuses cleanly without marking
     * anything, and any owed item that still will not fit after the claim commits (the inventory
     * changed during the write) is re-held for claim rather than lost.
     */
    private void claim(Player player) {
        UUID id = player.getUniqueId();
        onMainThreadForSender(market.pendingFor(id), player, (pending, failure) -> {
            if (failure != null) {
                player.sendMessage(text("Could not read the market just now.", NamedTextColor.RED));
                return;
            }
            if (pending.isEmpty()) {
                player.sendMessage(text("You have nothing to claim.", NamedTextColor.AQUA));
                return;
            }
            claimNext(id, pending, 0, 0);
        });
    }

    /**
     * Claims one owed row and recurses to the next, threading the running claimed-count so the
     * final message and the inventory-full message can name it. Runs on the main thread; each
     * {@link MarketService#claimOne} hops to the writer thread and back through
     * {@link #onMainThread}, so this is a chain of ticks, not a busy loop.
     */
    private void claimNext(UUID id, List<PendingItemRow> pending, int index, int claimedSoFar) {
        if (index >= pending.size()) {
            message(id, text("Claimed " + claimedSoFar + " item" + plural(claimedSoFar) + ".",
                    NamedTextColor.GREEN));
            return;
        }
        Player online = plugin.getServer().getPlayer(id);
        if (online == null) {
            // Left mid-claim. Everything not yet claimed is still owed and waits for next time.
            return;
        }
        PendingItemRow row = pending.get(index);
        ItemStack stack;
        try {
            stack = BukkitItemCodec.decode(row.itemBytes());
        } catch (RuntimeException corrupt) {
            // One unreadable owed row must not sink the whole claim. Leave it owed, log it, move on.
            log.log(Level.WARNING, "FarmersMarket: could not decode owed item " + row.id()
                    + " for " + id + "; leaving it in /market claim for an admin to look at.", corrupt);
            claimNext(id, pending, index + 1, claimedSoFar);
            return;
        }

        if (!fitsWholly(online, stack)) {
            int remaining = pending.size() - index;
            if (claimedSoFar > 0) {
                message(id, text("Claimed " + claimedSoFar + " item" + plural(claimedSoFar) + ".",
                        NamedTextColor.GREEN));
            }
            message(id, text("Your inventory is full. " + remaining + " item" + plural(remaining)
                    + " still waiting in /market claim.", NamedTextColor.YELLOW));
            return;
        }

        onMainThread(market.claimOne(id, row.id(), System.currentTimeMillis()), id,
                "claiming owed item " + row.id(), (claimed, failure) -> {
                    if (failure == null) {
                        deliverClaimed(id, claimed);
                        claimNext(id, pending, index + 1, claimedSoFar + 1);
                        return;
                    }
                    if (failure instanceof MarketException refused) {
                        // Definite refusal: the row was claimed by an overlapping run or is gone, and
                        // nothing was written here, so nothing was handed over. Skip it and continue.
                        log.fine("FarmersMarket: owed item " + row.id() + " for " + id
                                + " was already claimed; skipping. " + refused.reason());
                        claimNext(id, pending, index + 1, claimedSoFar);
                        return;
                    }
                    // Unknown outcome: the claim may or may not have committed, so do not hand
                    // anything over and do not retry. Stop the walk to avoid compounding it.
                    reportUncertain(id, "claiming owed item " + row.id(), failure,
                            "That item may or may not have been claimed; run /market claim again "
                                    + "in a moment and tell an admin if it looks wrong.");
                });
    }

    /**
     * Puts a just-claimed row's item into the player's inventory. The row was marked claimed by
     * {@link MarketService#claimOne} before this runs, and space was checked before that, so the
     * item all but always fits here. The one exception is the player having filled the inventory
     * during the claim's writer-thread round trip: the item is then re-held for claim rather than
     * dropped or lost, because it is already off the {@code pending_items} row it came from.
     */
    private void deliverClaimed(UUID id, PendingItemRow claimed) {
        Player online = plugin.getServer().getPlayer(id);
        ItemStack stack = BukkitItemCodec.decode(claimed.itemBytes());
        if (online != null && fitsWholly(online, stack)) {
            online.getInventory().addItem(stack);
            message(id, text("Claimed: " + claimed.summary(), NamedTextColor.AQUA));
            return;
        }
        // Claimed but no longer fits (or the player just left): re-hold so nothing is lost.
        holdForClaim(id, stack, claimed.amount(), claimed.summary(), "RECLAIM",
                "Your inventory filled up, so " + claimed.summary()
                        + " is waiting in /market claim again.");
    }

    // ---- deliver-or-hold --------------------------------------------------------------

    /**
     * Hands {@code stack} to the player if they are online and it fits wholly; otherwise holds it
     * for {@code /market claim}. A valuable unique is never dropped on the ground here: an item
     * dropped for an offline or full recipient despawns, and the whole point of the claim path is
     * that a unique survives until its owner can take it.
     *
     * <p>Called only after the money-moving operation that produced the item has already committed
     * (a sale, a cancellation), so this method's job is delivery, not the transaction.
     */
    private void deliverItemOrHold(UUID id, ItemStack stack, int amount, String summary,
            String reason) {
        Player online = plugin.getServer().getPlayer(id);
        if (online != null && fitsWholly(online, stack)) {
            online.getInventory().addItem(stack);
            return;
        }
        holdForClaim(id, stack, amount, summary, reason,
                "Your inventory was full, so it is waiting in /market claim.");
    }

    /**
     * Records an item as owed to the player through the market, then tells them where to find it.
     * If even that insert is refused, the item bytes are still recoverable from the {@code SOLD} or
     * {@code CANCELLED} listing row they came from, so this reports an uncertain escrow rather than
     * dropping or duplicating anything.
     */
    private void holdForClaim(UUID id, ItemStack stack, int amount, String summary, String reason,
            String heldNote) {
        holdBytesForClaim(id, stack.serializeAsBytes(), amount, summary, reason, heldNote);
    }

    /**
     * The raw-bytes core of {@link #holdForClaim}: records item bytes as owed even when no
     * {@link ItemStack} can be built from them.
     *
     * <p>This is the path a <em>post-commit</em> decode failure on {@code buy} or {@code cancel}
     * takes. The sale or cancellation is already final and the bytes are all that survive of a
     * unique whose registry changed under it (a Paper upgrade while it sat listed), so they are
     * held verbatim for {@code /market claim} rather than lost -- the {@code pending_items} row
     * stores {@code item_bytes} regardless of whether a stack can be decoded, and {@code claimNext}
     * guards decode on the claim side, so a permanently-undecodable row is skipped-and-logged there
     * and never handed over as a broken item. If even this insert is refused, the bytes remain on
     * the {@code SOLD}/{@code CANCELLED} listing row they came from, so this reports an uncertain
     * escrow rather than dropping or duplicating anything. No money moves on this path.
     */
    private void holdBytesForClaim(UUID id, byte[] bytes, int amount, String summary, String reason,
            String heldNote) {
        onMainThread(market.holdForClaim(id, bytes, amount, summary, reason,
                System.currentTimeMillis()), id, "holding " + summary + " for claim",
                (pendingId, failure) -> {
                    if (failure == null) {
                        message(id, text(heldNote, NamedTextColor.YELLOW));
                        return;
                    }
                    reportUncertain(id, "holding " + summary + " for claim", failure,
                            "Your item could not be placed in /market claim right now. It is not "
                                    + "lost -- an admin can recover it -- so please tell one.");
                });
    }

    /**
     * Whether {@code stack} fits <em>wholly</em> in the player's storage slots, counting empty
     * slots and room in similar partial stacks. Checked before delivery so an item is never split
     * -- half handed over, half held -- which would break the claim path's all-or-nothing contract.
     */
    private static boolean fitsWholly(Player player, ItemStack stack) {
        int need = stack.getAmount();
        int max = stack.getMaxStackSize();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                need -= max;
            } else if (slot.isSimilar(stack)) {
                need -= Math.max(0, max - slot.getAmount());
            }
            if (need <= 0) {
                return true;
            }
        }
        return need <= 0;
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }

    // ---- reload -----------------------------------------------------------------------

    private void reload(CommandSender sender) {
        List<String> warnings;
        try {
            warnings = reloadAction.get();
        } catch (Throwable t) {
            // A reload failure must leave the plugin running on its previous configuration
            // rather than taking anything down with it.
            error(sender, "Reload failed; the previous configuration is still active. "
                    + "See the server log.");
            log.log(Level.WARNING, "FarmersMarket: reload failed.", t);
            return;
        }
        if (warnings == null || warnings.isEmpty()) {
            sender.sendMessage(text("Farmers Market configuration reloaded.", NamedTextColor.GREEN));
            return;
        }
        sender.sendMessage(text("Farmers Market configuration reloaded with " + warnings.size()
                + " warning(s); defaults were substituted:", NamedTextColor.YELLOW));
        for (String warning : warnings) {
            sender.sendMessage(text("  " + warning, NamedTextColor.YELLOW));
        }
    }

    // ---- inventory arithmetic ----------------------------------------------------------

    /**
     * Whether {@code stack} is an ordinary diamond that this plugin is willing to move.
     *
     * <p>A renamed, enchanted, or otherwise tagged diamond is deliberately excluded from both
     * counting and removal. It is somebody's named item, it does not stack with a plain one, and
     * swallowing it into a fungible balance would destroy whatever made it worth naming.
     */
    private static boolean isPlainDiamond(ItemStack stack) {
        return stack != null && stack.getType() == Material.DIAMOND && !stack.hasItemMeta();
    }

    /** How many plain diamonds are in the player's 36 storage slots. */
    private static int countPlainDiamonds(PlayerInventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (isPlainDiamond(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Removes exactly {@code count} plain diamonds from the player's storage slots.
     *
     * @return how many were actually removed, which is {@code count} unless the inventory changed
     *         between the caller's count and this call
     */
    private static int removePlainDiamonds(PlayerInventory inventory, int count) {
        ItemStack[] contents = inventory.getStorageContents();
        int remaining = count;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!isPlainDiamond(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            remaining -= take;
            if (take >= stack.getAmount()) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
                contents[slot] = stack;
            }
        }
        inventory.setStorageContents(contents);
        return count - remaining;
    }

    /**
     * How many more diamonds the player's storage slots can accept.
     *
     * <p>The counting happens here because it needs an inventory; the arithmetic happens in
     * {@link MarketResolver#diamondCapacity}, where it can be tested.
     */
    private static long capacityForDiamonds(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        int emptySlots = 0;
        List<Integer> partials = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() == Material.AIR) {
                emptySlots++;
            } else if (isPlainDiamond(stack)) {
                partials.add(stack.getAmount());
            }
        }
        int[] sizes = new int[partials.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = partials.get(i);
        }
        return MarketResolver.diamondCapacity(emptySlots, sizes, Material.DIAMOND.getMaxStackSize());
    }

    /** {@code count} diamonds split into whole stacks. */
    private static ItemStack[] diamondStacks(int count) {
        int perStack = Material.DIAMOND.getMaxStackSize();
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = count; left > 0; left -= perStack) {
            stacks.add(new ItemStack(Material.DIAMOND, Math.min(perStack, left)));
        }
        return stacks.toArray(new ItemStack[0]);
    }

    /**
     * Adds {@code count} diamonds to an online player's inventory.
     *
     * @return how many did not fit and were therefore <em>not</em> added
     */
    private static int addToInventory(Player player, int count) {
        Map<Integer, ItemStack> leftOver = player.getInventory().addItem(diamondStacks(count));
        int notAdded = 0;
        for (ItemStack stack : leftOver.values()) {
            notAdded += stack.getAmount();
        }
        return notAdded;
    }

    /**
     * Puts {@code count} diamonds on the ground at {@code where}, the last resort for value that
     * has to go somewhere and has no inventory to go into.
     */
    private void dropAt(Location where, int count) {
        if (where == null || where.getWorld() == null) {
            log.severe("FarmersMarket: had to drop " + count + " diamonds but no world was "
                    + "available to drop them in; they are lost. This should not happen.");
            return;
        }
        for (ItemStack stack : diamondStacks(count)) {
            where.getWorld().dropItemNaturally(where, stack);
        }
    }

    /**
     * Returns {@code count} diamonds to a player: into their inventory if they are still online,
     * on the ground where they ran the command if they are not.
     */
    private void giveOrDrop(UUID id, Location where, int count) {
        Player online = plugin.getServer().getPlayer(id);
        if (online == null) {
            dropAt(where, count);
            return;
        }
        int notAdded = addToInventory(online, count);
        if (notAdded > 0) {
            dropAt(online.getLocation(), notAdded);
        }
    }

    /**
     * Returns a listed item to a player after a refused listing: into their inventory if they are
     * still online, and on the ground where they ran the command if they are not or it will not
     * fit. Unlike the claim path, a refused <em>sell</em> is a straight hand-back to a player who
     * is almost always standing right there, so a drop is the correct last resort here rather than
     * a held claim -- the item was in their hand a moment ago and is going straight back.
     */
    private void giveOrDrop(UUID id, Location where, ItemStack stack) {
        Player online = plugin.getServer().getPlayer(id);
        if (online == null) {
            dropAt(where, stack);
            return;
        }
        for (ItemStack notAdded : online.getInventory().addItem(stack).values()) {
            dropAt(online.getLocation(), notAdded);
        }
    }

    /** Puts a single item stack on the ground at {@code where}, the last resort for a returned item. */
    private void dropAt(Location where, ItemStack stack) {
        if (where == null || where.getWorld() == null) {
            log.severe("FarmersMarket: had to drop a " + stack.getType() + " x" + stack.getAmount()
                    + " but no world was available to drop it in; it is lost. This should not happen.");
            return;
        }
        where.getWorld().dropItemNaturally(where, stack);
    }

    // ---- plumbing ----------------------------------------------------------------------

    /**
     * Runs {@code handler} on the server main thread once {@code future} settles, with the
     * failure already unwrapped from its {@link CompletionException} shell.
     *
     * <p>This is the only place a ledger result crosses back onto the main thread, and every
     * completion in this class goes through it. That is deliberate: one hop is auditable, and a
     * completion that quietly forgot to hop would touch an inventory from the database writer
     * thread.
     *
     * <h2>Settling after the plugin is disabled</h2>
     *
     * <p>Paper flips {@code isEnabled} to {@code false} <em>before</em> it calls
     * {@code onDisable}, and {@code onDisable} then calls {@code DatabaseExecutor.close()},
     * which <b>runs</b> whatever is still queued rather than discarding it. So a task submitted
     * moments before a stop can commit and settle on this branch having <em>succeeded</em> --
     * the money has moved and there is no main thread left to hand the items over on. That is
     * the dangerous case, not the failure case, which is why the log line below is written
     * unconditionally and names the player, the operation, and the amount: it is the entire
     * record that a shutdown-time withdrawal leaves behind, and it has to be reconcilable by
     * hand from the log alone, to the same standard {@link #reportUncertain} sets.
     *
     * @param id      the player the operation belongs to, named in the shutdown log line
     * @param what    the operation and its amount, e.g. {@code "withdrawing 64 diamonds"}, for
     *                the same line
     */
    private <T> void onMainThread(CompletableFuture<T> future, UUID id, String what,
                                  BiConsumer<T, Throwable> handler) {
        future.whenComplete((value, failure) -> {
            Throwable cause = unwrap(failure);
            if (!plugin.isEnabled()) {
                // The server is shutting down; there is no main thread left to schedule onto,
                // so the handler -- the delivery, the message, the compensation -- never runs.
                log.log(Level.WARNING, shutdownReconciliationLine(id, what, cause), cause);
                return;
            }
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> handler.accept(value, cause));
            } catch (RuntimeException e) {
                // Losing the race against disable, almost always. Never swallowed silently.
                log.log(Level.WARNING, "FarmersMarket: could not schedule a ledger result back "
                        + "onto the main thread.", e);
            }
        });
    }

    /**
     * The read-only sibling of {@link #onMainThread}: schedules {@code handler} back onto the main
     * thread when {@code future} settles, so a read that the console may also run can send its
     * lines to a {@link CommandSender} rather than to a player resolved by UUID.
     *
     * <p>There is no shutdown-reconciliation log line here on purpose. Every future routed through
     * this helper is a <em>read</em> -- browse, info, mine, pot, and the pending-items list -- so a
     * result that settles after the plugin was disabled moved no money and owes nobody anything;
     * dropping it silently is correct, where a money op dropping silently is the exact thing the
     * other seam logs about.
     */
    private <T> void onMainThreadForSender(CompletableFuture<T> future, CommandSender sender,
                                           BiConsumer<T, Throwable> handler) {
        future.whenComplete((value, failure) -> {
            Throwable cause = unwrap(failure);
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> handler.accept(value, cause));
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "FarmersMarket: could not schedule a market read back onto "
                        + "the main thread.", e);
            }
        });
    }

    /**
     * The one log line a ledger operation leaves behind when it settles after the plugin was
     * disabled.
     *
     * <p>Written whether the operation succeeded or failed, because <b>success is the outcome
     * that costs a player money here</b>: the debit committed during {@code onDisable}'s flush,
     * the handler that would have handed the diamonds over never ran, and nothing else in the
     * plugin will ever mention it. An admin holding only this line must be able to reconcile the
     * account, so it names the player UUID, the operation, the amount, and which of the two
     * outcomes happened.
     *
     * <p>Package-private and pure so it can be tested: building it inside the completion would
     * put the only shutdown-time money record behind a running Paper server. Same seam as
     * {@link #unwrap}.
     */
    static String shutdownReconciliationLine(UUID id, String what, Throwable cause) {
        return "FarmersMarket: " + what + " (player " + id + ") settled after the plugin was "
                + "disabled, so nothing was handed over, the player was not told, and nothing "
                + "was compensated. " + (cause == null
                        ? "The ledger APPLIED it -- the balance has already moved."
                        : "The ledger reported a failure; it may or may not have applied.")
                + " Reconcile this account by hand.";
    }

    /**
     * The failure a caller actually cares about, with {@link CompletionException} and
     * {@link ExecutionException} wrappers peeled off.
     *
     * <p>Without this, a {@link LedgerException} arriving through a chained stage is an
     * unrecognised cause, and an unrecognised cause is treated as an unknown outcome -- which
     * would turn every ordinary "you only have three diamonds" into an alarming message telling
     * the player to go find an admin.
     *
     * <p>As things stand this is defensive rather than load-bearing: {@code DatabaseExecutor}
     * completes its futures with {@code completeExceptionally(t)} directly, so nothing currently
     * arrives wrapped. It is kept because the first chained stage anyone adds would change that
     * silently, and the symptom would be the alarming message above rather than a crash.
     *
     * <p>Package-private rather than private so it can be tested: it is pure, it imports nothing
     * from Bukkit, and it is the function standing between a routine refusal and a
     * go-find-an-admin message. Same seam as {@code Ledger.inTransaction} and
     * {@code EditionResolver.invokePublic}.
     */
    static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Reports an operation whose cause is not a {@link LedgerException}: an unknown outcome, not
     * a failed one.
     *
     * <p>Logged at {@code WARNING} with the whole stack trace, because this is the only record
     * anyone will have that a balance may need reconciling by hand. Nothing is retried and
     * nothing is compensated on this path, in either direction.
     *
     * <p><b>Then, and only for the log, one follow-up balance read.</b> Without it an admin
     * reading this warning cannot tell whether the write landed and has to go and ask the player;
     * with it the answer is on the next line. The read is issued <em>after</em> the failure has
     * already been logged rather than before, so a follow-up that never completes -- the executor
     * shutting down, say -- cannot cost us the reconciliation record itself. The read writes
     * nothing and is not a retry.
     */
    private void reportUncertain(UUID id, String what, Throwable failure, String extraForPlayer) {
        log.log(Level.WARNING, "FarmersMarket: " + what + " (player " + id + ") did not report a "
                + "result. The ledger may or may not have applied it; nothing was retried and "
                + "nothing was compensated. Reconcile this account by hand if the player "
                + "reports a problem.", failure);

        Player online = plugin.getServer().getPlayer(id);
        if (online != null) {
            online.sendMessage(text(MarketResolver.UNCERTAIN_MESSAGE, NamedTextColor.RED));
            if (extraForPlayer != null) {
                online.sendMessage(text(extraForPlayer, NamedTextColor.RED));
            }
        }

        onMainThread(ledger.balance(id), id, "reading a balance after an unconfirmed operation",
                (held, readFailure) -> {
                    if (readFailure == null) {
                        log.warning("FarmersMarket: after that unconfirmed operation, " + id
                                + "'s balance now reads " + held.format() + " diamonds.");
                        return;
                    }
                    log.log(Level.WARNING, "FarmersMarket: could not read " + id + "'s balance "
                            + "after that unconfirmed operation, so the log cannot say whether "
                            + "it landed.", readFailure);
                });
    }

    /** Sends {@code component} to {@code id} if they are still online, and drops it if not. */
    private void message(UUID id, Component component) {
        Player online = plugin.getServer().getPlayer(id);
        if (online != null) {
            online.sendMessage(component);
        }
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(text(message, NamedTextColor.RED));
    }

    /**
     * Plain coloured text and nothing else.
     *
     * <p>{@link NamedTextColor} is exactly the sixteen legacy colours, which is the whole palette
     * Geyser carries through to a Bedrock client intact. No decoration is applied anywhere in
     * this class: Geyser strips strikethrough and underline outright, so a message that relied on
     * either would arrive with its meaning missing rather than merely its styling.
     */
    private static Component text(String message, NamedTextColor colour) {
        return Component.text(message).color(colour);
    }
}
