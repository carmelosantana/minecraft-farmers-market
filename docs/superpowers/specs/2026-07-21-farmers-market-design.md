# Farmers Market — Design

Date: 2026-07-21
Status: approved, pre-implementation
Lifecycle gate: 1 (planning). Checklist: `docs/PLUGIN_CHECKLIST.md`.

This document records *why* the design is shaped the way it is. The checklist records *what* gets
built and how it will be verified. Where the two overlap, the checklist is authoritative.

---

## 1. Problem

`play.xpfarm.org` has no economy. Players who accumulate goods have no way to convert them into
anything another player wants, and no way to discover what anything is worth. The server runs
vanilla Paper plus our own plugins — no Vault, no EssentialsX, no claims plugin — so this plugin
owns the ledger outright and defines its own placement rules.

The server has **under fifteen regulars**, includes **younger players**, is **semi-public**, and is
played by **people who mostly know each other**. Those four facts drive nearly every decision below.

## 2. The finding that shaped the design

The obvious references — Path of Exile and Elder Scrolls Online — both deliberately add *friction*
to trade. Copying that would have been the single largest available mistake.

PoE and ESO add friction because at their scale the market is **too** efficient: it out-competes
the gameplay loop it sits on top of. Chris Wilson's 2017 Trade Manifesto is explicit that the
remaining frustration is the only thing keeping trades from being instant, and that this is
intentional.

At fifteen players the problem inverts. Thin markets do not discover prices; they sit dead. Wide
spreads, no volume, and — critically — a very low capital threshold for one actor to capture the
entire market. Analysis of ~6M Lineage transactions found old, low-participant servers consolidate
into winner-takes-all monopolies. On a fifteen-player Minecraft server, one person with an iron farm
*is* the market.

The correct precedent is not PoE. It is **WoW patch 9.2.7**, which moved commodities to region-wide
pools specifically to rescue dead low-population realms while leaving gear realm-local. A
fifteen-player server is a dead low-pop realm.

Two corroborating updates matter:

- **GGG reversed course.** PoE1 3.25 added a currency exchange; PoE2 0.3 added fully asynchronous,
  offline-capable, first-buyer-wins item trade with a gold fee. After eight years of defending
  friction on principle, the most friction-committed studio in the genre shipped an auction house in
  all but name — while keeping a **non-tradeable transaction currency** for fees. That shape is
  worth copying; the 2017 rhetoric is not.
- **Diablo III closed its auction houses** in March 2014 because "players started playing the
  auction house and not the game." The generalizable lesson is not that auction houses are bad. It
  is that *an efficient market for the primary reward of your core loop will out-compete that loop,
  with zero cheating required.* An honest, well-functioning market is itself the failure mode.

**Design position: take ESO's fee structure and physical-vendor charm, take WoW's instant buyout,
take RuneScape's buy limits and tax-funded item sink, and reject PoE's friction entirely.**

## 3. Currency

Diamonds and XP get **distinct, non-overlapping roles**, and each gets its own sink.

| | Diamonds | XP |
|---|---|---|
| Role | Store of value, trade medium | Transaction currency |
| Tradeable between players | Yes | **No** |
| Held where | Plugin ledger, deposit/withdraw physical diamonds | Real vanilla XP points, no mirror ledger |
| Sink | 7% sales tax, half burned | 1% listing fee, stall rent |

The non-transferability of XP is the point. Because it cannot be traded it cannot be cornered,
farmed for resale, or accumulated as a speculative asset — it can only be burned on friction. This
is PoE2's gold, and it is thematically native to a server called xpfarm.

**Deviation from the initial proposal, made deliberately.** The first draft denominated *both* fees
in XP. That leaves diamonds with no sink whatsoever, and an un-sinked currency backed by an
unbounded faucet is exactly how Minecraft server economies die — every server that tried
diamond-only currency ended up resetting resource worlds every three to four months to compensate.
Moving the sales tax to diamonds gives both currencies a sink without making XP tradeable.

Charging fees against real XP points rather than a mirrored ledger removes an entire class of
reconciliation bug and needs no deposit flow.

