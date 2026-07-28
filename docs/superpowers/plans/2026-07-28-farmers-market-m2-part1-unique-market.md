# Farmers Market M2 (Part 1) — Spine & the Unique Market Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `0.2.0` — a real market for unique items. A player lists an enchanted, renamed, damaged, or custom item for a diamond price and pays a 1% XP listing fee; the item goes into database escrow; another player buys it instantly from their ledger balance; a ~7% sales tax is taken in diamonds (half burned, half to a community pot); the seller is paid the net while offline; the item is handed to the buyer; and the trade is written to an append-only, database-enforced trade log.

**Architecture:** One new Bukkit-free package, `market`, plus one Bukkit adapter and extensions to the existing command layer. The market's decisions, money math, escrow bookkeeping, and the atomic sale are pure logic on the single-writer database thread, unit-tested with no server running. The one class that touches `ItemStack` (`BukkitItemCodec`) turns an item into a Bukkit-free value the rest of the package operates on. The atomic sale — money move, escrow flip, and trade-log write in one transaction — is exactly the operation M1's `Ledger` javadoc anticipated M2 would need, and it is built on a transaction primitive lifted out of `Ledger` so all three callers share one proven implementation.

**Tech Stack:** Java 25, Paper 26.1.2 build 74, SQLite via `org.xerial:sqlite-jdbc` (unchanged from M1), JUnit 5. No new dependencies. No Mockito, no MockBukkit.

**Milestone context:** This is **Part 1 of M2**, the market milestone. Part 1 builds the shared spine (item identity, escrow, fees, tax, the trade log) and the unique-item board with instant buyout — a complete, shippable market. **Part 2** (a separate plan) adds the commodity offer-matching exchange, rolling buy limits, and the server buy-back floor on top of this spine. M1 (`0.1.0`) shipped the ledger and is released. Design rationale is in `docs/superpowers/specs/2026-07-21-farmers-market-design.md`; authoritative scope is `docs/PLUGIN_CHECKLIST.md` §1; the handoff that set up this work is `docs/M2_KICKOFF.md`.

## Global Constraints

Binding on every task. A task that violates any of these is not complete. Copied from M1 with the version line and dependency line updated; everything else is unchanged and still binds.

