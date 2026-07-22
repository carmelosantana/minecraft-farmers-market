/*
 * FarmersMarket - plugin lifecycle: opens the database, wires the ledger, owns the command.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.farmersmarket;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import org.xpfarm.farmersmarket.command.MarketCommand;
import org.xpfarm.farmersmarket.config.BukkitConfigSource;
import org.xpfarm.farmersmarket.config.FmConfig;
import org.xpfarm.farmersmarket.identity.EditionResolver;
import org.xpfarm.farmersmarket.ledger.Ledger;
import org.xpfarm.farmersmarket.storage.AccountDao;
import org.xpfarm.farmersmarket.storage.Database;
import org.xpfarm.farmersmarket.storage.DatabaseExecutor;
import org.xpfarm.farmersmarket.storage.Migrations;

/**
 * Plugin entry point: loads the configuration, opens the database, runs the migrations, wires
 * the ledger, registers {@code /market}, and merges a Bedrock player's balance into their Java
 * account when they link one.
 *
 * <h2>Startup fails closed</h2>
 *
 * <p>Deliberately the opposite of the sibling {@code TimberBlast} plugin, whose every wiring
 * step degrades to a warning. That is right for a plugin whose worst failure is an axe that
 * does not fell trees. It is wrong here: every command in this plugin moves money through the
 * database, so a plugin that enables with a dead database is a plugin that throws on every
 * command a player runs and answers {@code /market balance} with a stack trace. Refusing to
 * enable at all is the honest failure, and it is the one an operator notices.
 *
 * <p>{@link Migrations#applyTo} throwing because the recorded schema version is newer than this
 * build knows is the sharpest case of that rule: an older jar has just opened a database a newer
 * jar migrated, and the only safe thing it can do is not run.
 *
 * <h2>Shutdown drains before it closes</h2>
 *
 * <p>{@link #onDisable} closes {@link DatabaseExecutor} before {@link Database}, in that order
 * and never the other way round. Paper cancels every scheduled plugin task at disable, so a
 * write still queued at that moment is lost unless the executor's bounded flush drains it first
 * -- and it cannot drain onto a connection that has already been closed.
 *
 * @see MarketCommand for the command tree and the rules that keep a partial failure from
 *      costing a player diamonds
 */
public final class FarmersMarketPlugin extends JavaPlugin implements Listener {

    /** The SQLite file inside the plugin's data folder. */
    private static final String DATABASE_FILE = "market.db";

    /**
     * The live configuration snapshot. Held in an {@link AtomicReference} because
     * {@code /market reload} replaces it wholesale from the main thread while other threads may
     * be reading it, and a torn read of a half-swapped configuration is not worth the risk of
     * saving one field.
     */
    private final AtomicReference<FmConfig> config = new AtomicReference<>();

    private Database database;
    private DatabaseExecutor executor;
    private Ledger ledger;
    private EditionResolver editions;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config.set(loadConfig(this::warn));

        Path databaseFile = getDataFolder().toPath().resolve(DATABASE_FILE);

        // Names whatever is being attempted right now, so the failure below can say where it
        // died instead of just that it did.
        String stage = "opening its database at " + databaseFile;
        try {
            Files.createDirectories(getDataFolder().toPath());
            String tmpdir = resolveTmpdir(config.get().sqliteTmpdir());
            database = Database.open(databaseFile, tmpdir, config.get().busyTimeoutMs());
            int schemaVersion = Migrations.applyTo(database.connection());
            getLogger().info("FarmersMarket: database ready at " + databaseFile
                    + " (schema version " + schemaVersion + ").");

            stage = "starting its database writer thread";
            executor = new DatabaseExecutor();
            ledger = new Ledger(database, new AccountDao(database), executor);

            // Inside the try, not after it. EditionResolver.create catches
            // ReflectiveOperationException and RuntimeException but not Error, so a
            // NoClassDefFoundError from a half-installed Floodgate escapes it -- and by this
            // point JavaPlugin has already flipped itself to enabled, which would leave the
            // plugin nominally running with an open database, no command executor, and no
            // listener. The same argument covers every step below.
            stage = "detecting Bedrock players through Floodgate";
            editions = EditionResolver.create(getLogger());

            stage = "registering the /market command";
            PluginCommand command = getCommand("market");
            if (command == null) {
                throw new IllegalStateException("the 'market' command is missing from plugin.yml, "
                        + "so there is no way to reach anything this plugin does");
            }
            MarketCommand market = new MarketCommand(this, ledger, this::reload);
            command.setExecutor(market);
            command.setTabCompleter(market);

            stage = "registering its player-join listener";
            getServer().getPluginManager().registerEvents(this, this);
        } catch (Throwable t) {
            // Throwable, not Exception: an UnsatisfiedLinkError from sqlite-jdbc failing to
            // extract its native library into a noexec tmpdir is an Error, and it is the single
            // most likely way this fails in a container.
            failToEnable(stage, t);
            return;
        }

