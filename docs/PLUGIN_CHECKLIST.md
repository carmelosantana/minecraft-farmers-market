# New or Edited Plugin Checklist

Copy this file for one plugin and replace every `<...>` field. Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove inapplicable checks.

- Plugin name: `Farmers Market`
- Slug: `farmers-market`
- Repository: `carmelosantana/minecraft-farmers-market`
- Owner: `Carmelo Santana`
- Target version: `0.1.0`
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `farmers-market.jar`
- External services: `none`
- Status: `active`
- Autonomy: `autonomous`

Gate 1 completed `2026-07-21`. Design spec: `docs/superpowers/specs/2026-07-21-farmers-market-design.md`.

## 1. Scope

- [x] Status is explicitly recorded as active, experimental, or excluded.
- [x] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are defined.
- [x] Known limitations and any intentionally withheld gates are recorded.

### Player-facing purpose

A player-run market economy for `play.xpfarm.org`. Players sell anything they own — blocks,
enchanted gear, custom items from other xpfarm plugins, filled shulker boxes — and buy from each
other. Sellers plant a market vendor, shaped like a Wandering Trader, at their base or rent a stall
in the server market town; the vendor holds their goods and sells them around the clock, including
while the seller is offline. Diamonds are the money. XP pays the fees. Everything is browsable from
a visual menu on both Java and Bedrock, with price charts showing how the item — and the server
economy — is moving.

### Naming chain

Established here; `minecraft-plugin-scaffold` implements and verifies it, and no later gate may
rename a link.

| Link | Value |
|---|---|
| Slug | `farmers-market` |
| Repository | `carmelosantana/minecraft-farmers-market` |
| Maven group | `org.xpfarm` |
| Maven `artifactId` | `farmers-market` |
| Java package | `org.xpfarm.farmersmarket` |
| Releasable JAR | `farmers-market-0.1.0.jar` |
| Updater destination | `farmers-market.jar` |
| `plugin.yml` `name` | `FarmersMarket` |

### Commands

`/market` is the single entry point. Every subcommand has a UI path, and no player action requires
typing a command — the command tree exists for accessibility, admin work, and Java players who
prefer it. Younger players can complete every task through vendors and menus alone.

| Command | Arguments | Who | Purpose |
|---|---|---|---|
| `/market` | — | all | Opens the main market UI (chest GUI on Java, Cumulus form on Bedrock). |
| `/market browse` | `[query]` | all | Global search index across every listing and stall. |
| `/market sell` | — | all | Opens the guided listing flow for the held item. |
| `/market sell` | `<price> [qty]` | all | Fast-path listing of the held item at a unit price. |
| `/market orders` | — | all | The player's own active listings and commodity buy orders. |
| `/market buy` | `<item> <qty> <maxUnitPrice>` | all | Places a commodity buy order against the exchange. |
| `/market balance` | — | all | Diamond balance, XP balance, and pending escrow. |
| `/market deposit` | `[qty]` | all | Moves physical diamonds from inventory into the ledger. |
| `/market withdraw` | `<qty>` | all | Moves ledger diamonds back into physical inventory. |
| `/market chart` | `<item> [7d\|30d]` | all | Issues a rendered map-item price chart for an item. |
| `/market index` | — | all | Server Market Index, its basket, and its trend. |
| `/market vendor place` | `[name]` | all | Places a stationary vendor at the player's location. |
| `/market vendor list` | — | all | The player's vendors, their locations, and rent status. |
| `/market vendor remove` | `<id>` | all | Despawns a vendor and returns escrowed stock. |
| `/market stall rent` | `<stallId> <bid>` | all | Places a sealed weekly bid on a market-town stall. |
| `/market admin reload` | — | admin | Reloads configuration without a server hot reload. |
| `/market admin floor` | `<item> <price>` | admin | Sets or clears the server buy-back floor for an item. |
| `/market admin audit` | `<player\|item> [days]` | admin | Reads the immutable trade log. |
| `/market admin freeze` | `<item>` | admin | Suspends trading on one item pending investigation. |
| `/market admin pot` | — | admin | Community tax-pot balance and disbursement history. |

### Events