### Known leaks

- **Diamonds have an unbounded faucet.** Mining, and any automated farm feeding into a sellable
  output. The tax burn is a real sink but is proportional to trade volume, not to mining rate. If
  supply outruns it, the honest remedy is a pre-announced seasonal reset, recorded as a limitation
  rather than designed in now.
- **The grindstone punctures the XP sink.** Since 1.14, disenchanting refunds XP, so a player with
  an XP farm can loop enchant/disenchant. XP fee pressure is therefore *soft*. Accepted knowingly;
  closing it means retrofitting a grindstone cost, which is out of scope and arguably another
  plugin's job.

## 4. Market structure

The market is **split by item type**. This is the single most important structural decision, and it
follows WoW 9.2.7's commodity/gear split.

**Commodity exchange** — stackable items with no meaningful component data (cobblestone, iron,
wheat, diamonds). Anonymous buy/sell offer matching, RuneScape Grand Exchange style. Buyers never
see individual listings. No order book to snipe, no undercutting war, no listing games. Instant and
liquid, which is what bulk goods need.

**Unique board** — enchanted, renamed, damaged, custom, or container items. Browsable listings with
instant buyout, surfaced physically at vendor stalls and mirrored in a global search index. No
bidding. This is where the character lives.

Beyond liquidity, the split sidesteps the nastiest correctness bug class in shop plugins: NBT item
matching. ChestShop's "Anvil Wizardry" bug — enchanted and renamed items failing comparison so
purchases never decremented stock, producing accidental infinite-stock shops — only exists because
one code path handled both kinds of item.

### On ESO's actual lesson

ESO's beloved quality is the **physical vendor in a town**. Its hated quality is the **opaque
search** — no cross-guild search in the client, mandatory third-party addons, the five-guild cap
forcing players to trade social guilds for market access. There is no reason to inherit the second
to get the first. Players get a physical stall **and** a global search index.

## 5. Liquidity

At fifteen players, listings will sit unsold for days and the market will feel dead in week one
without help.

**Server buy-back floor.** A server counterparty always buys common goods at a floor price, so a
player can always convert loot to currency even when nobody else is online. The trade-off is
accepted knowingly: the floor caps price discovery below itself, because the server is a permanent,
infinitely patient competing bid. At fifteen players, guaranteed liquidity beats perfect price
discovery.

**The floor is also the single most dangerous number in the plugin.** A buy-back price at or above
effective acquisition cost is an infinite money faucet, and it is the number-one documented way
Minecraft server economies die. Floors must be audited against **automated-farm output rates**, not
against manual play — a hopper-fed farm profitably selling into the floor is unbounded money for
zero effort. `liquidity.farm-output-costs` records the per-item automated-production estimate and
`liquidity.farm-output-audit` refuses to load any floor at or above it, so the check is mechanical
rather than a thing someone has to remember. A floor with **no** cost estimate also fails to load:
the un-estimated item is precisely the dangerous case, so it fails closed rather than defaulting.