- **Java 25**, **Paper `26.1.2` build 74**, `api-version: '26.1'` in `plugin.yml`. Do not lower `api-version` — a lower value opts the JAR into Paper's `Commodore` bytecode rewrites.
- Maven group `org.xpfarm`, artifactId `farmers-market`, **version `0.2.0`**. Root package `org.xpfarm.farmersmarket`. (Task 1 bumps `pom.xml`; `plugin.yml` already reads `${project.version}`.)
- **AGPL-3.0-or-later.** Every new `.java` file carries the project's license header. Match the header in `src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java` verbatim in structure, changing only the one-line description.
- **No external services.** This plugin makes zero outbound network calls. Do not add HTTP clients, Ollama, Umami, or telemetry of any kind.
- **No new dependencies.** M2 Part 1 adds none. The market is built on M1's four packages (`config`, `storage`, `identity`, `ledger`) plus Bukkit. Do not add Mockito, MockBukkit, HikariCP, Guava, Apache Commons, or any other library.
- **Tests ship with the code, not after it.** Any logic separable from the Bukkit runtime must be unit tested in the same task that writes it. Classes in `config`, `storage`, `identity`, `ledger`, and the new `market` package must not import `org.bukkit.*` at all, with the sole exceptions of the explicitly-named Bukkit adapter classes (`BukkitConfigSource`, `EditionResolver`, `BukkitItemCodec`, `MarketCommand`, `FarmersMarketPlugin`). That import ban is what makes them testable without a server.
- **Money is integer-only.** No `double`, `float`, or `BigDecimal` ever holds a balance or an amount. Every balance change goes through the `Diamonds` type and its overflow-refusing arithmetic — including inside the sale transaction, which must never add or subtract raw `long` dust the way `identity.AccountMerge` deliberately does not. The one permitted `double` is a **config-supplied rate** (a fee or tax percent, a burn share), which is converted to an integer basis-point count **once** and never touches a dust amount thereafter. See Task 4's `MarketMath`.
- **Geyser/Floodgate/Bedrock safety.** All player-facing output in Part 1 is plain chat text. Specifically forbidden: hover events, click events (a `copy_to_clipboard` click event breaks Bedrock chat outright, Geyser #1399), hex/RGB colours and gradients (Geyser downgrades them), strikethrough and underline (Geyser strips both), and any reliance on `▁▂▃▄▅▆▇`. Legacy colour codes and `█▓▒░` are safe. **Shulker boxes and other heavy-NBT items are listable but must never be rendered as a raw icon** (Geyser #3001 renders them invisibly) — Part 1 ships only chat text, so this means the *content summary text* is what a player reads; no item icon is drawn until M3.
- **Never key player data on username.** Always UUID. This now includes the buyer, the seller, and the community-pot account.
- Follow the house style of the sibling plugin at `/home/carmelo/Projects/Minecraft/Plugins/timber-blast` and, more immediately, the M1 code already in this repo — package layout, value-type shape, prepared-statement DAO shape, javadoc voice, comment density. Read the neighbouring class before writing a new one.
- Build must pass `mvn --batch-mode --no-transfer-progress clean verify`.

### Settled facts — do not re-derive, do not "improve"

M1's settled facts (1–9 in the M1 plan) still bind — the SQLite driver is provided-not-shaded, `org.sqlite.*` is never relocated, one connection on one thread, `foreign_keys=ON` per connection, `onDisable` flushes, Floodgate is reflective-only, a linked Bedrock UUID changes, `getTotalExperience()` is unreliable. Add these, specific to Part 1:

1. **Item identity is `ItemStack#serializeAsBytes()` / `ItemStack#deserializeBytes(byte[])`.** Not legacy YAML/base64 `ConfigurationSerializable` — Paper's byte form migrates across Minecraft versions through DataFixerUpper, so a listing created today still deserializes after a game update. The bytes are the escrow payload and the source of the content hash.
2. **The `trades` table lands in this part, with the first trade, and is append-only at the database layer.** Two `BEFORE UPDATE`/`BEFORE DELETE` triggers `RAISE(ABORT, ...)` so no code path — not a bug, not a future edit, not a console `sqlite3` session that forgot — can rewrite history. Its two conservation invariants (`gross = net + tax`, `tax = burned + pot`) are `CHECK` constraints, refused by the database exactly as `CHECK (diamonds_dust >= 0)` is. You cannot retrofit an audit trail onto an economy that has already been exploited.
3. **The atomic sale is one transaction over `AccountDao` + `MarketDao`, not a call to `Ledger.transfer`.** M1's `Ledger.inTransaction` javadoc says this in as many words: a sale that moves money and writes an escrow row and a trade-log row cannot wrap `transfer()` (one connection, and the nesting guard refuses it), so the whole sale is written as one `inTransaction` body. Task 1 lifts that transaction primitive into `storage.TransactionRunner` so the sale and the ledger share one implementation rather than a second hand-rolled copy — the exact divergence that let `Migrations` carry the same `Error`-skips-rollback bug `Ledger` had already fixed.
4. **The community pot is a ledger account under a nil-UUID sentinel.** `SystemAccounts.COMMUNITY_POT = new UUID(0L, 0L)`. No real player holds the nil UUID (Java accounts are v4-random; Floodgate synthetics are XUID-derived), so the pot is just another `accounts` row and every overflow guard already applies to it. It only ever accumulates in Part 1; spending it is a later admin feature.
5. **The listing fee is real vanilla XP, charged after the escrow write succeeds.** XP is non-transferable and never mirrored in the ledger (design §3). The fee is deducted with `Player#giveExp(-points)` on the main thread, *after* the item is safely escrowed — so a failed listing costs the player neither the item nor the fee, and a successful one costs both. The fee is non-refundable on cancel or expiry, by design.
6. **Every diamond a player spends in the market comes from their ledger balance, not their inventory.** A buyer must have `deposit`ed first. This is why M1 built deposit/withdraw before the market: a purchase is a pure ledger operation plus an item delivery, with no physical diamonds in the trade itself.

---

## File Structure

**Created — Bukkit-free (no `org.bukkit.*` imports):**

| Path under `src/main/java/org/xpfarm/farmersmarket/` | Responsibility |
|---|---|
| `storage/TransactionRunner.java` | The one money-safe transaction primitive: autocommit toggle, `Throwable` rollback, nesting guard, suppressed-exception restore. Lifted from `Ledger`. |
| `market/ItemClass.java` | Enum `COMMODITY`, `UNIQUE`, plus the pure classification rule |
| `market/ItemKey.java` | Pure content-hash identity for a unique item |
| `market/ListedItem.java` | Bukkit-free value type: an item as bytes + class + key + display fields |
| `market/ListingStatus.java` | Enum `ACTIVE`, `SOLD`, `CANCELLED`, `EXPIRED` |
| `market/ListingRow.java` | Immutable `listings` row |
| `market/TradeRow.java` | Immutable `trades` row |
| `market/PendingItemRow.java` | Immutable `pending_items` row (an item owed to a player) |
| `market/MarketException.java` | Typed market refusal, mirroring `LedgerException` |
| `market/SystemAccounts.java` | The community-pot sentinel UUID and the `isSystem` guard |
| `market/MarketMath.java` | Pure fee and tax arithmetic, integer-only, conservation-guaranteed |
| `market/MarketDao.java` | Prepared statements over `listings`, `trades`, `pending_items` |
| `market/SaleResult.java` | What a completed sale hands back to the command layer |
| `market/MarketService.java` | list / buy / cancel / expire / claim, on the writer thread |

**Created — Bukkit adapter (`org.bukkit.*` allowed):**

| Path | Responsibility |
|---|---|
| `market/BukkitItemCodec.java` | `ItemStack` ↔ `ListedItem`: serialize, classify, hash, summarise, deserialize |

**Modified:**

| Path | Change |
|---|---|
| `pom.xml` | Version → `0.2.0` |
| `storage/Migrations.java` | Add Migration 2 (`listings`, `trades`, `pending_items`, triggers) |
| `ledger/Ledger.java` | Delegate `inTransaction` to `storage.TransactionRunner` |
| `command/MarketResolver.java` | New subcommands, price/id/page parsing, market message mapping |
| `command/MarketCommand.java` | Dispatch the new subcommands; item and XP handling; deliver-or-hold |
| `FarmersMarketPlugin.java` | Wire `MarketService`, `BukkitItemCodec`, the expiry sweep |
| `src/main/resources/plugin.yml` | New `usage:` line naming the market subcommands |
| `src/test/java/.../PluginDescriptorTest.java` | Assert the new usage line and that no later-milestone nodes leak in |

---

## Task 1: Lift the transaction primitive into `storage.TransactionRunner`

**Files:**
- Modify: `pom.xml` (version → `0.2.0`)
- Create: `src/main/java/org/xpfarm/farmersmarket/storage/TransactionRunner.java`
- Modify: `src/main/java/org/xpfarm/farmersmarket/ledger/Ledger.java`
- Create: `src/test/java/org/xpfarm/farmersmarket/storage/TransactionRunnerTest.java`
- Test (already exists, must stay green): `src/test/java/org/xpfarm/farmersmarket/ledger/LedgerTest.java`

**Interfaces:**
- Consumes: `Database.connection()`.
- Produces: `new TransactionRunner(Database)`; `<T> T inTransaction(Callable<T> work) throws Exception`. Task 5's sale runs on this; `Ledger.transfer` and `Ledger.mergeAccounts` now run on this too.

**Why this is Task 1.** The sale in Task 5 needs a transaction, and M1's `Ledger.inTransaction` javadoc is explicit that the way to compose is "to write the whole operation as one `inTransaction` body … or to add a method here that owns the whole operation" — not to wrap `transfer()`. A third hand-rolled copy of the autocommit/`Throwable`/nesting dance is the precise shape that already bit this plugin once: `Migrations` shipped the same `Error`-skips-rollback bug `Ledger` had already fixed, because the discipline lived in two places instead of one. This task moves it to one place. `Ledger`'s existing tests are the regression proof that the move changed no behaviour.

The primitive is **guarded on the shared connection's autocommit state, not on instance state**, so any number of `TransactionRunner` instances over the one connection still correctly refuse to nest. That is what lets `Ledger` hold one and `MarketService` hold another without a re-entrancy count.

- [ ] **Step 1: Bump the version**

In `pom.xml`, change the project `<version>` from `0.1.0` to `0.2.0`. Leave `plugin.yml` alone — it reads `${project.version}`.

- [ ] **Step 2: Write the failing `TransactionRunner` test**

This is the same testable surface `LedgerTest` reaches through `Ledger.inTransaction` today, moved to where the code now lives. Use a real `Database` in a `@TempDir`. The load-bearing case is that a failure that is **not** an `Exception` still rolls back and still propagates — the money-destruction bug the `Throwable` catch exists to prevent.

```java
@Test
void committedWorkPersists(@TempDir Path dir) throws Exception {
    try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        Migrations.applyTo(db.connection());
        TransactionRunner runner = new TransactionRunner(db);
        runner.inTransaction(() -> {
            try (var st = db.connection().createStatement()) {
                st.execute("INSERT INTO accounts(uuid,diamonds_dust,created_at,updated_at) "
                        + "VALUES ('a',5,1,1)");
            }
            return null;
        });
        assertEquals(5L, scalarLong(db.connection(), "SELECT diamonds_dust FROM accounts WHERE uuid='a'"));
    }
}

@Test
void aThrowingBodyRollsBackEverythingItWrote(@TempDir Path dir) throws Exception {
    try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        Migrations.applyTo(db.connection());
        TransactionRunner runner = new TransactionRunner(db);
        assertThrows(IllegalStateException.class, () -> runner.inTransaction(() -> {
            try (var st = db.connection().createStatement()) {
                st.execute("INSERT INTO accounts(uuid,diamonds_dust,created_at,updated_at) "
                        + "VALUES ('a',5,1,1)");
            }
            throw new IllegalStateException("boom after the write");
        }));
        assertEquals(0L, scalarLong(db.connection(), "SELECT COUNT(*) FROM accounts WHERE uuid='a'"));
    }
}

@Test
void anErrorNotAnExceptionStillRollsBackAndStillPropagates(@TempDir Path dir) throws Exception {
    try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        Migrations.applyTo(db.connection());
        TransactionRunner runner = new TransactionRunner(db);
        // An Error between a write and the commit is the money-destruction case: a narrower
        // catch(Exception) would skip rollback, and restoring autocommit COMMITs the orphan.
        assertThrows(OutOfMemoryError.class, () -> runner.inTransaction(() -> {
            try (var st = db.connection().createStatement()) {
                st.execute("INSERT INTO accounts(uuid,diamonds_dust,created_at,updated_at) "
                        + "VALUES ('a',5,1,1)");
            }
            throw new OutOfMemoryError("simulated");
        }));
        assertEquals(0L, scalarLong(db.connection(), "SELECT COUNT(*) FROM accounts WHERE uuid='a'"),
                "an Error must roll back exactly as an Exception does, or a debit commits with no credit");
    }
}

@Test
void nestingIsRefusedOutright(@TempDir Path dir) throws Exception {
    try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        Migrations.applyTo(db.connection());
        TransactionRunner runner = new TransactionRunner(db);
        assertThrows(IllegalStateException.class, () -> runner.inTransaction(() ->
                runner.inTransaction(() -> null)));
    }
}
```

Write `scalarLong` as a small private helper in the test file.

- [ ] **Step 3: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=TransactionRunnerTest`
Expected: FAIL — `TransactionRunner` does not exist.

- [ ] **Step 4: Create `TransactionRunner`**

Move the body of `Ledger.inTransaction` verbatim into this class — the nesting guard, the `previousAutoCommit` capture, the `Throwable` catch with `addSuppressed`, and the `finally` that restores autocommit and rethrows a restore failure only when there was no original. Do not simplify it; the shape is exact for reasons M1's javadoc records. Carry that javadoc across.

```java
public final class TransactionRunner {

    private final Database database;

    public TransactionRunner(Database database) {
        this.database = java.util.Objects.requireNonNull(database, "database");
    }

    public <T> T inTransaction(java.util.concurrent.Callable<T> work) throws Exception {
        java.sql.Connection connection = database.connection();
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("nested transaction: this connection is already "
                    + "inside one, and the inner commit would commit the outer caller's "
                    + "half-applied work");
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try {
            T result = work.call();
            connection.commit();
            return result;
        } catch (Throwable t) {
            failure = t;
            try {
                connection.rollback();
            } catch (java.sql.SQLException rollbackFailure) {
                t.addSuppressed(rollbackFailure);
            }
            throw t;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (java.sql.SQLException restoreFailure) {
                if (failure == null) {
                    throw restoreFailure;
                }
                failure.addSuppressed(restoreFailure);
            }
        }
    }
}
```

- [ ] **Step 5: Point `Ledger` at it**

Give `Ledger` a `private final TransactionRunner transactions;`, constructed as `new TransactionRunner(database)` in the constructor (no constructor-signature change, so no ripple to `FarmersMarketPlugin` or `LedgerTest`). Replace `Ledger`'s private `inTransaction` method body with a single delegating call, or delete the method and call `transactions.inTransaction(...)` at the two call sites in `transfer` and `mergeAccounts`. Keep `Ledger`'s existing javadoc about *why* nesting is refused and why M2 must compose inside one transaction — move the prose to `TransactionRunner` and leave a one-line pointer at each call site.

**Watch:** `LedgerTest` currently drives a non-`Exception` failure through the package-private `Ledger.inTransaction`. That test now belongs to `TransactionRunnerTest` (Step 2 above). Delete the now-duplicated case from `LedgerTest` only if it was reaching `inTransaction` directly; keep every `LedgerTest` case that exercises `transfer`/`mergeAccounts` behaviour, because those are the regression proof that this refactor changed nothing.

- [ ] **Step 6: Run the ledger and storage suites**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='TransactionRunnerTest,LedgerTest,MigrationsTest,AccountDaoTest'`
Expected: PASS. If any `LedgerTest` case fails, the refactor changed behaviour — revert and redo, do not adjust the test to match.

- [ ] **Step 7: Mutation-check the rollback**

Temporarily change the `catch (Throwable t)` in `TransactionRunner` to `catch (Exception t)`, run `TransactionRunnerTest`, and confirm `anErrorNotAnException...` fails. Restore it. A green run before this change would mean the test does not actually pin the behaviour that protects player money.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/java/org/xpfarm/farmersmarket/storage/TransactionRunner.java src/main/java/org/xpfarm/farmersmarket/ledger/Ledger.java src/test/java/org/xpfarm/farmersmarket/storage/TransactionRunnerTest.java src/test/java/org/xpfarm/farmersmarket/ledger/LedgerTest.java
git commit -m "refactor(storage): lift the money-safe transaction primitive out of Ledger"
```

> **Note for the follow-up, not this task:** `Migrations.applyTo` still carries its own inline copy of the same transaction dance. It is not ported here because it runs at enable on the raw connection before the executor exists and has a fail-closed version pre-check *outside* the transaction — re-pointing it is a low-value, non-zero-risk change with its own tests already green. Leave it; note it in your task report.

---

## Task 2: Schema Migration 2, the DAO, and the row types

**Files:**
- Modify: `src/main/java/org/xpfarm/farmersmarket/storage/Migrations.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/ListingStatus.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/ListingRow.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/TradeRow.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/PendingItemRow.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/SystemAccounts.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/MarketDao.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/storage/MigrationsTest.java` (extend)
- Test: `src/test/java/org/xpfarm/farmersmarket/market/MarketDaoTest.java`

**Interfaces:**
- Consumes: `Database`, `AccountRow`.
- Produces:
  - Migration 2, taking the schema to **version 2**.
  - `SystemAccounts.COMMUNITY_POT` → `UUID`; `SystemAccounts.isSystem(UUID)` → `boolean`.
  - `ListingStatus` enum: `ACTIVE`, `SOLD`, `CANCELLED`, `EXPIRED`.
  - `ListingRow(long id, UUID seller, ItemClass itemClass, String itemKey, String materialKey, String displayName, String summary, int amount, long priceDust, byte[] itemBytes, long listedAtEpochMs, long expiresAtEpochMs, ListingStatus status, Long soldAtEpochMs, UUID buyer)` — a record. `displayName`, `soldAtEpochMs`, and `buyer` are nullable.
  - `TradeRow(long id, long happenedAtEpochMs, UUID buyer, UUID seller, ItemClass itemClass, String itemKey, String materialKey, int amount, long grossDust, long taxDust, long taxBurnedDust, long taxPotDust, long netDust, Long listingId)` — a record.
  - `PendingItemRow(long id, UUID owner, byte[] itemBytes, int amount, String summary, String reason, long createdAtEpochMs, Long claimedAtEpochMs)` — a record.
  - `MarketDao(Database)` with the methods listed in Step 6.

`ItemClass` is referenced by these rows but is created in Task 3; declare the rows against it and let Task 3 satisfy the type. If executing strictly in order, create a minimal `ItemClass` enum (`COMMODITY`, `UNIQUE`) at the top of this task and let Task 3 add its `classify` method — note that in your report so the reviewer knows the enum landed early.

- [ ] **Step 1: Write the failing migration test**

Extend `MigrationsTest`. The load-bearing assertions are that the schema reaches version 2, that the trade-log triggers actually refuse mutation, and that the conservation `CHECK`s actually bite.

```java
@Test
void migratesToVersionTwoWithTheMarketTables(@TempDir Path dir) throws Exception {
    try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        assertEquals(2, Migrations.applyTo(db.connection()));
        assertTrue(tableExists(db.connection(), "listings"));
        assertTrue(tableExists(db.connection(), "trades"));
        assertTrue(tableExists(db.connection(), "pending_items"));
    }
}