| Event | Direction | Why |
|---|---|---|
| `PlayerInteractEntityEvent` | listen, cancellable | Right-click on a market vendor opens our UI. Cancelled so the vanilla trade screen never appears. Right-click only — Geyser left-click is unreliable on Bedrock. |
| `InventoryOpenEvent` | listen, cancellable | Third interception layer: cancels any `InventoryType.MERCHANT` open on a market vendor, in case the first two layers are bypassed. |
| `InventoryClickEvent` | listen, cancellable | Drives the Java chest-GUI renderer. Never distinguishes left from right click, because Geyser cannot. |
| `InventoryCloseEvent` | listen | Releases GUI session state, returns any uncommitted items. |
| `EntitiesLoadEvent` | listen | Respawns PDC-tagged vendors missing after a restart or chunk reload. Chosen over `ChunkLoadEvent`, whose `getEntities()` has documented ordering bugs. |
| `EntityDamageEvent` | listen, cancellable | Defence in depth over `setInvulnerable(true)`; vendors are never damageable. |
| `EntityDeathEvent` | listen | Detects an unexpected vendor death, preserves escrow, and flags for respawn. |
| `PlayerJoinEvent` | listen | Resolves identity and runs the Floodgate account-merge check. Deliberately not an earlier join event — Floodgate `sendForm` throws NPE during the 1.20.2+ configuration phase. |
| `ChunkUnloadEvent` | listen | Bookkeeping only; vendors are `setPersistent(true)` and are never removed here. |
| `MarketTradeEvent` | fire | Custom, public. Announces a completed trade so other xpfarm plugins can react. |
| `MarketListingEvent` | fire | Custom, public. Announces a listing created, cancelled, or expired. |

### Permissions

| Node | Default | Gates |
|---|---|---|
| `farmersmarket.use` | `true` | Browsing, buying, selling, the main UI, deposits and withdrawals. |
| `farmersmarket.vendor.place` | `true` | Placing stationary vendors. |
| `farmersmarket.vendor.limit.<n>` | — | Raises the per-player vendor cap above the config default. |
| `farmersmarket.stall.rent` | `true` | Bidding on market-town stalls. |
| `farmersmarket.chart` | `true` | Requesting rendered map charts. Separable because map rendering has a per-viewer cost. |
| `farmersmarket.admin` | `op` | Parent of every admin node. |
| `farmersmarket.admin.reload` | `op` | Configuration reload. |
| `farmersmarket.admin.floor` | `op` | Editing server buy-back floors. |
| `farmersmarket.admin.audit` | `op` | Reading the immutable trade log. |
| `farmersmarket.admin.freeze` | `op` | Suspending trading on an item. |
| `farmersmarket.admin.pot` | `op` | Viewing and disbursing the community tax pot. |
| `farmersmarket.bypass.fees` | `false` | Waives listing fee and sales tax. Staff and testing only. |
| `farmersmarket.bypass.buylimit` | `false` | Waives rolling per-item buy limits. |

### Configuration

All keys live in `config.yml` and are validated on load; an invalid value logs a warning and falls
back to the documented default rather than failing plugin enable.

| Key | Type | Default | Validation |
|---|---|---|---|
| `economy.listing-fee-percent` | double | `1.0` | `0.0`–`100.0`. Charged in XP at listing, non-refundable. |
| `economy.sales-tax-percent` | double | `7.0` | `0.0`–`100.0`. Charged in diamonds from proceeds. |
| `economy.tax-burn-share` | double | `0.5` | `0.0`–`1.0`. Share of tax destroyed; remainder to the community pot. |
| `economy.xp-per-diamond` | int | `40` | `> 0`. Conversion rate used only to price XP-denominated fees. |
| `economy.listing-duration-days` | int | `14` | `1`–`90`. Expired listings return stock to the seller. |
| `economy.max-listings-per-player` | int | `40` | `> 0`. |
| `liquidity.buyback-enabled` | bool | `true` | — |
| `liquidity.buyback-floors` | map | empty | Item key to diamond price the server always pays. Each entry validated against `liquidity.farm-output-costs`. |
| `liquidity.farm-output-costs` | map | empty | Item key to the estimated diamond cost of producing one unit **via an automated farm**, not by hand. The denominator of the faucet check. An item with a floor but no matching entry fails to load rather than defaulting — a missing estimate is the dangerous case, so it fails closed. |
| `liquidity.farm-output-audit` | bool | `true` | Refuses to load any floor priced at or above its `farm-output-costs` entry. Disabling it logs a prominent warning at enable; it exists for testing, not for production. |
| `limits.buy-limit-enabled` | bool | `true` | — |
| `limits.buy-limit-window-hours` | int | `4` | `1`–`168`. Rolling window, per item, per player. |
| `limits.buy-limits` | map | empty | Item key to max quantity per window. Unlisted items are unlimited. |
| `limits.min-playtime-hours` | int | `2` | `>= 0`. Playtime before market access unlocks; an anti-alt measure. |
| `vendor.max-per-player` | int | `2` | `> 0`. Overridden by `farmersmarket.vendor.limit.<n>`. |
| `vendor.placement-mode` | enum | `owned-and-district` | `owned-and-district`, `district-only`, `anywhere`. |
| `vendor.min-distance-blocks` | int | `24` | `>= 0`. Minimum spacing between vendors of different owners. |
| `vendor.label-enabled` | bool | `true` | Spawns a `TextDisplay` label above each vendor. |
| `stalls.enabled` | bool | `true` | — |
| `stalls.bid-currency` | enum | `xp` | `xp` or `diamonds`. |
| `stalls.bid-period-hours` | int | `168` | `> 0`. Sealed-bid resolution period. |
| `stalls.max-stalls-per-player` | int | `1` | `> 0`. Prevents one player cornering the market town. |
| `ui.bar-glyphs` | enum | `density-4` | `density-4` or `ramp-8`. **The deferred sparkline flip.** `ramp-8` requires resource packs on both platforms; see Known limitations. |
| `ui.charts-enabled` | bool | `true` | Disables all map-item rendering when false. |
| `ui.chart-refresh-seconds` | int | `60` | `>= 10`. Map redraw interval; never per tick. |
| `ui.bedrock-touch-simplify` | bool | `true` | Larger targets and fewer steps for `InputMode.TOUCH` clients. |
| `analytics.index-basket` | list | empty | Item keys in the Laspeyres basket. Empty disables the index. |
| `analytics.outlier-filter-mad` | double | `3.0` | `> 0`. Median-absolute-deviation multiplier for excluding outlier trades from published prices. |
| `analytics.history-retention-days` | int | `365` | `> 0`. Daily rollups are retained; raw trades are never pruned. |
| `storage.sqlite-tmpdir` | string | `plugins/FarmersMarket/tmp` | Must be writable **and executable**. Set explicitly because the Dokploy container may mount `/tmp` `noexec`. |
| `storage.busy-timeout-ms` | int | `5000` | `> 0`. |