**Per-item rolling buy limits** (RuneScape's four-hour window) cap how fast any single actor can
corner an item. At this scale, cornering is the realistic threat model, not botting.

## 6. Fees and sinks

ESO's structure, which is the best-tested design available:

- **1% listing fee**, XP, charged at listing, non-refundable. Discourages spam and speculative
  relisting.
- **~7% sales tax**, diamonds, taken from proceeds. **Half destroyed** — a continuous,
  volume-proportional deflationary sink. **Half to a community pot** for server events.

The 5–15% total band is where every major game independently converged. Starting at the low end is
deliberate: taxing an already-illiquid market kills the remaining volume, and the research is
explicit that liquidity support comes first and fee tightening comes only once books are deep.

The half-to-community-pot split is not just economics. On a fifteen-player server, "our taxes fund
something we can see" is a real engagement driver that pure destruction throws away.

**Stall rent by sealed weekly bid.** ESO's kiosk auction transplants cleanly and is the strongest
available sink. Bids are secret, so nobody wins by +1 and collusion is hard. Funds are taken at bid
time, so bids are real. Ties break to the earliest bid. One stall per player per period, so no one
corners the market town. Tiering stalls by foot traffic creates the real-estate market and the
market-town feel in one mechanism.

## 7. Cross-platform UI

### The architecture

A screen is declared **once, as data** — title, rows of entries, available actions. Two renderers
consume that declaration:

- `JavaChestRenderer` → Bukkit inventory GUI
- `BedrockFormRenderer` → Cumulus `SimpleForm` tree

Branch once on `FloodgateApi.isFloodgatePlayer(uuid)` at the entry point, following the existing
`magic-carpet` `EditionResolver` pattern: Floodgate is a soft dependency, reached reflectively,
never on the compile classpath, and every failure in the chain resolves the player to Java edition.

Navigation is defined once; only pixels differ. This is what stops the two platforms drifting into
two different products.

**One shared chest GUI for both platforms was rejected.** The left-vs-right-click limitation alone
would make it a degraded experience for half the players, and Cumulus forms are strictly better for
the touch-input case — which is most of the younger players.

### Four hard constraints

1. **A Bedrock player never touches a chest GUI.** Geyser's `JavaOpenScreenTranslator` force-closes
   any open form when a container opens, firing the form's `closedResultHandler` as though the
   player dismissed it. That path produced Geyser #5850, where Bedrock players lost all inventory
   access until rejoin. The renderer boundary is absolute in both directions.
2. **One action per slot, right-click only.** Geyser documents left-vs-right click discrimination as
   **unfixable**. The universal shop idiom "left-click buys 1, right-click buys 64" is broken for
   every Bedrock player. Quantity is always an explicit step. Left-click on entities is separately
   unreliable on Bedrock, and touch users must hold ~3 seconds — so entity interaction is
   right-click/use only.
3. **Plain vanilla items as icons.** Bedrock has no custom-skull support; Geyser's only mechanism is
   static pre-registration in `custom-skulls.yml`, which cannot handle runtime-generated textures,
   and registering skulls has broken the Bedrock item-interact event outright (#5923).
4. **Shift-click is never special-cased.** Java fires one event; Bedrock fires two (#953).

### Younger players

`FloodgatePlayer.getInputMode()` exposes `TOUCH`. Those clients get larger targets and fewer steps
via `ui.bedrock-touch-simplify`. Every task is completable through vendors and menus with no command
typed; the command tree exists for accessibility and admin work.

Confirmations gate every spend. A misclick must never cost a player their diamonds — this is a
usability requirement, not politeness.

## 8. Vendors

A **real vanilla `WanderingTrader`**. Not Citizens, not a packet NPC.

Geyser issue #6322 — *Citizens NPC shop GUIs never open for Bedrock players, plus duplicated chat* —
was closed **`not planned`** in May 2026. That is precisely this plugin's use case, declined
upstream. Citizens carries further Bedrock breakage (holograms not displaying, names over nine
characters not showing, NPCs vanishing at distance) and lags bleeding-edge Paper at each version
bump. Packet/player-skin NPCs sit on Geyser's most fragile surface — skin and player-entity
translation — with a long tail of intermittent invisibility bugs.

A vanilla entity rides Geyser's most mature code path. Take it, and take no dependency.

### Grief-proofing

```
setDespawnDelay(0)          // javadoc, verbatim: "<= 0 → the trader will not be despawned"
setRemoveWhenFarAway(false) // stops the far-from-player despawn check
setPersistent(true)         // survives chunk save/load — a separate concern; set both
setAI(false)                // stops autonomous wandering
setInvulnerable(true)
setSilent(true); setGravity(false); setCanPickupItems(false)
+ scoreboard team collisionRule:never
```

`setCollidable(false)` does **not** stop players pushing an entity (Paper #7424, #672). The
scoreboard team operates at the layer vanilla itself uses, and is the only thing that actually
works.

`setInvulnerable(true)` is sufficient here. The creative-mode instant-kill loophole is specific to
armor stands, boats, and item frames — not to real `LivingEntity` mobs.

Trader llamas are a natural-spawn mechanic and do not accompany a plugin-spawned trader on Java.

### Interception — three layers

Community lore says cancelling the interact event does not stop the villager trade window. That lore
cites three tickets that are all about `PlayerInteractAtEntityEvent` — a **different event** — and
all three are closed, one as Fixed in 2017, one Invalid, one Duplicate. No modern reproduction was
found. Cancelling `PlayerInteractEntityEvent` is therefore expected to work.

But no modern *positive* confirmation was found either, and belt-and-braces is nearly free:

1. Cancel `PlayerInteractEntityEvent`, open our own UI.
2. Set the merchant's recipes to `Collections.emptyList()`.
3. Guard `InventoryOpenEvent` for `InventoryType.MERCHANT` — the javadoc is unambiguous that a
   cancelled inventory open event means the screen does not show.

`PlayerPurchaseEvent` and `TradeSelectEvent` both fire on an already-open GUI and are useless for
blocking it.

### Persistence

An entity in an unloaded chunk does not exist. Rather than pay for plugin chunk tickets, vendors are
tagged in the `PersistentDataContainer` and respawned-if-missing on **`EntitiesLoadEvent`** —
chosen over `ChunkLoadEvent`, whose `getEntities()` has documented ordering bugs (Paper #5921,
#5872). The tag also gives dedupe across restarts for free.

### Labels

`TextDisplay` **works on Bedrock**, contradicting widely-repeated claims to the contrary — Geyser's
`VanillaEntities` registers `TEXT_DISPLAY` with a full metadata translator, and five 2025–2026 PRs
address its rendering. But it is **emulated, not reproduced**: Geyser sets scale 0 and an empty
hitbox, forces the nametag always-visible, and spawns a secondary armor stand for multi-line text.
Bedrock players see **floating nametag text** — no background panel, no alignment, no
`setSeeThrough`, no per-line shadow. Label copy must be written to look right that way.

`ItemDisplay` and `BlockDisplay` are **not registered in Geyser at all**. Bedrock players see
nothing. No vendor visual may be built on them.

## 9. Analytics and charts

Read-only. Daily OHLCV rollups per item key, moving averages, a Laspeyres basket index over roughly
20–40 items, and "your price vs. server average."

**Outlier filtering is a correctness requirement, not a nicety.** poe.ninja demonstrates that a
published median *becomes* the market price — everyone price-checks against it before listing, so
it self-reinforces. On a small server that is largely good, because it solves price discovery for
you, but it means an unfiltered outlier propagates into the real market. Median-absolute-deviation
filtering, configurable multiplier.

The index is validated against **median time-to-earn**. This is the one methodological detail
separating a real index from a decorative number: it distinguishes genuine currency inflation from
single-item scarcity. A CPI also reads flat and useless if most prices are server-fixed, so the
index only earns its keep once player prices genuinely float — expect it to be uninformative early.

### Rendering

**Map items for real charts.** Geyser's `JavaMapItemDataTranslator` converts the full Java colour
array to Bedrock ABGR, preserves decorations and the locked flag, and queues packets arriving before
entity spawn. Every historical blocker is closed. This is the **only** technique in the whole design
that produces genuine pixel-level graphics identically on both platforms.

Constraints: 128×128 hard canvas; the palette is 4-shade base-colour families, so flat-colour charts
render cleanly and photographs band badly (fine — charts are flat-colour). A contextual
`MapRenderer` gives a separate canvas per player, which is what per-player market data needs, at the
cost of 128×128 palette-matching per viewer per redraw — the classic map-lag vector. Redraw on a
timer, never per tick. Deliver in-hand or in a GUI slot; **avoid item frames**, which carry a long
tail of general Bedrock invisibility issues.

`MapPalette.matchColor()` and `imageToBytes()` are deprecated for removal — use the `Color`-based
`MapCanvas.setPixelColor`.

**Inline bars: `█ ▓ ▒ ░` plus box-drawing.** Four density levels, full height.

### The sparkline decision

Bedrock's font has **no Unicode fallback layer**. Java ships GNU Unifont and falls back for any
codepoint outside Mojangles; Bedrock has only fixed glyph sheets, and unmapped codepoints render as
**blank space, not a tofu box** — so a broken sparkline looks like stray whitespace rather than an
obvious error.

Bedrock's sheet is CP437-derived. The confirmed-present block characters (`U+2580`, `U+2584`,
`U+2588`, `U+2591`–`U+2593`) are all CP437 characters. **CP437 does not contain the eighth-block
ramp `U+2581`–`U+2587`.** The classic sparkline alphabet is therefore at best one character in
eight. This is disproven-by-absence rather than observed, and is the highest-value single empirical
test on the gate 7a list — a positive result would hand us eight-level sparklines on both platforms
for free.

**Resource packs were considered and deferred.** The honest assessment:

- They would **not** render identically. Java uses `minecraft:font` JSON providers with variable
  glyph widths and arbitrary ascent; Bedrock uses a fixed 16×16 grid bitmap sheet. Matching them
  means crippling the Java font down to Bedrock's constraints. Two artifacts, authored separately,
  resynced at every Minecraft update — and vanilla Bedrock texture paths are known to shift between
  versions.
- Java resource packs have **zero** effect on Bedrock; Geyser explicitly does not convert them. A
  Geyser-served Bedrock `.mcpack` is the only route, and it forces a pack download on join.
- Delivery is asymmetric in the wrong direction. Geyser applies Bedrock packs fairly uniformly, but
  a **Java** server pack is a prompt the player can decline unless you kick everyone who says no.
  Java gains a *pack-declined, glyphs-garbled* state that Bedrock does not have.
- No named precedent was found of any server doing this for charting specifically.
- The prize is narrow. Map items already solve real charting better — no pack, no download, no
  decline state, no version-chasing. The only genuine gap is **inline** visuals, where a map cannot
  go. The entire gain is four density levels → eight height levels, in inline text only.

**Resolution:** glyph selection sits behind a `BarGlyphs` strategy interface (`DENSITY_4`,
`RAMP_8`) selected by `ui.bar-glyphs`. v1 ships `density-4`. Adding packs in v2 is a config flip
with no call-site changes. Roughly an hour of design cost now to keep the door open, and nothing
thereafter.

## 10. Safety

**An immutable trade log from day one.** Every documented Minecraft economy catastrophe — the
ChestShop chunk-unload dupe, exponential-notation money creation via `1e9` prices, "Anvil Wizardry"
infinite stock, the written-book dupe, EssentialsX BigDecimal/double rounding in the buyer's favour
— was found *after* the damage. You cannot retrofit an audit trail onto a compromised economy.
`trades` is append-only, never pruned, and no plugin code path can update or delete a row.

**Plugin escrow, not chest-backed stock.** Listing removes the item into the database. Sales
complete while the seller is offline, and the chunk-unload container desync that produced
ChestShop's worst dupe cannot occur because no container is involved.

**Integer minor units end to end.** No `double` ever holds a balance. Prices are rejected at parse,
not at use — the `1e9` bug was a validation gap, not an arithmetic one.

**Item identity via `ItemStack#serializeAsBytes()`.** Chosen over legacy YAML/base64
`ConfigurationSerializable` because Paper's byte serialization migrates across versions through
DataFixerUpper. A listing created today must still deserialize after a Minecraft update.

**The Floodgate identity merge is a schema requirement, not a footnote.** Unlinked Bedrock players
receive a synthetic XUID-derived UUID; on linking a Java account, `getCorrectUniqueId()` returns
their **real Java UUID** instead. Floodgate does not migrate plugin data. Without an explicit merge
path, a player who links loses their balance, listings, vendors, and history. Everything is keyed on
UUID — never username, since the Floodgate prefix is config-mutable and Java names change — and
`account_links` records every migration.

**Shulker boxes are allowed but never rendered raw.** Geyser #3001 reports heavy-NBT items rendering
entirely invisibly in Bedrock inventories; #818 reports empty shulkers kicking Bedrock players on
connect. Listings show a safe placeholder icon plus a text content summary; contents appear in a
detail screen. Bulk trade is preserved without shipping an invisible-item bug to the youngest
players.

**SQLite operational detail** is in the checklist. The one item worth repeating: sqlite-jdbc
extracts a native library into `java.io.tmpdir` at class-load, and a hardened container with `/tmp`
mounted `noexec` fails with `UnsatisfiedLinkError`. Given the Dokploy deployment,
`storage.sqlite-tmpdir` is set explicitly from day one rather than debugged in production.

## 11. Deliberate non-goals

**No player-facing banks, shares, dividends, or lending — ever, not merely not yet.** EVE's Phaser
Inc. Ponzi took roughly 1.8 trillion ISK from over 4,000 investors before the operators absconded.
On a server of fifteen people who know each other, one such incident ends the community. The
"stock market" the brief asked for is delivered as **read-only analytics**: price history, indices,
and comparison to average. That is what poe.ninja, the Albion Data Project, EVE's Monthly Economic
Report, and UEX all actually are — none of them creates a tradeable security.

There is a genuine differentiator here. DynaShop and DynamicShop already do per-item price history
server-side **for admins**. No surveyed Minecraft plugin exposes a composite basket index or
historical charts as a first-class **player-facing** feature.

**No automated price bands or ceilings.** Jagex *retired* automated price limits in favour of manual
last-resort intervention — an implicit admission they underperformed. On a fifteen-player server the
admin *is* the manual intervention, which is the same approach CCP uses at vastly greater scale.

**No wash-trade detection.** Mature in financial exchanges, weakly evidenced in games, and
disproportionate at this scale. Alt-account abuse is addressed socially, backed by a playtime gate.

## 12. Testing

Unit-testable without a server: ledger arithmetic and rounding, fee and tax splits, item key
derivation, escrow serialization round-trips, buy-limit windows, outlier filtering, index
computation, config validation, the `BarGlyphs` strategies, and `plugin.yml`/`config.yml` parsing
via the standard `PluginDescriptorTest`. Seven of the eight modules carry no Bukkit dependency
specifically so this is possible.

Runtime verification at gate 7a on the Legendary stack covers the cross-platform behaviour no unit
test can reach: the full list/browse/buy/withdraw loop completed by both a Java and a Bedrock player
with no command typed, vendor interception, form/GUI isolation, map chart legibility, `TextDisplay`
label legibility, vendor survival across restart and explosion, and the Floodgate link migration.

Full pass/fail conditions are enumerated in `docs/PLUGIN_CHECKLIST.md` §1.

## 13. Phasing

**v1 (`0.1.0`)** — ledger, split market, cross-platform UI, stationary vendors, market-town stalls,
read-only analytics with map charts. The schema carries columns for v2 features so no migration is
needed later.

**v2** — wandering vendors: a vendor periodically relocates to another player's market area or the
market town, carrying its listings to that foot traffic, with the owner told where it went. Possibly
the `ramp-8` glyph packs, if the gate 7a test says they are worth it.

**Never** — tradeable securities.

## 14. Sources

Primary sources fetched and cross-verified: the PoE Trade Manifesto (Chris Wilson, 2017-11-02);
ZeniMax official support documentation for ESO guild trader fees and bidding; the OSRS Wiki for
Grand Exchange tax and buy limits; Blizzard's Diablo III auction house closure announcement; Geyser
`master` source inspected directly via the GitHub API for `VanillaEntities`, `TextDisplayEntity`,
`InteractionEntity`, `FormCache`, `JavaOpenScreenTranslator`, `JavaMapItemDataTranslator`, and
`MessageTranslator`; Cumulus and Floodgate API javadocs; Paper javadocs and `build.gradle.kts`.

**Version caveat carried into gate 4:** no consulted source used the `26.1` version scheme. Every
Paper API signature cited here was verified against 1.21.x javadocs on the assumption that the new
scheme is a renaming. Confirm against the actual Paper 26.1.2 build 74 JAR before relying on any of
them.