        getLogger().info("FarmersMarket enabled.");
    }

    @Override
    public void onDisable() {
        // Order is load-bearing and both guards matter: a failed enable leaves one or both of
        // these null, and an NPE here would bury whatever the real startup failure was.
        if (executor != null) {
            // Drains the queue synchronously. Paper cancels scheduled plugin tasks at disable,
            // so anything still queued is lost if this does not run first.
            executor.close();
            executor = null;
        }
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "FarmersMarket: the database did not close "
                        + "cleanly. Queued writes were already flushed.", e);
            }
            database = null;
        }
        ledger = null;
        editions = null;
    }

    /**
     * Re-reads {@code config.yml} and swaps the live snapshot for the result.
     *
     * <p>Invoked by {@code /market reload}, and deliberately not a server-wide plugin reload:
     * nothing is unregistered, nothing is re-registered, and no listener is added a second time.
     *
     * <p><b>Storage settings are not re-applied.</b> {@code storage.sqlite-tmpdir} and
     * {@code storage.busy-timeout-ms} are consumed once, when the connection is opened, and a
     * reload cannot move an open SQLite connection to a different tmpdir. Changing either takes
     * a server restart. The values are still re-validated here, so an operator who typed one
     * wrong is told about it now rather than at the next restart.
     *
     * @return the validation warnings this reload produced, in order; empty when the file was
     *         clean. The command reports these to the sender, so an operator fixing a typo sees
     *         the result in chat instead of having to open the server log.
     */
    public List<String> reload() {
        List<String> warnings = new ArrayList<>();
        reloadConfig();
        config.set(loadConfig(warning -> {
            warnings.add(warning);
            getLogger().warning(warning);
        }));
        return List.copyOf(warnings);
    }

    /**
     * The live configuration.
     *
     * @return the current snapshot; never {@code null} once {@link #onEnable} has started
     */
    public FmConfig config() {
        return config.get();
    }

    /**
     * Folds a linking Bedrock player's stranded balance into the Java account they linked.
     *
     * <p>Runs at {@link EventPriority#MONITOR} because it changes nothing about the join itself
     * and has no opinion on whether another plugin cancels something first.
     *
     * <p><b>No forms and no messages are sent from here.</b> Floodgate #605 throws a
     * {@link NullPointerException} when a form is sent during the 1.20.2+ configuration phase,
     * which a join listener is close enough to for it to matter. The merge is silent to the
     * player and logged for the operator; {@link Ledger#mergeAccounts} is idempotent by its link
     * row, so this running on every subsequent login is a no-op rather than a double credit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (ledger == null || editions == null) {
            return;
        }
        UUID current = event.getPlayer().getUniqueId();
        if (!editions.isBedrock(current)) {
            return;
        }
        Optional<UUID> beforeLinking = editions.linkedBedrockUuid(current);
        if (beforeLinking.isEmpty() || beforeLinking.get().equals(current)) {
            return;
        }

        UUID floodgateUuid = beforeLinking.get();
        ledger.mergeAccounts(floodgateUuid, current, System.currentTimeMillis())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        getLogger().log(Level.WARNING, "FarmersMarket: could not merge the "
                                + "pre-link balance of " + floodgateUuid + " into " + current
                                + ". The balance is still held under the old UUID and no diamonds "
                                + "were lost; retry happens on their next login.", failure);
                    }
                });
    }

    // ---- wiring helpers -----------------------------------------------------------------

    private FmConfig loadConfig(java.util.function.Consumer<String> warn) {
        return FmConfig.load(new BukkitConfigSource(getConfig(), warn), warn);
    }

    /**
     * Resolves {@code storage.sqlite-tmpdir} against the server root when it is relative.
     *
     * <p>The default, {@code plugins/FarmersMarket/tmp}, is written relative to the server root
     * rather than to the plugin's data folder, and resolving it against the wrong base would put
     * the SQLite native library somewhere an operator never looks. An absolute value is taken
     * exactly as given -- that is the whole point of setting one, since the reason to set it at
     * all is a container whose {@code /tmp} is mounted {@code noexec}.
     */
    private String resolveTmpdir(String configured) {
        try {
            Path path = Path.of(configured);
            return path.isAbsolute() ? path.toString() : serverRoot().resolve(path).toString();
        } catch (InvalidPathException e) {
            warn("FarmersMarket config: storage.sqlite-tmpdir is not a usable path ('" + configured
                    + "'); using '" + FmConfig.DEFAULT_SQLITE_TMPDIR + "' instead.");
            return serverRoot().resolve(FmConfig.DEFAULT_SQLITE_TMPDIR).toString();
        }
    }

    /**
     * The server root: the directory holding {@code plugins/}, which is {@code getDataFolder()}
     * two levels up. Falls back to the JVM working directory, which is where a Paper server is
     * started from, if the folder shape is ever anything else.
     */
    private Path serverRoot() {
        File pluginsDirectory = getDataFolder().getParentFile();
        File root = pluginsDirectory == null ? null : pluginsDirectory.getParentFile();
        return (root == null ? Path.of("") : root.toPath()).toAbsolutePath().normalize();
    }

    /**
     * Logs which startup stage failed, closes whatever was already opened, and disables the
     * plugin.
     *
     * <p>{@link #onDisable} is idempotent and null-guarded, so closing here and having Paper call
     * {@code onDisable} again on the way out is safe rather than a double close.
     *
     * @param stage what was being attempted, phrased to follow "failed while"
     * @param cause the failure; a {@link Throwable} rather than an {@link Exception} because an
     *              {@code UnsatisfiedLinkError} or {@code NoClassDefFoundError} is exactly the
     *              kind of thing that reaches here
     */
    private void failToEnable(String stage, Throwable cause) {
        getLogger().log(Level.SEVERE, "FarmersMarket: failed while " + stage + ". Refusing to "
                + "enable -- every command this plugin has moves diamonds through its database, "
                + "and a plugin that enables half-wired answers those commands with a stack "
                + "trace instead.", cause);
        onDisable();
        getServer().getPluginManager().disablePlugin(this);
    }

    private void warn(String message) {
        getLogger().warning(message);
    }
}