### Persistence

SQLite, one file at `plugins/FarmersMarket/market.db`, `journal_mode=WAL`,
`synchronous=NORMAL`, `busy_timeout` from config, `foreign_keys=ON` set per connection.
All access is off the main thread through a single-writer executor; every Bukkit API touch
returns to the main thread. `onDisable` performs a synchronous, bounded flush, because Paper
cancels scheduled tasks at disable and in-flight writes would otherwise be lost.

The JDBC driver is declared through `plugin.yml` `libraries:` rather than relying on Paper's
bundled `org.xerial:sqlite-jdbc`, which Paper's own build file marks as legacy and
"eventually to be removed". If shading is ever chosen instead, `org.sqlite.*` must **not** be
relocated — relocation breaks the driver's reflective and JNI paths.

Tables:

| Table | Shape | Notes |
|---|---|---|
| `accounts` | `uuid PK, diamonds, created_at, updated_at` | Diamonds only. XP is charged against real vanilla XP points, never mirrored here. |
| `account_links` | `floodgate_uuid, java_uuid, merged_at` | The Floodgate link-migration audit trail. |
| `item_keys` | `key_id PK, material, is_commodity, canonical_hash` | Commodity keys are material-only; unique keys hash the serialized form. |
| `item_blobs` | `key_id FK, bytes` | `ItemStack#serializeAsBytes()` output. Chosen over legacy YAML/base64 because it migrates across versions via DataFixerUpper. |
| `listings` | `id PK, seller_uuid, key_id, qty, unit_price, vendor_id, state, created_at, expires_at` | Unique-item board. |
| `buy_orders` | `id PK, buyer_uuid, key_id, qty_remaining, max_unit_price, created_at` | Commodity exchange book. |
| `sell_orders` | `id PK, seller_uuid, key_id, qty_remaining, min_unit_price, created_at` | Commodity exchange book. |
| `trades` | `id PK, buyer, seller, key_id, qty, unit_price, fee_xp, tax_diamonds, tax_burned, tax_pot, ts` | **Append-only. Never updated, never pruned.** The audit trail. |
| `vendors` | `id PK, owner_uuid, world, x, y, z, entity_uuid, kind, stall_id, rent_paid_until` | `entity_uuid` reconciled on `EntitiesLoadEvent`. |
| `stalls` | `id PK, world, x, y, z, tier, current_holder, held_until` | Market-town kiosks. |
| `stall_bids` | `id PK, stall_id, bidder, amount, placed_at, period` | Sealed until resolution; ties break to the earliest bid. |
| `buy_limit_usage` | `uuid, key_id, window_start, qty` | Rolling per-item purchase caps. |
| `price_daily` | `key_id, day, open, high, low, close, volume, vwap` | Outlier-filtered daily rollups. |
| `index_daily` | `day, value, basket_version` | Laspeyres Server Market Index. |
| `community_pot` | `id PK, delta, reason, actor, ts` | Append-only ledger of the tax pot. |

Vendor entities additionally carry a `PersistentDataContainer` tag so a vendor can be recognised
and deduplicated after a restart independently of the database.

### Dependencies

Hard dependencies: **none**. Soft dependencies: **Floodgate**, accessed reflectively and never on
the compile classpath, following the existing `magic-carpet` `EditionResolver` pattern — every
failure in the chain resolves the player to Java edition. `softdepend: [Floodgate]` in `plugin.yml`
for load ordering. **Citizens is deliberately not a dependency**: Geyser issue #6322, "Citizens NPC
shop GUIs never open for Bedrock players", was closed `not planned` in May 2026, which is precisely
this plugin's use case. A real vanilla `WanderingTrader` rides Geyser's most mature code path.
No Vault, no EssentialsX — this plugin owns its ledger, because xpfarm runs no economy plugin.