@Test
void tradesRejectsUpdateAndDelete(@TempDir Path dir) throws Exception {
    try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        Migrations.applyTo(db.connection());
        insertConservingTrade(db.connection());   // gross=100, net=93, tax=7, burned=3, pot=4
        assertThrows(SQLException.class, () -> exec(db.connection(),
                "UPDATE trades SET gross_dust = 1 WHERE id = 1"),
                "trades is append-only; an UPDATE must be refused by the trigger");
        assertThrows(SQLException.class, () -> exec(db.connection(),
                "DELETE FROM trades WHERE id = 1"),
                "trades is append-only; a DELETE must be refused by the trigger");
    }
}

@Test
void tradesRefusesANonConservingRow(@TempDir Path dir) throws Exception {
    try (Database db = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        Migrations.applyTo(db.connection());
        // gross must equal net + tax; this row claims 100 = 90 + 7 and must be refused.
        assertThrows(SQLException.class, () -> exec(db.connection(),
                "INSERT INTO trades(happened_at,buyer_uuid,seller_uuid,item_class,item_key,"
              + "material_key,amount,gross_dust,tax_dust,tax_burned_dust,tax_pot_dust,net_dust)"
              + " VALUES (1,'b','s','UNIQUE','k','DIAMOND_SWORD',1,100,7,3,4,90)"));
    }
}
```

`insertConservingTrade`, `exec`, and any `tableExists`/`scalar` helper live in the test file (reuse the ones already in `MigrationsTest` where they exist).

- [ ] **Step 2: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MigrationsTest`
Expected: FAIL — schema is still version 1.

- [ ] **Step 3: Add Migration 2**

Append a `MIGRATION_2` `String[]` to `Migrations` and add it to the `MIGRATIONS` list (never mutate `MIGRATION_1` or reorder). Each string is one statement.

```sql
CREATE TABLE IF NOT EXISTS listings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_uuid     TEXT    NOT NULL,
    item_class      TEXT    NOT NULL,
    item_key        TEXT    NOT NULL,
    material_key    TEXT    NOT NULL,
    display_name    TEXT,
    summary         TEXT    NOT NULL,
    amount          INTEGER NOT NULL,
    price_dust      INTEGER NOT NULL,
    item_bytes      BLOB    NOT NULL,
    listed_at       INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'ACTIVE',
    sold_at         INTEGER,
    buyer_uuid      TEXT,
    CHECK (amount > 0),
    CHECK (price_dust > 0)
);
CREATE INDEX IF NOT EXISTS idx_listings_active ON listings(status, item_class, material_key);
CREATE INDEX IF NOT EXISTS idx_listings_seller ON listings(seller_uuid, status);

CREATE TABLE IF NOT EXISTS trades (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    happened_at     INTEGER NOT NULL,
    buyer_uuid      TEXT    NOT NULL,
    seller_uuid     TEXT    NOT NULL,
    item_class      TEXT    NOT NULL,
    item_key        TEXT    NOT NULL,
    material_key    TEXT    NOT NULL,
    amount          INTEGER NOT NULL,
    gross_dust      INTEGER NOT NULL,
    tax_dust        INTEGER NOT NULL,
    tax_burned_dust INTEGER NOT NULL,
    tax_pot_dust    INTEGER NOT NULL,
    net_dust        INTEGER NOT NULL,
    listing_id      INTEGER,
    CHECK (gross_dust = net_dust + tax_dust),
    CHECK (tax_dust = tax_burned_dust + tax_pot_dust),
    CHECK (gross_dust >= 0 AND tax_dust >= 0 AND net_dust >= 0
           AND tax_burned_dust >= 0 AND tax_pot_dust >= 0)
);
CREATE INDEX IF NOT EXISTS idx_trades_item ON trades(item_key, happened_at);

CREATE TRIGGER IF NOT EXISTS trades_no_update BEFORE UPDATE ON trades
BEGIN SELECT RAISE(ABORT, 'trades is append-only'); END;
CREATE TRIGGER IF NOT EXISTS trades_no_delete BEFORE DELETE ON trades
BEGIN SELECT RAISE(ABORT, 'trades is append-only'); END;

CREATE TABLE IF NOT EXISTS pending_items (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_uuid   TEXT    NOT NULL,
    item_bytes   BLOB    NOT NULL,
    amount       INTEGER NOT NULL,
    summary      TEXT    NOT NULL,
    reason       TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    claimed_at   INTEGER,
    CHECK (amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_pending_owner ON pending_items(owner_uuid, claimed_at);
```

The `CHECK (gross_dust = net_dust + tax_dust)` and `CHECK (tax_dust = tax_burned_dust + tax_pot_dust)` are the database refusing to record a trade that invented or destroyed money — the same principle as `accounts`' non-negative check, applied to the audit trail itself. The triggers make the row immutable once written.

- [ ] **Step 4: Run to verify the migration passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MigrationsTest`
Expected: PASS.

- [ ] **Step 5: Create the value types and `SystemAccounts`**

Records mirroring `AccountRow`'s shape (null-check the non-nullable reference fields in a compact constructor). `ListingStatus` parses case-insensitively from the stored `TEXT`. `SystemAccounts`:

```java
public final class SystemAccounts {
    /** The community pot: the nil UUID, which no real or Floodgate-synthetic player is assigned. */
    public static final UUID COMMUNITY_POT = new UUID(0L, 0L);
    private SystemAccounts() {}
    public static boolean isSystem(UUID uuid) { return COMMUNITY_POT.equals(uuid); }
}
```

- [ ] **Step 6: Write `MarketDao` and its tests**

Prepared statements only, exactly the shape of `AccountDao` — no method concatenates a caller value into SQL, and none opens its own transaction (callers wrap it in `DatabaseExecutor.submit`, and the sale wraps several calls in one `TransactionRunner.inTransaction`). Methods:

| Method | Behaviour |
|---|---|
| `long insertListing(ListingRow row)` | Insert; return the generated `id` via `Statement.RETURN_GENERATED_KEYS`. `status` written from `row.status()`. |
| `Optional<ListingRow> findListing(long id)` | Full row by id, any status. |
| `Optional<ListingRow> findActiveListing(long id)` | Row by id **only if** `status = 'ACTIVE'`. This is the one the sale reads — a sold or cancelled listing is invisible to it. |
| `List<ListingRow> browseActive(String materialLike, int limit, int offset)` | `status='ACTIVE' AND item_class='UNIQUE'`, optional `material_key LIKE ?`, ordered `listed_at DESC`, paged. |
| `List<ListingRow> listingsBySeller(UUID seller, ListingStatus status)` | Seller's rows in one status, newest first. |
| `int countActiveBySeller(UUID seller)` | For the max-listings-per-player gate. |
| `void markSold(long id, UUID buyer, long soldAtEpochMs)` | `UPDATE ... SET status='SOLD', buyer_uuid=?, sold_at=? WHERE id=? AND status='ACTIVE'`; caller checks the affected-row count is 1. |
| `void markStatus(long id, ListingStatus status, long atEpochMs)` | `CANCELLED`/`EXPIRED` transitions, `WHERE id=? AND status='ACTIVE'`. |
| `List<ListingRow> dueForExpiry(long nowEpochMs, int limit)` | `status='ACTIVE' AND expires_at <= ?`, bounded. |
| `void insertTrade(TradeRow row)` | Insert only — there is no update or delete method for `trades`, by design, and the triggers enforce it even if one were added. |
| `long insertPending(PendingItemRow row)` | Insert an owed item; return `id`. |
| `List<PendingItemRow> unclaimedFor(UUID owner)` | `claimed_at IS NULL`, oldest first. |
| `void markClaimed(long id, long atEpochMs)` | `UPDATE ... SET claimed_at=? WHERE id=? AND claimed_at IS NULL`. |

Tests (real SQLite in `@TempDir`, no mocks) must cover, each with a named behaviour a mutation would break:
- insert-then-read round-trips every column, `item_bytes` included (`assertArrayEquals`);
- `findActiveListing` returns the row while `ACTIVE` and empty after `markSold`;
- `markSold` affects exactly one row, and a second `markSold` on the same id affects zero (the sale's double-buy guard depends on this);
- `countActiveBySeller` counts only `ACTIVE`;
- `insertTrade` then read-back is faithful, and a conservation-violating `TradeRow` is refused by the `CHECK` (wrap the insert, assert `SQLException`);
- `unclaimedFor` omits a row after `markClaimed`.

- [ ] **Step 7: Run the market DAO tests**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='MigrationsTest,MarketDaoTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/storage/Migrations.java src/main/java/org/xpfarm/farmersmarket/market src/test/java/org/xpfarm/farmersmarket/storage/MigrationsTest.java src/test/java/org/xpfarm/farmersmarket/market/MarketDaoTest.java
git commit -m "feat(market): schema for listings, escrow, and the append-only trade log"
```

