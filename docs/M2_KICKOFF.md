# M2 kickoff — the market

Durable handoff written `2026-07-22`, immediately after `v0.1.0` shipped. Everything needed to
start M2 is here or is linked from here; nothing important lives only in conversation.

## Where things stand

`v0.1.0` (M1) is **released and verified** — https://github.com/carmelosantana/minecraft-farmers-market/releases/tag/v0.1.0

- `main` is at `f6143cc`, tag `v0.1.0` sits on it, both Actions runs green.
- Release assets: `farmers-market-0.1.0.jar` + `SHA256SUMS.txt`, `sha256sum --check` OK, no `original-*`.
- Gates 1-9 complete. **Gates 10 (updater), 11 (deploy), and 12 (handoff) have NOT run.**
- 173 tests, 32 Java files, 4,064 main lines, 3,034 test lines.

Read these before writing any M2 code:

| File | What it is |
|---|---|
| `docs/PLUGIN_CHECKLIST.md` | Authoritative scope (§1) and every gate's recorded evidence |
| `docs/superpowers/specs/2026-07-21-farmers-market-design.md` | Why the design is shaped this way |
| `docs/superpowers/plans/2026-07-21-farmers-market-m1-foundation.md` | M1's plan — copy its Global Constraints block verbatim into M2's plan |
| `.superpowers/sdd/progress.md` | M1's task ledger and every deferred finding (gitignored, local only) |

## What M1 actually built

Four Bukkit-free packages plus one thin wiring layer. The Bukkit-free rule is what makes the suite
possible — do not break it in M2.