### External integrations

`none`. No Ollama, no Umami, no outside HTTP of any kind. Nothing in this plugin inherits gate 5's
external-service contract.

### Acceptance checks

Testable pass/fail conditions. These become gate 6 unit tests and gate 7a runtime verification.

**Ledger and correctness**

1. A diamond deposit followed by a withdrawal of the same amount leaves the balance unchanged and the physical diamond count unchanged.
2. A purchase debits the buyer, credits the seller net of tax, burns exactly `tax-burn-share` of the tax, and credits the remainder to the community pot — summing to the gross price with no rounding drift, verified over 10,000 randomized prices.
3. Currency arithmetic uses integer minor units end to end. No `double` ever holds a balance. A price expressed in exponential notation (`1e9`) is rejected at parse, not at use.
4. A trade that fails partway leaves balances, escrow, and stock exactly as they were before it started.
5. Every completed trade writes exactly one `trades` row. Deleting or updating a `trades` row is impossible through any plugin code path.
6. A listing that expires returns the full escrowed stack to the seller with components intact.

**Item identity**

7. An enchanted, renamed, and damaged item round-trips through escrow and returns byte-identical.
8. Two differently-enchanted diamond swords never collide on the same item key.
9. A filled shulker box round-trips with contents intact, and is never rendered as a raw icon in any UI — the browse view shows a placeholder plus a text content summary.
10. A custom item from another xpfarm plugin survives listing, escrow, and purchase across a plugin restart.

**Economy health**

11. No configured buy-back floor is at or above its `liquidity.farm-output-costs` entry, and no floor loads for an item with no matching cost entry. `farm-output-audit` drops each offending entry and warns, naming the item; the surviving map contains only audited floors, and the plugin still enables. The unsafe floor never reaches the economy, which is the property that matters — the plugin does not refuse to start over one bad entry, because a market with one missing floor is better than no market at all.
    - Wording note: an earlier draft of this check said such a floor "fails config load". It does not, and should not — the *entry* is refused, not the whole config. Recorded so a later reader does not mistake the implemented behaviour for a defect.
12. A player exceeding a rolling buy limit is refused, and the refusal does not consume currency or stock.
13. XP fees are charged against real XP points and cannot be transferred between players by any code path.
14. Published prices exclude trades beyond `outlier-filter-mad`; a single 100× outlier trade does not move the published price.

**Shutdown and durability**

Added `2026-07-22` after the M1 whole-branch review found a money-destruction path here that no
per-task review could see, because it spans the command layer, the executor, and the plugin
lifecycle. The plan calls the bounded shutdown flush "a correctness requirement, not politeness";
nothing in this section previously held it to that.

23. `DatabaseExecutor.close()` drains queued writes rather than abandoning them, and its warning distinguishes work that was queued-and-dropped from work that was still executing when it gave up.
24. `onDisable` closes the executor before the database, so no write is running when the connection closes.
25. A write that commits during shutdown — after Paper has already set `isEnabled = false` but while `onDisable`'s flush is still running — leaves a log line naming the player, the operation, and the amount. This window is **recorded, not eliminated**: the debit commits and the items are never handed over, so the log line is the only thing making it reconcilable. Verified at gate 7a, not by unit test.

**Cross-platform — gate 7a, on the Legendary stack**

15. A Java player and a Bedrock player each complete the full loop — list, browse, buy, withdraw — with no command typed.
16. Right-clicking a vendor as a Bedrock player opens the Cumulus form. The vanilla trade screen never appears on either platform.
17. No Bedrock code path opens a chest GUI from a form callback, verified by inspection and by a Bedrock player retaining inventory access after every UI flow.
18. No UI action anywhere depends on distinguishing left from right click.
19. A rendered map chart is legible and identical in content on Java and Bedrock.
20. Vendor `TextDisplay` labels are legible on Bedrock as plain nametag text, with no reliance on a background panel.
21. A vendor survives a server restart, a chunk unload/reload cycle, and an explosion adjacent to it, and cannot be pushed by a player.
22. An unlinked Bedrock player who then links a Java account retains their balance, listings, vendors, and trade history.

### Known limitations

Deliberately out of scope for `0.1.0`. **No lifecycle gates are withheld** — this plugin is `active`
and runs gates 1 through 12 in full.