---

## Task 3: Item identity — classify, hash, serialize

**Files:**
- Create/extend: `src/main/java/org/xpfarm/farmersmarket/market/ItemClass.java` (add `classify`)
- Create: `src/main/java/org/xpfarm/farmersmarket/market/ItemKey.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/ListedItem.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/BukkitItemCodec.java` (the one Bukkit adapter)
- Test: `src/test/java/org/xpfarm/farmersmarket/market/ItemClassTest.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/market/ItemKeyTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `ItemClass.classify(int maxStackSize, boolean hasMeaningfulComponents)` → `ItemClass` (static, pure).
  - `ItemKey.forUnique(byte[] itemBytes)` → `String`; `ItemKey.sha256Hex(byte[])` → `String`.
  - `ListedItem(ItemClass itemClass, String itemKey, String materialKey, String displayName, String summary, int amount, byte[] itemBytes)` — a record; `displayName` nullable.
  - `BukkitItemCodec.encode(ItemStack)` → `ListedItem`; `BukkitItemCodec.decode(ListedItem)` → `ItemStack`; `BukkitItemCodec.decode(byte[])` → `ItemStack`.

**The classification rule lives in `ItemClass` as a pure function over two primitives, not in the codec.** The codec's job is only to *derive* those two primitives from a live `ItemStack`; the *decision* is testable without a server. This is the same seam `MarketResolver` uses for command decisions and the reason the money bugs in M1 were all catchable by unit tests.

- [ ] **Step 1: Write the failing classification tests**

The rule: an item is a `COMMODITY` only if it both stacks (`maxStackSize > 1`) **and** carries no meaningful component data. Everything else is `UNIQUE`. A single-stack item (a tool, armour) is `UNIQUE` even when plain, because it cannot be pooled fungibly; a stackable item with enchantments, a custom name, damage, custom model data, or container contents is `UNIQUE` because it will not match a plain one and swallowing it into a fungible pool would destroy what made it worth listing (this is exactly the ChestShop "Anvil Wizardry" bug class the split market exists to avoid).

```java
@Test
void plainStackableIsCommodity() {
    assertEquals(ItemClass.COMMODITY, ItemClass.classify(64, false));
    assertEquals(ItemClass.COMMODITY, ItemClass.classify(16, false));
}

@Test
void anythingWithMeaningfulComponentsIsUnique() {
    assertEquals(ItemClass.UNIQUE, ItemClass.classify(64, true));
    assertEquals(ItemClass.UNIQUE, ItemClass.classify(1, true));
}

@Test
void aSingleStackItemIsUniqueEvenWhenPlain() {
    // A diamond sword stacks to 1; it cannot be pooled as a fungible commodity.
    assertEquals(ItemClass.UNIQUE, ItemClass.classify(1, false));
}
```

- [ ] **Step 2: Write the failing key tests**

The key is a stable content hash of the exact bytes: the same item hashes identically, a different item does not, and the prefix marks it a unique key so a later commodity key (Part 2) cannot collide with it.

```java
@Test
void identicalBytesHashIdentically() {
    byte[] a = {1, 2, 3, 4};
    assertEquals(ItemKey.forUnique(a), ItemKey.forUnique(new byte[] {1, 2, 3, 4}));
}

@Test
void differentBytesHashDifferently() {
    assertNotEquals(ItemKey.forUnique(new byte[] {1, 2, 3}), ItemKey.forUnique(new byte[] {1, 2, 4}));
}

@Test
void aUniqueKeyIsMarkedAsOne() {
    assertTrue(ItemKey.forUnique(new byte[] {9}).startsWith("u:"));
}

@Test
void theHashIsStableAcrossRuns() {
    // Pinned so a switch of hash algorithm is a visible, deliberate change, never an accident:
    // stored item_keys in a live database would otherwise silently stop matching.
    assertEquals("u:9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
            ItemKey.forUnique(new byte[] {0}));
}
```

Compute the pinned value with `printf '\0' | sha256sum` (or a scratch `MessageDigest` call) rather than trusting this text; if it differs, use the real digest and record that you corrected it.

- [ ] **Step 3: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='ItemClassTest,ItemKeyTest'`
Expected: FAIL — classes/methods do not exist.

- [ ] **Step 4: Implement `ItemClass`, `ItemKey`, `ListedItem`**

```java
public enum ItemClass {
    COMMODITY, UNIQUE;

    public static ItemClass classify(int maxStackSize, boolean hasMeaningfulComponents) {
        return (maxStackSize > 1 && !hasMeaningfulComponents) ? COMMODITY : UNIQUE;
    }
}
```

```java
public final class ItemKey {
    private ItemKey() {}

    public static String forUnique(byte[] itemBytes) {
        return "u:" + sha256Hex(itemBytes);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; its absence is not a runtime outcome to handle.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

`ListedItem` is a record. Because `byte[]` has identity equality, add a compact constructor that defensively copies `itemBytes` and a javadoc note that instances are compared by identity, not value — nothing in the market relies on `ListedItem.equals`. Provide an accessor returning a copy if you want to be strict, or document the shared-array caveat; do not spend effort building value equality no caller needs.

- [ ] **Step 5: Implement `BukkitItemCodec`**

The only class in `market` that imports `org.bukkit.*`. It derives the two classification primitives and the display fields from a live `ItemStack`, then hands the rest to the pure classes.

- `encode(ItemStack stack)`:
  - `byte[] bytes = stack.serializeAsBytes();`
  - `int amount = stack.getAmount();`
  - `String materialKey = stack.getType().getKey().getKey().toUpperCase(Locale.ROOT);` (or `stack.getType().name()` — pick one and be consistent with what `browseActive`'s `LIKE` searches);
  - `boolean meaningful = hasMeaningfulComponents(stack);`
  - `ItemClass itemClass = ItemClass.classify(stack.getMaxStackSize(), meaningful);`
  - `String itemKey = ItemKey.forUnique(bytes);` (Part 1 lists only uniques; Part 2 adds a commodity key path);
  - `String displayName = displayNameText(stack);` (the plain string of the custom name, or `null`);
  - `String summary = summarise(stack);`
  - return `new ListedItem(itemClass, itemKey, materialKey, displayName, summary, amount, bytes)`.
- `hasMeaningfulComponents(ItemStack)` returns true if any of: `stack.getEnchantments()` non-empty (or stored enchantments on an enchanted book), the meta has a display name, the item is damaged (`Damageable#getDamage() > 0`), it has custom model data, it has lore, or it is a container with contents (a shulker box or other `BlockStateMeta` whose `BlockState` is an `InventoryHolder` holding non-empty stacks). Keep the check readable and conservative: **when in doubt, return true** — a false "commodity" is the dangerous direction, because it would let a named item be pooled.
- `summarise(ItemStack)` returns Bedrock-safe plain text: the material name in words plus the custom name if present, and for a container, a short content line — e.g. `"Shulker Box — 27 items (Diamond x64, Iron Ingot x12, …)"`. Cap the enumerated contents at a handful and append `"…"`. **This text is the only thing a Bedrock player will read about a shulker's contents in Part 1, because the item icon is never rendered (Geyser #3001).** No colour codes, no glyphs.
- `decode(ListedItem item)` → `decode(item.itemBytes())`; `decode(byte[] bytes)` → `ItemStack.deserializeBytes(bytes)`.

`BukkitItemCodec` needs a live server to run `serializeAsBytes`/`deserializeBytes`, so it is exercised at gate 7a, not by a unit test — the same boundary `EditionResolver`'s reflective half sits behind. Keep every decision it makes delegated to `ItemClass`/`ItemKey` so the untested surface is as thin as possible. Add a class javadoc saying exactly that.

- [ ] **Step 6: Run to verify pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='ItemClassTest,ItemKeyTest'`
Expected: PASS.

- [ ] **Step 7: Mutation-check the classification**

Change `classify` to `return COMMODITY;` and confirm `anythingWithMeaningfulComponentsIsUnique` and `aSingleStackItemIsUniqueEvenWhenPlain` fail; restore. A green run here would mean the split-market safety rule is unpinned.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/market/ItemClass.java src/main/java/org/xpfarm/farmersmarket/market/ItemKey.java src/main/java/org/xpfarm/farmersmarket/market/ListedItem.java src/main/java/org/xpfarm/farmersmarket/market/BukkitItemCodec.java src/test/java/org/xpfarm/farmersmarket/market/ItemClassTest.java src/test/java/org/xpfarm/farmersmarket/market/ItemKeyTest.java
git commit -m "feat(market): item identity, classification, and the ItemStack codec"
```

---

