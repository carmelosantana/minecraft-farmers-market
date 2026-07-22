/*
 * FarmersMarket - the /market command tree: balance, deposit, withdraw, and reload.
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

import org.xpfarm.farmersmarket.ledger.Diamonds;
import org.xpfarm.farmersmarket.ledger.ExperienceMath;
import org.xpfarm.farmersmarket.ledger.Ledger;
import org.xpfarm.farmersmarket.ledger.LedgerException;

/**
 * {@code /market [balance | deposit [qty] | withdraw <qty> | reload]}, with tab completion.
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
 * <h2>Bedrock safety</h2>
 *
 * <p>All output is plain chat text with legacy-named colours. No hover events, no click events
 * (a {@code copy_to_clipboard} click event breaks Bedrock chat outright), no hex or gradient
 * colours, no strikethrough or underline, and no block-ramp glyphs -- Geyser either strips or
 * silently blanks every one of those.
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    /**
     * Deliberately {@code java.util.logging} rather than {@code Bukkit.getLogger()}: the paths
     * that log here are the ones where something has already gone wrong with the ledger, and
     * reaching through {@code Bukkit} for a logger makes them depend on a live server.
     */
    private static final Logger LOG = Logger.getLogger(MarketCommand.class.getName());

    private final Plugin plugin;
    private final Ledger ledger;
    private final Supplier<List<String>> reloadAction;

    /**
     * @param plugin       the owning plugin, used only to schedule work back onto the main thread
     * @param ledger       the one thing in the plugin that moves money
     * @param reloadAction re-reads {@code config.yml} and returns the validation warnings it
     *                     produced, empty when the file was clean
     */
    public MarketCommand(Plugin plugin, Ledger ledger, Supplier<List<String>> reloadAction) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
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
        onMainThread(ledger.balance(id), (held, failure) -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null) {
                return;
            }
            if (failure != null) {
                reportFailure(online, id, "reading your balance", failure, null);
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
    private static int experiencePoints(Player player) {
        float progress = Math.max(0f, Math.min(1f, player.getExp()));
        try {
            return ExperienceMath.totalPoints(Math.max(0, player.getLevel()), progress);
        } catch (ArithmeticException | IllegalArgumentException e) {
            // Nothing a reachable level can cause. Showing a balance without an XP line beats
            // failing the whole command over the cosmetic half of it.
            LOG.log(Level.WARNING, "FarmersMarket: could not compute experience points for "
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
        onMainThread(ledger.deposit(id, credited), (balance, failure) -> {
            if (failure == null) {
                message(id, text("Deposited " + moved + " diamonds. Balance: " + balance.format(),
                        NamedTextColor.GREEN));
                return;
            }
            if (failure instanceof LedgerException refused) {
                // A refusal is definite: nothing was written, so the items are safe to return.
                giveOrDrop(id, where, moved);
                message(id, text(MarketResolver.messageFor(refused.reason(), null)
                        + " Your " + moved + " diamonds were returned.", NamedTextColor.RED));
                return;
            }
            // Unknown, not failed. Returning the items could dupe them; retrying could charge
            // twice. Say so, log it, and leave it for a human.
            reportFailure(plugin.getServer().getPlayer(id), id,
                    "depositing " + moved + " diamonds", failure,
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
        onMainThread(ledger.withdraw(id, amount), (balance, failure) -> {
            if (failure == null) {
                message(id, text("Withdrew " + count + " diamonds. Balance: " + balance.format(),
                        NamedTextColor.GREEN));
                deliver(id, where, count);
                return;
            }
            if (failure instanceof LedgerException refused) {
                // A refusal means the debit never happened, so there is nothing to undo. The
                // balance is only re-read to fill in the number the player wants to hear.
                explainRefusedWithdrawal(id, refused);
                return;
            }
            reportFailure(plugin.getServer().getPlayer(id), id,
                    "withdrawing " + count + " diamonds", failure,
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
        onMainThread(ledger.balance(id), (held, readFailure) ->
                message(id, text(MarketResolver.messageFor(refused.reason(),
                        readFailure == null ? held : null), NamedTextColor.RED)));
    }

    /**
     * Hands over {@code count} withdrawn diamonds, crediting back anything that will not fit.
     *
     * <p>The player has already been debited by the time this runs, so every branch has to end
     * with the value somewhere: in their inventory, back in their balance, or -- only if the
     * credit itself fails -- on the ground at their feet. There is no branch that ends with the
     * diamonds nowhere.
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
        onMainThread(ledger.deposit(id, Diamonds.ofDiamonds(returned)), (balance, failure) -> {
            if (failure == null) {
                message(id, text("Your inventory filled up, so " + returned
                        + " diamonds went back into the market.", NamedTextColor.YELLOW));
                return;
            }
            // The credit did not land, or might not have. Either way this side still holds the
            // physical diamonds, so putting them on the ground is the one move that cannot
            // destroy them -- and it is not a retry of the credit.
            Player again = plugin.getServer().getPlayer(id);
            dropAt(again != null ? again.getLocation() : where, returned);
            message(id, text("Your inventory filled up, so " + returned
                    + " diamonds were dropped at your feet.", NamedTextColor.YELLOW));
            LOG.log(Level.WARNING, "FarmersMarket: could not return " + returned
                    + " undelivered diamonds to " + id + "'s balance; dropped them as items "
                    + "instead so nothing was lost.", failure);
        });
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
            LOG.log(Level.WARNING, "FarmersMarket: reload failed.", t);
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
            LOG.severe("FarmersMarket: had to drop " + count + " diamonds but no world was "
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

    // ---- plumbing ----------------------------------------------------------------------

    /**
     * Runs {@code handler} on the server main thread once {@code future} settles, with the
     * failure already unwrapped from its {@link CompletionException} shell.
     *
     * <p>This is the only place a ledger result crosses back onto the main thread, and every
     * completion in this class goes through it. That is deliberate: one hop is auditable, and a
     * completion that quietly forgot to hop would touch an inventory from the database writer
     * thread.
     */
    private <T> void onMainThread(CompletableFuture<T> future, BiConsumer<T, Throwable> handler) {
        future.whenComplete((value, failure) -> {
            Throwable cause = unwrap(failure);
            if (!plugin.isEnabled()) {
                // The server is shutting down; there is no main thread left to schedule onto.
                if (cause != null) {
                    LOG.log(Level.WARNING, "FarmersMarket: a ledger operation failed after the "
                            + "plugin was disabled, so nobody was told.", cause);
                }
                return;
            }
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> handler.accept(value, cause));
            } catch (RuntimeException e) {
                // Losing the race against disable, almost always. Never swallowed silently.
                LOG.log(Level.WARNING, "FarmersMarket: could not schedule a ledger result back "
                        + "onto the main thread.", e);
            }
        });
    }

    /**
     * The failure a caller actually cares about, with {@link CompletionException} and
     * {@link ExecutionException} wrappers peeled off.
     *
     * <p>Without this, a {@link LedgerException} arriving through a chained stage is an
     * unrecognised cause, and an unrecognised cause is treated as an unknown outcome -- which
     * would turn every ordinary "you only have three diamonds" into an alarming message telling
     * the player to go find an admin.
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Reports a failure whose cause is not a {@link LedgerException}: an unknown outcome, not a
     * failed one.
     *
     * <p>Logged at {@code WARNING} with the whole stack trace, because this is the only record
     * anyone will have that a balance may need reconciling by hand. Never retried.
     */
    private void reportFailure(Player online, UUID id, String what, Throwable failure,
                               String extraForPlayer) {
        LOG.log(Level.WARNING, "FarmersMarket: " + what + " for " + id + " did not report a "
                + "result. The ledger may or may not have applied it; nothing was retried and "
                + "nothing was compensated. Reconcile this account by hand if the player "
                + "reports a problem.", failure);
        if (online == null) {
            return;
        }
        online.sendMessage(text(MarketResolver.UNCERTAIN_MESSAGE, NamedTextColor.RED));
        if (extraForPlayer != null) {
            online.sendMessage(text(extraForPlayer, NamedTextColor.RED));
        }
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