- **Wandering vendors are v2.** `0.1.0` ships stationary vendors only. The agreed v2 behaviour is that a vendor periodically relocates to another player's market area or the market town, carrying its listings to that foot traffic, with the owner told where it went. The `vendors.kind` column exists in `0.1.0` so the schema does not change.
- **The stock-market layer is conceptual.** `0.1.0` ships read-only analytics: per-item OHLCV history, moving averages, a Laspeyres basket index, and "your price vs. server average". No tradeable securities, shares, dividends, lending, or player-facing banks — ever, not merely not yet. EVE's Phaser Inc. Ponzi took ~1.8 trillion ISK from 4,000+ investors; one such incident on a server of fifteen ends the community.
- **Sparklines are four density levels, not eight.** Bedrock's font has no Unicode fallback and its glyph sheet is CP437-derived; CP437 does not contain `U+2581`–`U+2587`, so `▁▂▃▄▅▆▇` is assumed broken on Bedrock **by inference, not by observation**. `0.1.0` uses `█▓▒░` plus box-drawing inline, and map items for real charts. Glyph selection sits behind a `BarGlyphs` strategy interface switched by `ui.bar-glyphs`, so enabling `ramp-8` in v2 is a config flip with no call-site changes. Enabling it requires **two** hand-authored resource packs — Java `minecraft:font` providers and a Bedrock glyph-sheet override served by Geyser — which will not render identically, since Bedrock's fixed 16×16 grid is strictly less capable than Java's variable-width providers. Java additionally has a pack-declined failure state that Bedrock does not. No precedent was found of any server doing this for charting.
- **Five questions the research could not settle** must be answered empirically at gate 7a before the affected behaviour is trusted. Recorded here so gate 4 does not bake in a guess:
  1. Do `U+2581`–`U+2587` render on a real Bedrock client? Cheap to test, and a positive result yields eight-level sparklines on both platforms with no resource pack.
  2. Every Paper API signature cited in this design was verified against 1.21.x javadocs. **No consulted source used the `26.1` version scheme.** Confirm against the actual Paper 26.1.2 build 74 JAR at gate 4.
  3. Does clicking an `Interaction` entity as a Bedrock player actually fire `PlayerInteractEntityEvent`? Geyser's source path says yes; unobserved on a live server.
  4. Does cancelling `PlayerInteractEntityEvent` block the merchant GUI on this Paper build? Evidence says yes — the community lore claiming otherwise cites three closed tickets about a *different* event — but no modern positive confirmation was found. Three interception layers ship regardless.
  5. What is the practical `SimpleForm` button ceiling before a Bedrock client degrades? Cumulus enforces none and GeyserMC documents none. Pagination is hand-rolled against a conservative assumed limit until measured.
- **`ItemDisplay` and `BlockDisplay` are unusable.** Neither is registered in Geyser's `VanillaEntities`; Bedrock players see nothing at all. No vendor visual may be built on them. `TextDisplay` works but is emulated as a nametag — no background panel, no alignment, no `setSeeThrough`.
- **Seasonal economy reset is not implemented and not scheduled.** Recorded as the reliable inflation cure if the diamond supply outruns its sinks. It is only unfair when it is a surprise, so it would be announced well ahead.
- **Anti-alt enforcement is social, not technical.** `limits.min-playtime-hours` raises the cost of a throwaway alt but does not defeat a determined one. At fifteen mostly-known players this is acceptable; it would not be on a large public server.
- **No admin web dashboard.** The monthly economic report is a manual read of `/market admin audit` in `0.1.0`.

## 2. Repository

Gate 2 complete `2026-07-21`. Local repository initialized on `main`; ten files tracked, `target/`
ignored; commits `57b8f3f` and `1df4e6c`, authored `Carmelo Santana <me@carmelosantana.com>`.

- [x] Repository is `carmelosantana/minecraft-farmers-market` with an SSH `origin` and `main` branch. `origin` is `git@github.com:carmelosantana/minecraft-farmers-market.git`; `git status --short --branch` reports `## main...origin/main` with no divergence. The repository was created by the operator rather than by the agent: `gh repo create` was refused by the workstation's Bash permission classifier — a local tooling gate, not a pipeline failure and not an autonomy question, since autonomous mode had already authorized the action in writing at gate 1. The agent performed the remote add and push.
- [x] Existing user-owned worktree changes were identified and preserved. The directory was empty at gate 1 preflight and was not a git repository; nothing user-owned existed to preserve.
- [x] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation. `rg -n 'herobrinesystems' . --hidden -g '!target/**' -g '!.git/**'` returns exactly one hit: this checklist's own verification checkbox on the line above, which is the standard template wording carried by every plugin repository. No match in any source, metadata, workflow, remote, or prose.

## 3. Metadata

Gate 3 complete `2026-07-21`.

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent. Full 661-line AGPL-3.0 text; `pom.xml` `<licenses>` names "GNU Affero General Public License v3.0 or later" pointing at `https://www.gnu.org/licenses/agpl-3.0.html`.
- [x] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present. `pom.xml` `<url>` and `<developers>`; `plugin.yml` `author:` and `website:`.
- [x] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented. `README.md`, line 6.
- [x] New work uses the `org.xpfarm` Maven group. `org.xpfarm:farmers-market:0.1.0`. No existing-coordinate carve-out was needed or taken.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent. Verified: project `<artifactId>farmers-market</artifactId>` (the coordinate directly under `<project>`; the other `<artifactId>` matches are dependency and build-plugin coordinates), `plugin.yml` `name: FarmersMarket`, built JAR `target/farmers-market-0.1.0.jar`, updater destination `farmers-market.jar`.
- [x] No secrets committed in source, defaults, tests, logs, history, or documentation. `config.yml` ships empty maps and local paths only; no credentials, tokens, endpoints, or production configuration anywhere in the tree or the single commit.