## Task 4: The fee and tax math

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/market/MarketMath.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/market/MarketMathTest.java`

**Interfaces:**
- Consumes: `Diamonds`.
- Produces:
  - `MarketMath.listingFeeXp(Diamonds price, double listingFeePercent, int xpPerDiamond)` → `int` (XP points).
  - `MarketMath.taxOnSale(Diamonds gross, double salesTaxPercent, double taxBurnShare)` → `TaxSplit`.
  - `MarketMath.TaxSplit(Diamonds gross, Diamonds net, Diamonds tax, Diamonds burned, Diamonds toPot)` — a record.

**This is the money math, and its whole job is to conserve.** For any sale, `gross == net + tax` and `tax == burned + toPot`, exactly, to the dust — not because rounding happens to line up, but because `net` is *defined* as `gross − tax` and `toPot` as `tax − burned`, so the remainder always lands somewhere and money is neither invented nor destroyed. That structural conservation is what the `trades` table's `CHECK` constraints (Task 2) then re-verify at the database. The `double` percents are config-supplied rates; each is turned into an integer basis-point count once, and no floating point touches a dust amount.

- [ ] **Step 1: Write the failing tests**

```java
private static final int XP_PER_DIAMOND = 40;

@Test
void listingFeeIsOnePercentPricedInXp() {
    // 100 diamonds, 1% fee => 1 diamond-equivalent => 40 XP points.
    assertEquals(40, MarketMath.listingFeeXp(Diamonds.ofDiamonds(100), 1.0, XP_PER_DIAMOND));
}

@Test
void aTinyListingStillCostsAtLeastOneXp() {
    // Rounds up: any listing costs something, or the anti-spam fee does nothing.
    assertEquals(1, MarketMath.listingFeeXp(Diamonds.ofDiamonds(1), 1.0, XP_PER_DIAMOND));
}

@Test
void aZeroPercentFeeIsFree() {
    assertEquals(0, MarketMath.listingFeeXp(Diamonds.ofDiamonds(1000), 0.0, XP_PER_DIAMOND));
}

@Test
void taxSplitsSevenPercentHalfBurnedHalfToPot() {
    MarketMath.TaxSplit split = MarketMath.taxOnSale(Diamonds.ofDiamonds(100), 7.0, 0.5);
    assertEquals(7_000L, split.tax().dust());       // 7% of 100 diamonds = 7 diamonds
    assertEquals(93_000L, split.net().dust());      // seller keeps 93
    assertEquals(3_500L, split.burned().dust());    // half of tax burned
    assertEquals(3_500L, split.toPot().dust());     // half to the pot
}

@Test
void grossAlwaysEqualsNetPlusTaxAndTaxAlwaysEqualsBurnedPlusPot() {
    Random r = new Random(20260728L);
    for (int i = 0; i < 20_000; i++) {
        long grossDust = r.nextLong(0, 1_000_000_000L);
        double taxPct = r.nextDouble(0.0, 100.0);
        double burnShare = r.nextDouble(0.0, 1.0);
        MarketMath.TaxSplit s = MarketMath.taxOnSale(Diamonds.ofDust(grossDust), taxPct, burnShare);
        assertEquals(grossDust, s.net().dust() + s.tax().dust(),
                "gross must equal net + tax, or the sale invented or destroyed money");
        assertEquals(s.tax().dust(), s.burned().dust() + s.toPot().dust(),
                "tax must equal burned + pot");
        assertTrue(s.net().dust() >= 0 && s.tax().dust() >= 0
                && s.burned().dust() >= 0 && s.toPot().dust() >= 0,
                "no component of a sale may be negative");
    }
}

@Test
void noComponentEverExceedsTheGross() {
    MarketMath.TaxSplit s = MarketMath.taxOnSale(Diamonds.ofDiamonds(50), 100.0, 1.0);
    assertTrue(s.tax().dust() <= s.gross().dust());
    assertEquals(0L, s.net().dust());  // a 100% tax leaves the seller nothing, but never less
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MarketMathTest`
Expected: FAIL — `MarketMath` does not exist.

- [ ] **Step 3: Implement `MarketMath`**

```java
public final class MarketMath {

    private MarketMath() {}

    /** A percent such as 7.0 as basis points of 10_000 (700). Converts a config RATE, never a balance. */
    static long basisPoints(double percent) {
        return Math.round(percent * 100.0);
    }

    /** A share such as 0.5 as basis points of 10_000 (5000). */
    static long shareBasisPoints(double share) {
        return Math.round(share * 10_000.0);
    }

    /**
     * The XP listing fee for a listing priced at {@code price}, rounded up so any listing costs
     * something. XP is not money in the {@link Diamonds} sense, so this returns an {@code int} of
     * points; overflow is refused via {@link Math#toIntExact}, never wrapped.
     */
    public static int listingFeeXp(Diamonds price, double listingFeePercent, int xpPerDiamond) {
        long bp = basisPoints(listingFeePercent);
        long numerator = Math.multiplyExact(Math.multiplyExact(price.dust(), bp), (long) xpPerDiamond);
        long denom = 10_000L * Diamonds.DUST_PER_DIAMOND;
        return Math.toIntExact(ceilDiv(numerator, denom));
    }

    public record TaxSplit(Diamonds gross, Diamonds net, Diamonds tax, Diamonds burned, Diamonds toPot) {}

    /**
     * Splits a gross sale price into the seller's net, the burned tax, and the pot tax.
     * Conserves to the dust: {@code net = gross - tax} and {@code toPot = tax - burned}, so the
     * three parts always re-sum to the whole regardless of rounding.
     */
    public static TaxSplit taxOnSale(Diamonds gross, double salesTaxPercent, double taxBurnShare) {
        long taxBp = basisPoints(salesTaxPercent);
        long taxDust = Math.multiplyExact(gross.dust(), taxBp) / 10_000L;
        long netDust = gross.dust() - taxDust;
        long burnedDust = Math.multiplyExact(taxDust, shareBasisPoints(taxBurnShare)) / 10_000L;
        long potDust = taxDust - burnedDust;
        return new TaxSplit(gross, Diamonds.ofDust(netDust), Diamonds.ofDust(taxDust),
                Diamonds.ofDust(burnedDust), Diamonds.ofDust(potDust));
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MarketMathTest`
Expected: PASS.

- [ ] **Step 5: Mutation-check conservation**

Change `long netDust = gross.dust() - taxDust;` to `long netDust = gross.dust();` (the classic "buyer's-favour rounding" money leak) and confirm `grossAlwaysEqualsNetPlusTax...` fails; restore. Then change `potDust = taxDust - burnedDust;` to `potDust = taxDust;` and confirm the same test fails; restore.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/market/MarketMath.java src/test/java/org/xpfarm/farmersmarket/market/MarketMathTest.java
git commit -m "feat(market): conserving integer fee and sales-tax arithmetic"
```

---

## Task 5: `MarketService` — list, buy, cancel, expire, claim

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/market/MarketException.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/SaleResult.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/MarketService.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/market/MarketServiceTest.java`

**Interfaces:**
- Consumes: `Database`, `DatabaseExecutor`, `TransactionRunner`, `AccountDao`, `MarketDao`, `MarketMath`, `Diamonds`, `SystemAccounts`, `ListedItem`, and the row types.
- Produces:
  - `new MarketService(Database, DatabaseExecutor, AccountDao, MarketDao)` — constructs its own `TransactionRunner(Database)` internally, exactly as `Ledger` does.
  - `CompletableFuture<Long> list(UUID seller, ListedItem item, Diamonds price, int maxListings, long nowEpochMs, long listingDurationDays)` — inserts the escrow listing; the XP fee is charged by the command layer *after* this succeeds. Fails with `MarketException(TOO_MANY_LISTINGS)` if the seller is at the cap, `MarketException(NOTHING_WRITTEN)` if the pre-insert read failed.
  - `CompletableFuture<SaleResult> buy(UUID buyer, long listingId, double salesTaxPercent, double taxBurnShare, long nowEpochMs)` — the atomic sale.
  - `CompletableFuture<byte[]> cancel(UUID seller, long listingId, long nowEpochMs)` — flips the listing to `CANCELLED` and returns the escrow bytes for the command layer to hand back.
  - `CompletableFuture<Integer> expireDue(long nowEpochMs, int batchLimit)` — sweeps `ACTIVE` listings past `expires_at` to `EXPIRED`, moving each item to `pending_items` for the seller; returns how many were swept.
  - Read helpers returning futures: `browse(String materialLike, int page, int pageSize)`, `findListing(long id)`, `myListings(UUID seller)`, `communityPotBalance()`, `pendingFor(UUID owner)`.
  - `CompletableFuture<PendingItemRow> claimOne(UUID owner, long pendingId, long nowEpochMs)` — marks one owed item claimed and returns it; the command layer delivers it. (Claim-all is a loop in the command layer over `pendingFor`.)

**`SaleResult`** carries what the command layer needs to finish on the main thread: `byte[] itemBytes`, `int amount`, `String summary`, `UUID seller`, `MarketMath.TaxSplit split`, `long listingId`. The item bytes come back so the buyer can be handed the item *after* the transaction has committed — the money and the audit row are final before any inventory is touched.

**The sale is the operation M1's `Ledger` javadoc was written for.** It runs as one `TransactionRunner.inTransaction` body over `AccountDao` and `MarketDao`, on the executor thread. It does not call `Ledger.transfer`. Every balance change goes through `Diamonds` arithmetic — never a raw `long` add — so an overflow refuses with `AMOUNT_TOO_LARGE` instead of wrapping a balance negative, exactly as `mergeAccounts` does.

- [ ] **Step 1: Write `MarketException`**

Mirror `LedgerException`: extends `RuntimeException`, carries a `Reason` enum and an optional cause. Reasons: `LISTING_UNAVAILABLE`, `SELF_PURCHASE`, `INSUFFICIENT_FUNDS`, `NOT_YOUR_LISTING`, `TOO_MANY_LISTINGS`, `COMMODITY_NOT_YET`, `NOTHING_WRITTEN`, `AMOUNT_TOO_LARGE`. The command layer maps each to a player sentence; the service never formats player text. Follow `LedgerException`'s "the cause's type is the contract" split — a `MarketException` means refused and nothing was written; any other cause is an unknown outcome.

- [ ] **Step 2: Write the failing service tests**

Real `Database` + real `DatabaseExecutor` in a `@TempDir`; join the futures. Fund buyers by writing balances through `AccountDao` directly (the market takes ledger balances as given). The critical cases:

```java
@Test
void aSaleMovesMoneyConservesItAndLogsExactlyOneTrade() throws Exception {
    fund(BUYER, Diamonds.ofDiamonds(200));
    long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40,
            NOW, 14).get();

    SaleResult r = service.buy(BUYER, id, 7.0, 0.5, NOW).get();

    // Money conservation across the four parties, to the dust.
    assertEquals(100_000L, currentBalance(BUYER).dust(), "buyer balance fell by exactly the gross");
    assertEquals(93_000L, currentBalance(SELLER).dust(), "seller received net");
    assertEquals(3_500L, currentBalance(SystemAccounts.COMMUNITY_POT).dust(), "pot received its half");
    // Burned diamonds are credited to nobody: total ledger supply fell by exactly the burn.
    long supplyAfter = currentBalance(BUYER).dust() + currentBalance(SELLER).dust()
            + currentBalance(SystemAccounts.COMMUNITY_POT).dust();
    assertEquals(200_000L - 3_500L, supplyAfter, "the only diamonds that left the world are the burn");
    assertEquals(1, tradeCount(), "exactly one trade row");
}

@Test
void aSoldListingCannotBeBoughtAgain() throws Exception {
    fund(BUYER, Diamonds.ofDiamonds(200));
    fund(OTHER, Diamonds.ofDiamonds(200));
    long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();

    service.buy(BUYER, id, 7.0, 0.5, NOW).get();
    ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> service.buy(OTHER, id, 7.0, 0.5, NOW).get());

    assertInstanceOf(MarketException.class, thrown.getCause());
    assertEquals(MarketException.Reason.LISTING_UNAVAILABLE,
            ((MarketException) thrown.getCause()).reason());
    assertEquals(1, tradeCount(), "the second attempt logged no trade");
    assertEquals(Diamonds.ofDiamonds(200).dust(), currentBalance(OTHER).dust(),
            "the second buyer was not charged");
}

@Test
void anUnaffordablePurchaseChangesNothing() throws Exception {
    fund(BUYER, Diamonds.ofDiamonds(50));
    long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();

    assertThrows(ExecutionException.class, () -> service.buy(BUYER, id, 7.0, 0.5, NOW).get());

    assertEquals(50_000L, currentBalance(BUYER).dust());
    assertEquals(0L, currentBalance(SELLER).dust());
    assertEquals(0, tradeCount());
    assertTrue(service.findListing(id).get().orElseThrow().status() == ListingStatus.ACTIVE,
            "a refused sale leaves the listing on sale");
}

@Test
void cancellingReturnsTheEscrowBytesAndTakesTheListingDown() throws Exception {
    byte[] original = uniqueItem().itemBytes();
    long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();

    byte[] returned = service.cancel(SELLER, id, NOW).get();

    assertArrayEquals(original, returned, "the exact escrowed bytes come back");
    assertEquals(ListingStatus.CANCELLED, service.findListing(id).get().orElseThrow().status());
}

@Test
void onlyTheSellerCanCancel() throws Exception {
    long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(100), 40, NOW, 14).get();
    ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> service.cancel(OTHER, id, NOW).get());
    assertEquals(MarketException.Reason.NOT_YOUR_LISTING,
            ((MarketException) thrown.getCause()).reason());
}