| Package | Public surface M2 will call |
|---|---|
| `config` | `FmConfig.load(ConfigSource, Consumer<String> warn)`. **Already parses every key M2-M5 need** — listing fees, sales tax, burn share, buy limits, listing duration. M2 consumes, it does not add. |
| `storage` | `Database.open(Path, String tmpdir, int busyTimeout)`, `Migrations.applyTo(Connection)`, `DatabaseExecutor.submit(Callable)`, `AccountDao`, `AccountRow` |
| `identity` | `EditionResolver.create(Logger)` / `.isBedrock(UUID)` / `.linkedBedrockUuid(UUID)`, `AccountMerge.merge(AccountRow, AccountRow)` |
| `ledger` | `Ledger(Database, AccountDao, DatabaseExecutor)` — **three args**. `Diamonds` (1 diamond = 1000 dust, integer only), `LedgerException.Reason`, `ExperienceMath` |
| `command` | `MarketCommand`, `MarketResolver` (the Bukkit-free decision seam — extend this, don't bypass it) |

Schema is at **version 1**: `accounts`, `account_links`, `schema_version`.

## M2 scope

The market. From §1: listings, item identity, commodity offer matching, escrow, fees and tax, and
**the immutable trade log**.

- **Split market.** Commodities (stackable, no meaningful components) use anonymous buy/sell offer matching. Uniques (enchanted, renamed, damaged, custom, containers) use a browsable board with instant buyout. This split also sidesteps the NBT-matching bug class that produced ChestShop's accidental infinite-stock shops.
- **Item identity** via Paper's `ItemStack#serializeAsBytes()` / `deserializeBytes()`, not legacy YAML/base64 — it migrates across versions through DataFixerUpper.
- **Fees:** 1% listing fee in XP at listing, non-refundable. ~7% sales tax in diamonds from proceeds, half burned, half to the community pot. Both currencies get a sink; XP stays non-transferable.
- **Escrow:** listing removes the item into the database. Sales complete while the seller is offline.
- **Shulkers** are listable but must NEVER render as a raw icon — Geyser #3001 renders heavy-NBT items invisibly on Bedrock. Placeholder icon plus a text content summary.
- **The `trades` table must land in the same milestone as the first trade, never later.** You cannot retrofit an audit trail onto an economy that has already been exploited.

Still M3-M5, do not build: any GUI or Cumulus form, vendors, `TextDisplay`, stalls, sealed bids,
price history, indices, map charts.

## STATUS: entry conditions are DONE but UNREVIEWED

Branch `feat/m2-entry-conditions`, based on `95cf0a1`, **not merged and not pushed**. `main` is
still at `95cf0a1` with `v0.1.0` released, so nothing here is at risk.

Six commits: `89a2b77` (ConfigValidator), `b0e8dbf` (upsertAccount + findLink), `673d313`
(`NOTHING_WRITTEN`), `13dcfdc` (plugin logger), `ef31f92` (re-entrancy notes), `c83b097` (the two
follow-ups below). **204 tests**, 29 mutations run and all caught. Full detail:
`.superpowers/sdd/m2-entry-report.md`.

**The next step is a review of this branch**, then merge, then write the M2 plan. Do not merge it
unreviewed — every task on M1 that skipped straight to merge would have shipped a defect.

Two things were caught and fixed during the work, both worth knowing:

1. **The M1 mint-bug shape recurred.** `deposit` was narrowed to a definite refusal on a provable
   no-write failure while `withdraw` was left as unknown — "two code paths, same question,
   different answers", which is exactly what let `deposit` and `deliver` diverge in M1 and mint
   diamonds. Fixed by routing both through one `Ledger.readBeforeWriting`, with the policy written
   down once and pointed at from every call site. `transfer` and `mergeAccounts` deliberately stay
   unknown — reads inside a transaction are entangled with rollback and the autocommit restore, and
   neither has a compensating caller — and that exception is now written down rather than inferred.
2. **A fifth hollow test.** `mergeIsIdempotentAndDoesNotDoubleCredit` survived a `findLink` that
   always answered "no link". It was genuinely out of scope until `findLink` existed; adding it put
   the test on top of the mechanism it failed to pin. Replaced with a test killed by that exact
   mutation.

**One concern remains open:** `MarketCommand`'s deposit/withdraw/deliver compensation branches are
still provable only by reading them — no unit test reaches them, because they need a live server.
If M2 touches those branches, lifting the decision into `MarketResolver` as a pure function closes
it permanently. Otherwise they stay a runtime-pass obligation.

## M2 entry conditions — do these FIRST

Before M2 adds its first config key or schema change, while nothing has copied the current shape:

1. **Extract `ConfigValidator` from `FmConfig`** to match the sibling plugin `timber-blast`'s shape. M2-M5 will copy whichever shape M1 set — this is why it is an entry condition and not a Minor.
2. **Add `AccountDao.upsertAccount(AccountRow)` and `AccountDao.findLink(UUID)`.** The first makes `AccountMerge`'s computed timestamps actually persist — today only `into.uuid()` is used, so all four `AccountMergeTest` tests pin behaviour no production path observes. The second replaces an O(n) full-table scan that runs on every player join.
3. **Give `Ledger` a typed pre-write-failure reason.** Today a `SELECT` failure before any `UPDATE` is indistinguishable from an unknown outcome, so `deposit` refuses to return items it could safely return.
4. **Move `MarketCommand.LOG` to the plugin logger** so console lines carry Paper's `[FarmersMarket]` prefix.
5. **Add a re-entrancy note** wherever M2 adds an in-package caller near `Ledger.inTransaction` — the guard is in place, but M2 is the milestone that makes nesting reachable.

## The practice that mattered most

**Mutation-check every guard test.** Delete the mechanism the test names; confirm the test actually
fails. On M1, four tests passed while asserting nothing about what they claimed to guard — and
**two of those were written into the plan by the planner**, not by an implementer. A green count is
not evidence.

That discipline is what caught: an `Error` between the debit and credit writes skipping rollback and
then being committed by the `autoCommit` restore (money destroyed); `deposit` and `deliver`
answering the same unknown-outcome question in opposite directions (money created); a committed
withdrawal at shutdown vanishing with no log line; `Migrations` carrying the identical
`Error`-skips-rollback shape already fixed in `Ledger`; a non-deterministic flush test; and every
boolean config default being unpinned, including `farm-output-audit`, which is the faucet safety
switch itself. One glyph-guard rewrite was caught by the check before it ever shipped.

Also worth repeating: **the whole-branch review found defects no per-task review could**, because
the worst ones lived where packages meet. Budget for it.

## Open obligations M2 inherits

Not M2's to fix, but M2 must not make them worse:

- **The shutdown window is recorded, not eliminated.** A withdrawal committing inside `onDisable`'s flush still takes diamonds without delivering items; it now leaves a reconcilable log line naming player, operation, and amount. Every new M2 operation that moves money must use the same `onMainThread(future, uuid, what, handler)` seam so it inherits that record.
- **The Floodgate account merge is unverified end-to-end.** If `isFloodgatePlayer(javaUuid)` returns false for an already-linked player, the join guard short-circuits and the merge is dead code that fails silently. Only a real linked Bedrock account settles it. Highest-value gate 12 play-test item.
- **Five Geyser assumptions remain unverified** (§1 Known limitations). M2 carries zero Bedrock risk, so they do not block it — but M3 must not start until the cheap ones are tested, especially whether `U+2581`-`U+2587` render on a real Bedrock client.

## Not yet done for v0.1.0

`v0.1.0` is released but **not deployed**. Gates 10-12 remain: updater enrollment
(`minecraft-plugin-updater`), the operator-triggered Dokploy redeployment
(`minecraft-plugin-deploy`), and the handoff record (`minecraft-plugin-handoff`). Decide whether to
deploy `0.1.0` on its own or hold it and deploy after M2 — a ledger with no market is safe to run
but does nothing a player would notice, so holding is defensible.