## 4. Compatibility

Gate 4 complete `2026-07-22` for milestone M1 (`0.1.0`).

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'`. Built with Temurin 25.0.3 and Maven 3.9.16; the embedded descriptor in the shaded JAR was inspected and shows `api-version: '26.1'` and `version: '0.1.0'` (Maven filtering resolved). `PluginDescriptorTest` asserts the `String` type of `api-version`, so an unquoted value that would parse as a double cannot ship.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared. **No hard dependencies.** Soft: `Floodgate`, declared in `plugin.yml` `softdepend` for load ordering only and never on the compile classpath. Runtime library: `org.xerial:sqlite-jdbc:3.53.2.0` via `plugin.yml` `libraries:`, `provided` scope in the POM so it is never shaded. Observed loading correctly at gate 7a: `[SpigotLibraryLoader] [FarmersMarket] Loaded library .../sqlite-jdbc-3.53.2.0.jar`. `PluginDescriptorTest` asserts the `libraries:` coordinate carries the same version as `pom.xml`, so the two cannot drift.
- [x] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior. **Input:** M1 is command-only; no UI, inventory GUI, or form exists yet (M3). **Output:** all player-facing text is plain legacy-coloured chat — no hover events, click events, hex/RGB, gradients, strikethrough, or underline, each of which Geyser strips or downgrades. Enforced by a test that scans the *compiled class bytes* of the whole `command` package for the UTF-8 encodings of `§` and `U+2581`–`U+2587`; a source-text scan was tried first and rejected because Java decodes `\uXXXX` in the lexer, making an escaped glyph invisible in source and present in the compiled string. **Identity:** everything is keyed on UUID, never username, because Floodgate's username prefix is config-mutable. The Floodgate→Java UUID account merge is implemented and unit-tested; see §7 for what remains unverified about it. **Protocol:** the plugin reads no protocol version and makes no client-version assumption, so ViaVersion-bridged clients are unaffected.

## 5. External services

Gate 5 is satisfied by there being nothing to satisfy it. This plugin makes **zero** outbound
network calls — no Ollama, no Umami, no HTTP client, no telemetry, no outside endpoint of any kind.
The Global Constraints forbade adding one, and the whole-branch review confirmed none was added.

- [x] External integrations are disabled by default or require explicit configuration and have bounded timeouts. Vacuously true: there are no external integrations. The only I/O is a local SQLite file.
- [x] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable. Not applicable — none exist.
- [x] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets. No endpoint exists to fail. On secrets: `rg` across the tree and the shipped `config.yml` inside the JAR found no credentials, tokens, or endpoints; every match was prose. The plugin logs player UUIDs and diamond amounts for reconciliation, which is operational data rather than a secret, and never logs a username.

## 6. Tests and build

- [ ] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable.
- [ ] `PluginDescriptorTest` parses `plugin.yml` and `config.yml` with SnakeYAML and asserts `name`, `main`, a `String`-typed `api-version`, a fully-substituted `version`, every command the code looks up, every permission the code checks, and the declared soft dependencies.
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds. `BUILD SUCCESS`, **173 tests, 0 failures, 0 errors, 0 skipped**, on `2026-07-22` at commit `fec0b81`.
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded. Exactly one non-`original-*` JAR, `target/farmers-market-0.1.0.jar`. Embedded descriptor verified: `name`, `main`, `api-version: '26.1'`, fully-substituted `version: '0.1.0'`, the `market` command, all three permission nodes, `softdepend`, and `libraries`. **Shading review:** the JAR bundles nothing — `org/bukkit`, `io/papermc`, `org/sqlite`, `org/yaml`, and `org/junit` each return 0 entries, so no server API and no runtime-loaded library leaked in.

### Test-strength discipline

Worth recording because it changed the outcome. **Four tests on this branch were caught passing
while asserting nothing about the mechanism they named**, and two of those were written into the
implementation plan by the planner rather than by an implementer. A green count is not evidence
that assertions bite.

From Task 4 onward every task mutation-checked its own guard tests — delete the mechanism the test
names, confirm the test actually fails. That discipline found: the `Error`-skips-rollback money
destruction path, the hollow self-transfer guard, a non-deterministic flush test that would have
passed with `awaitTermination` deleted, `applyTo`'s idempotency being untested (the SQL's
`IF NOT EXISTS` was doing the work), the `org.sqlite.tmpdir` property never being asserted, and
every boolean config default being unpinned — including `farm-output-audit`, which **is** the
faucet safety switch. One glyph-guard rewrite was itself caught by the check before it shipped.