@Test
void theListingCapIsEnforced() throws Exception {
    for (int i = 0; i < 2; i++) {
        service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(10), 2, NOW, 14).get();
    }
    ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(10), 2, NOW, 14).get());
    assertEquals(MarketException.Reason.TOO_MANY_LISTINGS,
            ((MarketException) thrown.getCause()).reason());
}

@Test
void expirySweepsDueListingsAndOwesTheItemToTheSeller() throws Exception {
    long id = service.list(SELLER, uniqueItem(), Diamonds.ofDiamonds(10), 40, NOW, 14).get();
    long later = NOW + java.time.Duration.ofDays(15).toMillis();

    assertEquals(1, service.expireDue(later, 100).get());

    assertEquals(ListingStatus.EXPIRED, service.findListing(id).get().orElseThrow().status());
    assertEquals(1, service.pendingFor(SELLER).get().size(), "the seller is owed the expired item");
}
```

`uniqueItem()`, `fund`, `currentBalance`, `tradeCount`, and the UUID constants are test helpers. `uniqueItem()` returns a `ListedItem` built by hand (no Bukkit) — e.g. `new ListedItem(ItemClass.UNIQUE, "u:abc", "DIAMOND_SWORD", "Excalibur", "Diamond Sword — Excalibur", 1, new byte[]{7,7,7})`.

- [ ] **Step 3: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MarketServiceTest`
Expected: FAIL — `MarketService` does not exist.

- [ ] **Step 4: Implement `MarketService.buy` (the atomic sale)**

Every method wraps its body in `executor.submit(() -> ...)`. `buy`'s body is one `transactions.inTransaction`:

```java
public CompletableFuture<SaleResult> buy(UUID buyer, long listingId,
        double salesTaxPercent, double taxBurnShare, long nowEpochMs) {
    Objects.requireNonNull(buyer, "buyer");
    return executor.submit(() -> transactions.inTransaction(() -> {
        ListingRow listing = market.findActiveListing(listingId)
                .orElseThrow(() -> new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                        "listing " + listingId + " is not on sale"));
        if (listing.seller().equals(buyer)) {
            throw new MarketException(MarketException.Reason.SELF_PURCHASE,
                    "a seller cannot buy their own listing");
        }

        Diamonds gross = Diamonds.ofDust(listing.priceDust());
        MarketMath.TaxSplit split = MarketMath.taxOnSale(gross, salesTaxPercent, taxBurnShare);

        Diamonds buyerBalance = Diamonds.ofDust(accounts.balanceDust(buyer));
        Diamonds afterBuyer = buyerBalance.minus(gross);
        if (afterBuyer.isNegative()) {
            throw new MarketException(MarketException.Reason.INSUFFICIENT_FUNDS,
                    buyer + " holds " + buyerBalance.format() + " but the price is " + gross.format());
        }

        // Every balance change goes through Diamonds, never a raw long add, so an overflow
        // refuses with AMOUNT_TOO_LARGE instead of wrapping a balance negative.
        accounts.upsertBalance(buyer, afterBuyer.dust());
        Diamonds sellerAfter = Diamonds.ofDust(accounts.balanceDust(listing.seller())).plus(split.net());
        accounts.upsertBalance(listing.seller(), sellerAfter.dust());
        Diamonds potAfter = Diamonds.ofDust(accounts.balanceDust(SystemAccounts.COMMUNITY_POT))
                .plus(split.toPot());
        accounts.upsertBalance(SystemAccounts.COMMUNITY_POT, potAfter.dust());
        // split.burned() is credited to nobody — that is the sink.

        market.markSold(listingId, buyer, nowEpochMs);
        market.insertTrade(new TradeRow(0L, nowEpochMs, buyer, listing.seller(),
                listing.itemClass(), listing.itemKey(), listing.materialKey(), listing.amount(),
                gross.dust(), split.tax().dust(), split.burned().dust(), split.toPot().dust(),
                split.net().dust(), listingId));

        return new SaleResult(listing.itemBytes(), listing.amount(), listing.summary(),
                listing.seller(), split, listingId);
    }));
}
```

Because `Diamonds` throws `LedgerException(AMOUNT_TOO_LARGE)` on overflow and that is not a `MarketException`, catch it at the `MarketService` boundary and rethrow as `MarketException(AMOUNT_TOO_LARGE, ..., cause)` so the command layer has one refusal type to map — or let the command layer's message mapping accept both. Pick one and state it in the class javadoc; the overflow path is unreachable at this server's scale but must stay a *refusal* (nothing was written — it throws before the first `upsertBalance` only if the price itself overflowed, which it cannot, since it came from a stored `price_dust`; the seller-credit and pot-credit overflows would throw mid-transaction and roll back cleanly, so they are refusals too).

- [ ] **Step 5: Implement `list`, `cancel`, `expireDue`, `claimOne`, and the read helpers**

- `list`: read `market.countActiveBySeller(seller)`; if `>= maxListings`, throw `TOO_MANY_LISTINGS`. Wrap the count read the way `Ledger.readBeforeWriting` does — a storage failure before the insert is `NOTHING_WRITTEN`, a definite refusal, so the command layer returns the item and charges no fee. Then `market.insertListing(...)` with `status=ACTIVE`, `expires_at = nowEpochMs + Duration.ofDays(listingDurationDays)`. This is a single insert; it needs no transaction (one statement on the single writer thread). Return the new id.
- `cancel`: one `inTransaction` — `findActiveListing(id)`; if empty → `LISTING_UNAVAILABLE`; if `seller != caller` → `NOT_YOUR_LISTING`; `market.markStatus(id, CANCELLED, now)`; return `listing.itemBytes()`. The read-check-write is wrapped so the status can't change under it.
- `expireDue`: one `inTransaction` — `dueForExpiry(now, batchLimit)`; for each, `markStatus(id, EXPIRED, now)` and `insertPending(new PendingItemRow(0, seller, bytes, amount, summary, "EXPIRED", now, null))`; return the count. Batching keeps a long-idle server's first sweep bounded.
- `claimOne`: one `inTransaction` — read the pending row, verify `owner == caller` and `claimed_at IS NULL`; `markClaimed(id, now)`; return the row. The command layer delivers the bytes and, only if delivery physically fails, does nothing to reverse the claim (it re-holds via a fresh pending row — see Task 7).
- Read helpers submit a read `Callable` (no transaction). `browse` computes `offset = (page - 1) * pageSize`. `communityPotBalance()` returns `Diamonds.ofDust(accounts.balanceDust(SystemAccounts.COMMUNITY_POT))`.

