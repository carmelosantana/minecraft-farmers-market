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

- [ ] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'`, matching the API compiled against (see `PLUGIN_LIFECYCLE.md` §4 — a lower value opts the JAR into Paper's `Commodore` bytecode rewrites).
- [ ] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared.
- [ ] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior.

## 5. External services

- [ ] External integrations are disabled by default or require explicit configuration and have bounded timeouts.
- [ ] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable.
- [ ] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets.

## 6. Tests and build

- [ ] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable.
- [ ] `PluginDescriptorTest` parses `plugin.yml` and `config.yml` with SnakeYAML and asserts `name`, `main`, a `String`-typed `api-version`, a fully-substituted `version`, every command the code looks up, every permission the code checks, and the declared soft dependencies.
- [ ] `mvn --batch-mode --no-transfer-progress clean verify` succeeds.
- [ ] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded.

Both boxes deliberately left unchecked. A scaffold-only build **was** run on `2026-07-21` as a
pre-push sanity check — `BUILD SUCCESS`, 7/7 `PluginDescriptorTest` assertions passing, Temurin
25.0.3 and Maven 3.9.16, producing `target/farmers-market-0.1.0.jar` alongside
`target/original-farmers-market-0.1.0.jar`, with the embedded descriptor confirmed to carry
`version: '0.1.0'` (Maven filtering resolved) and `api-version: '26.1'`. That evidence is recorded
here only to show the push would not have produced a red `main` run. **It is not gate 6.** The
plugin has no main class yet, so the build compiled zero production sources and the descriptor test
asserted against a `main:` class that does not exist. `minecraft-plugin-dev` ticks these against
real code.

## 7. Matrix

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