M2 should keep this. It is the single highest-yield practice this milestone adopted.

## 7. Matrix

### 7a — single-plugin runtime verification: PASSED `2026-07-22`

Disposable fresh-volume Legendary stack, slot 0, project
`xpfarm-plugin-test-farmers-market-b44fbbfb`. Booted from `target/farmers-market-0.1.0.jar` at
commit `fec0b81`, verified, and torn down; slot lease released.

**Startup.** Paper logged its own `Done (15.368s)! For help`. The Java port answered a real
Minecraft protocol handshake — `Paper 26.1.2 | protocol 775` — not merely a TCP connect. RCON
`plugins` listed **four plugins, all green**: `FarmersMarket`, `floodgate`, `Geyser-Spigot`
(2.11.0-SNAPSHOT), `ViaVersion` (5.11.0). The whole cross-play stack starts together.

**Enable path.** `[SpigotLibraryLoader] Loaded library .../sqlite-jdbc-3.53.2.0.jar` →
`Loading server plugin FarmersMarket v0.1.0` → `database ready at plugins/FarmersMarket/market.db
(schema version 1)` → `FarmersMarket enabled.` The data folder contains `market.db`,
`market.db-wal`, and `market.db-shm`, confirming WAL is genuinely active on the real server, plus
the `tmp/` directory proving `org.sqlite.tmpdir` was created before the driver loaded — the noexec
container hazard the design called out.

**Commands over RCON.** `/market`, `/market balance`, `/market deposit`, and `/market withdraw 5`
from the console each replied with `'<sub>' needs a player with an inventory, so the console cannot
run it. Console can run /market reload.` — a clear refusal, **no `ClassCastException`**.
`/market reload` returned `Farmers Market configuration reloaded.` No server-wide plugin hot reload
was used at any point.

**Configuration validation and the faucet audit, end to end.** A deliberately unsafe `config.yml`
was written into the running container and reloaded. All four expected warnings fired and the
plugin stayed enabled:

- `economy.sales-tax-percent` `150.0` → out-of-range, defaulted to `7.0`.
- `liquidity.buyback-floors` `DIAMOND` `10.0` against a cost of `10.0` → **refused**, *"this would be an infinite money faucet."*
- `liquidity.buyback-floors` `GOLD_INGOT` with no cost entry → **refused fail-closed**, *"an unestimated item is the dangerous case."*
- `analytics.index-basket: 42` → wrong-typed container key, warned and defaulted to `[]`.
- `IRON_INGOT` at `1.0` against a cost of `4.0` → loaded silently, correctly.

This is acceptance check 11 demonstrated on a real server, not merely unit-tested.

**Shutdown.** Graceful `stop`: `Disabling FarmersMarket v0.1.0` with no abandoned-task warning
(nothing was queued) and no exception. **Zero exceptions, stack traces, or `SEVERE` lines appeared
anywhere in the entire run**, startup through shutdown.

### What gate 7a could NOT reach — carried to the gate 12 play-test

No client attaches to this stack by design, and no RCON test-harness plugin exists yet, so
`rcon` proves a command ran, not that an event fired. Named here so `minecraft-plugin-handoff`
carries them forward as real obligations rather than silence:

1. **Every player-path command.** `balance`, `deposit`, and `withdraw` are console-refused by design, so their *success* paths — the entire inventory-movement surface — ran zero times. Acceptance check 1 (deposit then withdraw conserves both balance and physical diamond count) is unverified on its physical half.
2. **The compensation paths.** Both reviewers demanded this specifically: force a ledger failure *during* `deliver` (e.g. `chmod` the database file after the debit lands) rather than accepting a happy-path withdraw. The unknown-outcome policy that prevents minting lives exactly where no unit test can reach.
3. **The shutdown reconciliation line** (check 25). Run `/market withdraw 64`, stop the server inside the window, and confirm the log line names the player, the operation, **and** the amount. The window is recorded, not eliminated, so that line is the only thing making a committed-but-undelivered withdrawal reconcilable.
4. **The Floodgate account merge** (check 22) — **the highest-value open question.** Confirm `isFloodgatePlayer(javaUuid)` returns true for an **already-linked** player. If it returns false, the join guard short-circuits and the entire merge feature is dead code that fails silently — the exact outcome the merge exists to prevent. Floodgate is deliberately off the test classpath, so this cannot be settled statically.
5. **Merge idempotence across repeated joins**, since the merge runs on every join.
6. **`LinkageError` handling in `EditionResolver`** — reasoned and implemented, but reproducing a raw linkage failure needs a bytecode fixture or custom classloader, both excluded by the no-new-dependency constraint. Unverified, not blocking.

### 7b — full-roster matrix

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers every updater-managed plugin.
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
- [ ] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
- [ ] Affected commands, permissions, persistence, and configuration reload were exercised over RCON with no server-wide hot reload.
- [ ] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.