- [ ] **Step 6: Run to verify pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MarketServiceTest`
Expected: PASS.

- [ ] **Step 7: Mutation-check the sale**

Three deletions, each must turn a test red; restore after each:
1. Delete the pot credit (`accounts.upsertBalance(SystemAccounts.COMMUNITY_POT, ...)`) → `aSaleMovesMoneyConservesItAndLogsExactlyOneTrade` fails on the pot or supply assertion.
2. Change `findActiveListing` to `findListing` → `aSoldListingCannotBeBoughtAgain` fails (a sold listing becomes buyable again).
3. Delete the `afterBuyer.isNegative()` guard → `anUnaffordablePurchaseChangesNothing` fails (the buyer goes negative or the CHECK constraint throws mid-transaction, either way the assertion of an unchanged state breaks).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/market/MarketException.java src/main/java/org/xpfarm/farmersmarket/market/SaleResult.java src/main/java/org/xpfarm/farmersmarket/market/MarketService.java src/test/java/org/xpfarm/farmersmarket/market/MarketServiceTest.java
git commit -m "feat(market): the atomic sale, listing, cancel, and expiry service"
```

---

## Task 6: Extend `MarketResolver` with the market decisions

**Files:**
- Modify: `src/main/java/org/xpfarm/farmersmarket/command/MarketResolver.java`
- Modify: `src/test/java/org/xpfarm/farmersmarket/command/MarketResolverTest.java`

**Interfaces:**
- Consumes: `Diamonds`, `MarketException`.
- Produces: new `Sub` values, new `Outcome` values, a resolved `Resolution` carrying a parsed price or listing id or page, message constants, and `messageFor(MarketException.Reason)`.

**Every new decision goes here, pure, before any of it reaches `MarketCommand`.** This is the same discipline M1 used and the resolution to the open concern the kickoff carried forward: the market's argument parsing, permission gating, self-purchase rejection, the commodity refusal, and the reason-to-sentence mapping are all reachable by a unit test because they live in this Bukkit-free class. `MarketCommand` is left with only the parts that genuinely need a live server.

- [ ] **Step 1: Write the failing resolver tests**

New subcommands: `sell <price>`, `browse [page]`, `info <id>`, `buy <id>`, `cancel <id>`, `mine`, `claim`, `pot`. Add to the existing `MarketResolverTest`.

```java
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
    assertTrue(MarketResolver.messageFor(MarketException.Reason.COMMODITY_NOT_YET)
            .toLowerCase(Locale.ROOT).contains("unique"));
}
```

Extend the `Resolution` record with `priceDust`, `listingId`, and `page` fields (or a small carrier) — keep `diamonds` and `message` for the existing deposit/withdraw paths. Add helper accessors used above.

- [ ] **Step 2: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MarketResolverTest`
Expected: FAIL — the new `Sub`/`Outcome` values do not exist.

- [ ] **Step 3: Extend the resolver**

- Add to `Sub`: `SELL("sell", USE_PERMISSION, true)`, `BROWSE("browse", USE_PERMISSION, false)`, `INFO("info", USE_PERMISSION, false)`, `BUY("buy", USE_PERMISSION, true)`, `CANCEL("cancel", USE_PERMISSION, true)`, `MINE("mine", USE_PERMISSION, true)`, `CLAIM("claim", USE_PERMISSION, true)`, `POT("pot", USE_PERMISSION, false)`. All under `farmersmarket.use`; read-only views set `needsPlayer=false`.
- Add to `Outcome`: `SELL`, `BROWSE`, `INFO`, `BUY`, `CANCEL`, `MINE`, `CLAIM`, `POT`, plus the error outcomes `MISSING_ID`, `BAD_ID`, `BAD_PAGE`. Extend `isError()` to include the three new error outcomes.
- Parse price via `Diamonds.parse` (reuse the existing `amount(...)` helper's grammar; a sell price may be fractional, unlike a physical-diamond withdraw, so do **not** apply the whole-diamonds check here — allow any positive `Diamonds`). Refuse a non-positive price as `BAD_AMOUNT`, a missing one as `MISSING_AMOUNT`.
- Parse an id as a positive `long` (`> 0`), refusing `BAD_ID`/`MISSING_ID`. Parse a page as a positive `int` defaulting to 1, refusing `BAD_PAGE`.
- Add `messageFor(MarketException.Reason)` mapping each reason to one plain sentence: `LISTING_UNAVAILABLE` → "That listing is no longer available."; `SELF_PURCHASE` → "You cannot buy your own listing."; `INSUFFICIENT_FUNDS` → "You do not have enough diamonds. Deposit some with /market deposit."; `NOT_YOUR_LISTING` → "That is not your listing."; `TOO_MANY_LISTINGS` → "You have too many listings up. Cancel or sell one first."; `COMMODITY_NOT_YET` → "Only unique items — enchanted, renamed, damaged, or custom — can be listed right now. Bulk trading is coming soon."; `NOTHING_WRITTEN` → "The market could not be reached, so nothing was changed. Try again in a moment."; `AMOUNT_TOO_LARGE` → "That amount is too large."
- Update `usage()` and add the new tokens to tab completion (they flow automatically from the `Sub` enum via `allowedSubcommandTokens`).

- [ ] **Step 4: Run to verify pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MarketResolverTest`
Expected: PASS.

- [ ] **Step 5: Mutation-check the console gate**

Change `BUY`'s `needsPlayer` to `false` and confirm `infoAndBrowseAndPotAreAllowedFromTheConsole` fails on the `buy` assertion; restore. This pins that a money-moving subcommand cannot be run from a console that has no inventory to pay from.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/command/MarketResolver.java src/test/java/org/xpfarm/farmersmarket/command/MarketResolverTest.java
git commit -m "feat(command): resolve the market subcommands as pure decisions"
```

---

## Task 7: Wire the market into `/market` and the plugin

**Files:**
- Modify: `src/main/java/org/xpfarm/farmersmarket/command/MarketCommand.java`
- Modify: `src/main/java/org/xpfarm/farmersmarket/FarmersMarketPlugin.java`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java`

**Interfaces:**
- Consumes: `MarketService`, `BukkitItemCodec`, `MarketResolver`, `FmConfig`, everything prior.
- Produces: the runtime market. No later task in this plan depends on it.

`MarketCommand` gains the item-and-XP half of each market decision; every ledger/market result still crosses back to the main thread through the **existing `onMainThread(future, id, what, handler)` seam**, inheriting M1's shutdown-reconciliation log line for free (an obligation the kickoff makes explicit: every new money-moving operation must use that seam). Nothing new touches an inventory or an XP bar off the main thread.

- [ ] **Step 1: Update `PluginDescriptorTest` and `plugin.yml`**

Set the `usage:` line to name the market subcommands and assert it. Keep the three existing permission nodes and the absent-later-milestone-nodes test exactly as they are — Part 1 adds **no** new permission nodes (all market actions are `farmersmarket.use`) and adds **no** later-milestone nodes.

```java
@Test
void marketUsageNamesTheSubcommandsThisBuildImplements() throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> commands = (Map<String, Object>) parse(PLUGIN_YML).get("commands");
    @SuppressWarnings("unchecked")
    Map<String, Object> market = (Map<String, Object>) commands.get("market");
    assertEquals("/market [balance | deposit | withdraw | sell | browse | info | buy | "
            + "cancel | mine | claim | pot | reload]", market.get("usage"));
}
```

Replace the old `marketUsageNamesOnlyTheSubcommandsM1Implements` assertion with this one (the usage string changed). Update the `usage:` in `plugin.yml` to the identical string. Leave `farmersmarket.bypass.fees` and `farmersmarket.bypass.buylimit` in the absent-nodes list — fee and buy-limit bypass are not built in Part 1.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PluginDescriptorTest`
Expected: FAIL — the usage string still reads the M1 value.

- [ ] **Step 3: Update `plugin.yml`, re-run**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PluginDescriptorTest`
Expected: PASS.

- [ ] **Step 4: Extend `MarketCommand`**

Give it a `MarketService market`, a `BukkitItemCodec codec`, and the `FmConfig` accessors it needs (or the whole `Supplier<FmConfig>` so a reload is reflected). Dispatch the new outcomes from `onCommand`. The two money-moving flows carry the real care:

**`sell` (list a unique):**
1. Resolve is `SELL` with `priceDust`. Read the item in the player's main hand; if empty → "Hold the item you want to sell." and stop.
2. `ListedItem item = codec.encode(handItem);`
3. If `item.itemClass() == ItemClass.COMMODITY` → `error(player, MarketResolver.messageFor(MarketException.Reason.COMMODITY_NOT_YET))` and stop. **Nothing has been removed or charged.**
4. Compute the XP fee: `int fee = MarketMath.listingFeeXp(Diamonds.ofDust(priceDust), config.listingFeePercent(), config.xpPerDiamond());`. If `experiencePoints(player) < fee` → "Listing costs `fee` XP and you have `have`." and stop. (Reuse the existing `experiencePoints` helper.)
5. Remove the item from the hand (`player.getInventory().setItemInMainHand(null)` after capturing it — remove the exact stack you encoded). Then call `market.list(id, item, Diamonds.ofDust(priceDust), config.maxListingsPerPlayer(), now, config.listingDurationDays())`.
6. In `onMainThread(future, id, "listing " + item.summary(), (listingId, failure) -> ...)`:
   - success → **now** charge the fee: `player.giveExp(-fee)` (re-resolve the player; if offline, the item is safely listed and the fee is simply not charged — non-dangerous). Message: "Listed as #`listingId` for `price` diamonds. Fee: `fee` XP."
   - `MarketException` (a refusal, includes `TOO_MANY_LISTINGS` and `NOTHING_WRITTEN`) → return the item with `giveOrDrop(id, where, item)` and show `messageFor(reason)`. **No fee was charged.** (`giveOrDrop` needs an item-aware overload — add `giveOrDrop(UUID, Location, ItemStack)` alongside the existing diamond-count one, using `addItem`/`dropItemNaturally`.)
   - any other cause → `reportUncertain(id, "listing " + summary, failure, "Your item was taken out of your hand and may or may not be listed; do not sell another copy until an admin checks.")`. The item left the hand and the outcome is unknown — the same never-dupe policy as deposit.