## 8. CI/CD

Gate 8a complete `2026-07-21`. Gate 8b belongs to `minecraft-plugin-release`.

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior. `.github/workflows/build.yml` was copied from `timber-blast` and verified **byte-identical** by `diff`, so it carries the corrected `SHA256SUMS.txt` generation that records bare filenames rather than `target/`-prefixed paths.
- [ ] Successful main Actions run is recorded before tagging. Not this skill's box to tick — `minecraft-plugin-release` records it at gate 8b, against the commit actually being tagged. Evidence available for it: run `29886516873` on commit `1df4e6c`, `completed/success`, observed `2026-07-22`. That run built a scaffold with no production sources, so it is not evidence about the plugin; it only establishes that the workflow itself is sound.
- [x] Workflow permissions contain no broader access than the documented contract. `permissions: contents: write`, and nothing else.

## 9. Release

- [ ] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
- [ ] Successful tag Actions run and GitHub release are recorded.
- [ ] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR.
- [ ] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`.

## 10. Updater

- [ ] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin.
- [ ] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass.
- [ ] Updater dry-run uses a disposable directory and never a production plugin directory.
- [ ] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.

## 11. Deployment

Gate 11 is operator-mediated — the agent prepares and verifies, the operator triggers. Leaving
these unticked with a note that the redeployment is pending the operator is an accurate resting
state, not a failure.

- [ ] Full Dokploy redeployment/recreation was performed by the operator (not a container restart), and the recreation used is noted.
- [ ] Operator-relayed evidence was verified: `plugin-updater` exit `0`, Minecraft started after it, each covered plugin's updater line, and clean enable lines for Paper/Geyser/Floodgate/ViaVersion and every covered plugin.
- [ ] No production plugin hot reload was used.

## 12. Handoff

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local pending state.
- [ ] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and follow-up owner are recorded.
- [ ] Evidence distinguishes source commit, published tag/release, updater state, and deployed state without exposing secrets.
- [ ] Client play-test obligation recorded with a named owner and a target date: `<owner>` / `<date>`.
- [ ] Client play-test outcome recorded once performed, covering Java join, Bedrock join, and any form, inventory, or rendered item behavior this plugin introduces. Leave unchecked with the owner and date above until the team has run it; an unchecked box here does not block a release, but an unrecorded obligation is a gate 12 failure.
- [ ] Public deployment reachability confirmed during that pass: `play.xpfarm.org` reaches the intended Java and Bedrock entry points.

---

## Milestone status

`0.1.0` is **M1 of five**. The v1 scope recorded in §1 above is delivered across five milestones,
each running its own gates 4-7a and its own release. This was decided at gate 4 after a scope check
measured the single-plan build at 3-4x the largest existing plugin in the ecosystem (Timber Blast,
7k lines / 56 files) and the writing-plans scope check called for splitting it.

| Milestone | Scope | State |
|---|---|---|
| **M1** `0.1.0` | config, storage, identity, ledger, `/market` balance/deposit/withdraw/reload | **complete** — gates 4, 5, 6, 7a passed |
| M2 `0.2.0` | listings, item identity, commodity matching, escrow, fees/tax, the immutable trade log | not started |
| M3 `0.3.0` | cross-platform UI — Java chest GUI, Bedrock Cumulus forms | not started |
| M4 `0.4.0` | vendors, `TextDisplay` labels, stalls, sealed-bid rent | not started |
| M5 `0.5.0` | price history, basket index, map-item charts | not started |

The second reason for the split matters more than the size one: **five of the design's decisions
are unverified assumptions about Geyser behaviour** (§1 Known limitations). A single-plan build
would have stacked ~25k lines on top of them before any was tested against a real Bedrock client.
M1 and M2 carry zero Bedrock risk and can be built with full confidence; M3 gets built on measured
ground.

### M2 entry conditions

Do these before M2 adds its first config key or schema change, while nothing has copied the current
shape yet:

- Extract `ConfigValidator` from `FmConfig` to match the sibling plugin's shape. M2-M5 will copy
  whichever shape M1 sets, which is why this is an entry condition rather than a Minor.
- Add `AccountDao.upsertAccount(AccountRow)` and `AccountDao.findLink(UUID)`. The first makes
  `AccountMerge`'s computed timestamps actually persist — today only `into.uuid()` is used, so all
  four `AccountMergeTest` tests pin behaviour no production path observes. The second replaces an
  O(n) full-table scan on every player join.
- Give `Ledger` a typed pre-write-failure reason. Today a `SELECT` failure before any `UPDATE` is
  indistinguishable from an unknown outcome, so `deposit` refuses to return items it could safely
  return.
- Move `MarketCommand.LOG` to the plugin logger, and reattach the two doc/wording items already
  fixed in `fec0b81`.
- Keep the mutation-check discipline. It is the highest-yield practice M1 adopted.