**`buy`:**
1. Resolve is `BUY` with `listingId`. Optionally pre-check self-purchase by reading the listing first, but the service refuses it authoritatively inside the transaction, so a pre-check is only for a nicer message — keep it optional.
2. `onMainThread(market.buy(id, listingId, config.salesTaxPercent(), config.taxBurnShare(), now), id, "buying listing " + listingId, (result, failure) -> ...)`:
   - success → the money and the trade are already committed. Deliver the item: `deliverItemOrHold(id, where, codec.decode(result.itemBytes()), result.amount(), result.summary(), "PURCHASE")`. Message: "Bought #`listingId` for `gross` diamonds (tax `tax`). Enjoy!" If the item had to be held, add "Your inventory was full, so it is waiting in /market claim."
   - `MarketException` (refusal: `LISTING_UNAVAILABLE`, `SELF_PURCHASE`, `INSUFFICIENT_FUNDS`) → nothing was written; show `messageFor(reason)`.
   - any other cause → `reportUncertain(id, "buying listing " + listingId, failure, "You may or may not have been charged; check /market balance before trying again.")`.

**`cancel`:** `onMainThread(market.cancel(id, listingId, now), ...)`: success → `deliverItemOrHold(id, where, codec.decode(bytes), amount, summary, "CANCELLED")` (the seller is standing right there, so it almost always lands in the hand); refusal → `messageFor(reason)`; unknown → `reportUncertain`.

**`browse` / `info` / `mine` / `pot`:** read-only; format the rows as plain coloured chat lines (id, material/name, price, and for `info` the full `summary` including shulker contents). `pot` shows `market.communityPotBalance().format()`. No item or money moves, so a failure just shows "Could not read the market just now."

**`claim`:** read `market.pendingFor(id)`; for each unclaimed row, `deliverItemOrHold`-style, but here delivery drives the claim: try to add the item to the inventory; only if it fits, call `market.claimOne(id, pendingId, now)` and confirm; if it does not fit, stop and tell the player how many remain. Never mark claimed an item that did not physically land.

Add the `deliverItemOrHold(UUID, Location, ItemStack, int, String, String reason)` helper: if the player is online and the item fits, give it; otherwise `market.insertPending`-via-service so it appears in `/market claim`. For an offline or full recipient this **holds for claim rather than dropping on the ground** — a valuable unique must not despawn, which is why uniques get the claim path and M1's diamond withdraw (fungible, replaceable) drops. If even the pending insert is refused, fall back to `reportUncertain` naming the recoverable escrow (the item bytes are still in the `SOLD`/`CANCELLED` listing row).

- [ ] **Step 5: Wire `FarmersMarketPlugin`**

In `onEnable`, after the `Ledger` is built:
1. Construct `BukkitItemCodec`, `MarketDao(database)`, `MarketService(database, executor, accountDao, marketDao)`.
2. Pass `market`, `codec`, and a `Supplier<FmConfig>` (so a reload is seen) into the `MarketCommand` constructor.
3. Schedule the expiry sweep: `getServer().getScheduler().runTaskTimerAsynchronously(this, () -> market.expireDue(System.currentTimeMillis(), 200) ...)` on a slow cadence (e.g. every 10 minutes), and run one sweep shortly after enable. The sweep runs its own executor work; do not block the scheduler thread on the future — attach a `whenComplete` that logs a swept-count at FINE and logs a failure at WARNING. Guard the whole registration so a scheduling failure disables cleanly like the rest of `onEnable`.
4. `onDisable` is unchanged in ordering — `DatabaseExecutor.close()` first (its bounded flush drains any in-flight sale), then `Database`. The market adds no new closeable resources.

- [ ] **Step 6: Run the full build**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, all tests passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/command/MarketCommand.java src/main/java/org/xpfarm/farmersmarket/FarmersMarketPlugin.java src/main/resources/plugin.yml src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java
git commit -m "feat(command): list, browse, buy, cancel, and claim unique items"
```

---

## Out of scope for M2 Part 1 — do not build

Naming these explicitly because a helpful implementer will otherwise reach for them:

- **The commodity exchange** — anonymous buy/sell offer matching, partial fills, an order book. **M2 Part 2.** Part 1 refuses a commodity listing with `COMMODITY_NOT_YET`; the codec already classifies them, and the schema already carries `item_class`, so Part 2 adds rows and a matching engine, not a migration of what exists.
- **Rolling buy limits and the server buy-back floor.** **M2 Part 2.** `FmConfig` already parses `buy-limits`, `buyback-floors`, and `farm-output-costs`; Part 1 consumes none of them.
- **Any GUI or Cumulus form, any item icon, any `BarGlyphs` rendering, map charts.** **M3.** Part 1 is chat text only; the shulker *content summary* is text, not a rendered icon.
- **Vendors, `TextDisplay` labels, stalls, sealed bids.** **M4.**
- **Price history, indices, "your price vs. average," analytics rollups.** **M5.** The `trades` table feeds them later, but Part 1 only writes it.
- **Spending the community pot.** It accumulates in Part 1; an admin withdrawal path is a later feature. Do not add a `/market pot pay` or similar.
- **A global search index.** `browse` with a material filter and pagination is Part 1's discovery surface. Full-text search across names is deferred.

`FmConfig` already parses every setting these features need. That is deliberate and is not permission to implement them.

## Runtime verification carried to gate 7a

No unit test can reach these; they join the gate 7a play-test list on the Legendary stack and must pass before `0.2.0` is deployed:

1. **`serializeAsBytes` / `deserializeBytes` round-trips a real item** — list an enchanted, damaged item and a filled shulker box; buy each; confirm the delivered item is byte-identical (enchantments, name, damage, and shulker contents all intact). This is the one path `BukkitItemCodec` cannot unit-test.
2. **The shulker content summary reads correctly on a Bedrock client** — a filled shulker's `summary` line is legible plain text with no blank glyphs, and the item is never drawn as an invisible icon (Geyser #3001).
3. **The offline-seller payout** — seller lists, logs off; a second player buys; seller logs back in and their `/market balance` shows the net. The whole design rests on sales completing while the seller is offline.
4. **The full no-command-typed loop is *not yet* reachable** — Part 1 is command-driven by design (the GUI is M3), so gate 7a for `0.2.0` verifies the command loop only. Note this explicitly so the M3 gate is not thought already covered.
5. **`giveExp(-fee)` deducts the right XP** and never pushes a player below zero (Bukkit clamps, but confirm on a level-0 player listing a cheap item).

## Self-review — completed at write time

Checked against `docs/PLUGIN_CHECKLIST.md` §1 and the design spec:

- **Item identity** → Task 3 (`serializeAsBytes`, content hash, classification). ✓
- **Escrow** → Task 2 schema (`listings.item_bytes`) + Task 5 (`list`/`cancel`/`expireDue`). Sales complete offline. ✓
- **Fees (1% XP)** → Task 4 `listingFeeXp` + Task 7 `giveExp` after escrow. ✓
- **Sales tax (~7% diamonds, half burned, half to pot)** → Task 4 `taxOnSale` + Task 5 sale credits pot, burns the rest. ✓
- **The immutable trade log** → Task 2 (`trades`, conservation `CHECK`s, append-only triggers) + Task 5 (first trade written in the sale). Lands with the first trade, as required. ✓
- **Split market — unique board with instant buyout** → Tasks 5–7. Commodity half refused with `COMMODITY_NOT_YET`, deferred to Part 2. ✓
- **Shulkers listable, never rendered raw** → Task 3 summary + gate 7a item 2. ✓
- **Community pot** → Task 2 `SystemAccounts` + Task 5 credit. ✓
- **Integer money end to end** → Global Constraints + Task 4 (`double` only as a converted config rate) + Task 5 (`Diamonds` arithmetic in the sale). ✓
- **Every money-moving op uses the `onMainThread` shutdown seam** → Task 7. ✓
- **The kickoff's open concern** (lift compensation decisions into `MarketResolver` as pure functions) → Task 6 puts every new decision in the pure resolver. ✓

Type-consistency pass: `ListedItem`, `ListingRow`, `TradeRow`, `PendingItemRow`, `TaxSplit`, `SaleResult`, `MarketException.Reason`, `ListingStatus`, and `ItemClass` are named identically everywhere they appear across Tasks 2–7. `MarketService`'s constructor is `(Database, DatabaseExecutor, AccountDao, MarketDao)` in both its Interfaces block and Task 7's wiring. `MarketMath.listingFeeXp` / `taxOnSale` signatures match between Task 4 and their callers in Tasks 5 and 7.

Placeholder scan: the mechanical DAO methods (Task 2 Step 6) and the read-only command formatting (Task 7 Step 4) are specified by exact signature, exact SQL, and a pointer to the in-repo pattern to mirror (`AccountDao`, the existing `MarketCommand` helpers) rather than by full transcribed code — this is DRY against a pattern already in the tree, not a "TODO". Every money-critical and pure-logic method carries complete code and complete test code.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-28-farmers-market-m2-part1-unique-market.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task with two-stage review between tasks. **Strongly recommended here:** every task in this plan moves money or defines the data model money moves through, and M1's whole-branch review found the worst defects at package boundaries no per-task review could see. Budget for a whole-branch review after Task 7, before merge, exactly as M1 did.

**2. Inline Execution** — batch execution with checkpoints in this session.

After the plan lands and passes review, the market is `0.2.0`-ready but **not released**: the release, updater, deploy, and handoff gates (10–12, still open from M1) apply, and gate 7a's runtime items above must pass on the Legendary stack first. Part 2 (the commodity exchange) is a separate plan that builds on this spine.

**Which approach?**
