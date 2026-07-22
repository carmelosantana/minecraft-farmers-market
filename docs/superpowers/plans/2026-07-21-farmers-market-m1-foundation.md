# Farmers Market M1 — Foundation & Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `0.1.0` — a plugin that enables cleanly on the Legendary stack, owns a migrated SQLite database, and lets a player deposit diamonds into a ledger, check their balance, and withdraw them again, correctly for both Java and Bedrock clients.

**Architecture:** Four Bukkit-free modules (`config`, `storage`, `identity`, `ledger`) plus one thin Bukkit wiring layer. Everything with logic lives behind a small interface so it is unit-testable with no server running; the Bukkit adapters are deliberately dumb. All database access goes through a single-writer executor off the main thread, and every Bukkit API touch returns to the main thread.

**Tech Stack:** Java 25, Paper 26.1.2 build 74, SQLite via `org.xerial:sqlite-jdbc` (provided scope, runtime-loaded through `plugin.yml` `libraries:`), JUnit 5. No Mockito, no MockBukkit.

**Milestone context:** This is M1 of five. M2 adds the market (listings, commodity matching, escrow, fees). M3 adds the cross-platform UI. M4 adds vendors and stalls. M5 adds analytics and charts. Design rationale lives in `docs/superpowers/specs/2026-07-21-farmers-market-design.md`; authoritative scope is `docs/PLUGIN_CHECKLIST.md` §1.

## Global Constraints

Binding on every task. A task that violates any of these is not complete.

- **Java 25**, **Paper `26.1.2` build 74**, `api-version: '26.1'` in `plugin.yml`. Do not lower `api-version` — a lower value opts the JAR into Paper's `Commodore` bytecode rewrites.
- Maven group `org.xpfarm`, artifactId `farmers-market`, version `0.1.0`. Root package `org.xpfarm.farmersmarket`.
- **AGPL-3.0-or-later.** Every new `.java` file carries the project's license header. Match the header in `src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java` verbatim in structure, changing only the one-line description.
- **No external services.** This plugin makes zero outbound network calls. Do not add HTTP clients, Ollama, Umami, or telemetry of any kind. Gate 5 is satisfied by there being nothing to satisfy it.
- **New dependencies are limited to exactly one:** `org.xerial:sqlite-jdbc` at `provided` scope. Do not add Mockito, MockBukkit, HikariCP, Guava, Apache Commons, or any other library.
- **Tests ship with the code, not after it.** Any logic separable from the Bukkit runtime must be unit tested in the same task that writes it. Classes in `config`, `storage`, `identity`, and `ledger` must not import `org.bukkit.*` at all, with the sole exceptions of the explicitly-named Bukkit adapter classes. That import ban is what makes them testable without a server.
- **Money is integer-only.** No `double`, `float`, or `BigDecimal` ever holds a balance or an amount. See Task 4's `Diamonds` type.
- **Geyser/Floodgate/Bedrock safety.** All player-facing output in M1 is plain chat text. Specifically forbidden: hover events, click events (a `copy_to_clipboard` click event breaks Bedrock chat outright, Geyser #1399), hex/RGB colours and gradients (Geyser downgrades them to legacy codes), strikethrough and underline (Geyser strips both), and any reliance on `▁▂▃▄▅▆▇` (absent from Bedrock's CP437-derived glyph sheet). Legacy colour codes and `█▓▒░` are safe.
- **Never key player data on username.** Always UUID. Floodgate's username prefix is config-mutable and Java names change.
- Follow the house style of the sibling plugin at `/home/carmelo/Projects/Minecraft/Plugins/timber-blast` — package layout, config class shape, naming, comment density, javadoc voice. Read it before writing.
- Build must pass `mvn --batch-mode --no-transfer-progress clean verify`.

### Settled facts — do not re-derive, do not "improve"

Researched and settled at planning time. Implement against them exactly.

1. **Paper's bundled `sqlite-jdbc` is legacy.** Paper's own `build.gradle.kts` marks it "eventually to be removed". The driver is declared in `plugin.yml` `libraries:` and must stay there. Adding it to the POM at `provided` scope puts it on the compile and test classpaths without shading it.
2. **Never relocate `org.sqlite.*` if shading is ever added.** Relocation breaks the driver's reflective and JNI paths (xerial/sqlite-jdbc#145). The current shade config bundles nothing; leave it that way.
3. **`sqlite-jdbc` extracts a native library into `java.io.tmpdir` at class load.** A container with `/tmp` mounted `noexec` fails with `UnsatisfiedLinkError`. The `storage.sqlite-tmpdir` config key exists to set `org.sqlite.tmpdir` explicitly. It must be applied **before** the first driver class load.
4. **SQLite serialises writers regardless of connection count.** Use one connection on one thread. Do not add a pool.
5. **`foreign_keys=ON` is per-connection and not persisted.** Set it on every connection, every time.
6. **Paper cancels all scheduled plugin tasks at disable.** In-flight async writes are lost unless `onDisable` performs a synchronous, bounded flush. This is a correctness requirement, not politeness.
7. **Floodgate must never be on the compile classpath.** Reach it reflectively, exactly as `/home/carmelo/Projects/Minecraft/Plugins/magic-carpet/src/main/java/org/xpfarm/magiccarpet/visual/EditionResolver.java` does. Any failure anywhere in the chain resolves the player to Java edition. Read that file before writing Task 3.
8. **A linked Bedrock player's UUID changes.** Unlinked Floodgate players get a synthetic XUID-derived UUID; after linking a Java account, `getCorrectUniqueId()` returns the real Java UUID instead. Floodgate does not migrate plugin data. Without an explicit merge path a linking player loses their balance.
9. **`Player#getTotalExperience()` is unreliable** as a total-points reading across implementations and is not the inverse of `setTotalExperience`. Compute points from level plus `getExp()` progress using the vanilla formula in Task 4's `ExperienceMath`.

---

## File Structure

**Created — Bukkit-free (no `org.bukkit.*` imports):**

| Path under `src/main/java/org/xpfarm/farmersmarket/` | Responsibility |
|---|---|
| `config/ConfigSource.java` | Read-only key/value abstraction over a config file |
| `config/FmConfig.java` | Validated, immutable settings for the whole plugin |
| `config/BarGlyphs.java` | Enum: `DENSITY_4`, `RAMP_8` |
| `config/VendorPlacementMode.java` | Enum: `OWNED_AND_DISTRICT`, `DISTRICT_ONLY`, `ANYWHERE` |
| `storage/Database.java` | Owns the one SQLite connection, pragmas, tmpdir |
| `storage/Migrations.java` | Ordered, idempotent schema migrations |
| `storage/DatabaseExecutor.java` | Single-writer thread, bounded shutdown flush |
| `storage/AccountDao.java` | Reads and writes `accounts` and `account_links` |
| `storage/AccountRow.java` | Immutable row value type |
| `identity/AccountMerge.java` | Pure Floodgate-link merge rules |
| `ledger/Diamonds.java` | Integer money value type, parsing, formatting |
| `ledger/Ledger.java` | Deposit, withdraw, transfer, balance |
| `ledger/LedgerException.java` | Typed failure for insufficient funds and bad input |
| `ledger/ExperienceMath.java` | Vanilla XP level↔points conversion |

**Created — Bukkit adapters (thin, `org.bukkit.*` allowed):**

| Path | Responsibility |
|---|---|
| `config/BukkitConfigSource.java` | Wraps `ConfigurationSection` as a `ConfigSource` |
| `identity/EditionResolver.java` | Reflective Floodgate Bedrock detection |
| `command/MarketCommand.java` | The `/market` command tree |
| `FarmersMarketPlugin.java` | Lifecycle, wiring, `onEnable`/`onDisable` |

**Modified:** `pom.xml`, `src/main/resources/plugin.yml`, `src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java`.

---

## Task 1: Configuration

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/config/ConfigSource.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/config/FmConfig.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/config/BukkitConfigSource.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/config/FmConfigTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `FmConfig.load(ConfigSource, Consumer<String> warn)` returning `FmConfig`; accessors listed below. Later tasks read settings only through `FmConfig`.

Parse and validate **every** key in the shipped `src/main/resources/config.yml`. Later milestones consume values M1 does not yet use; parsing them now means one tested config class instead of five edits to it. An out-of-range or wrong-typed value logs one warning through `warn` and falls back to the documented default — it never throws and never fails plugin enable.

Two keys are **fail-closed** and are the exception to that rule, because an unsafe value there is worse than no plugin: see `liquidity` below.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void rejectsOutOfRangeTaxAndFallsBackToDefault() {
    List<String> warnings = new ArrayList<>();
    MapConfigSource source = new MapConfigSource(Map.of("economy.sales-tax-percent", 150.0));

    FmConfig config = FmConfig.load(source, warnings::add);

    assertEquals(7.0, config.salesTaxPercent());
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("economy.sales-tax-percent"));
}

@Test
void acceptsValidValuesWithoutWarning() {
    List<String> warnings = new ArrayList<>();
    MapConfigSource source = new MapConfigSource(Map.of(
            "economy.sales-tax-percent", 5.0,
            "economy.listing-fee-percent", 0.5,
            "storage.busy-timeout-ms", 9000));

    FmConfig config = FmConfig.load(source, warnings::add);

    assertEquals(5.0, config.salesTaxPercent());
    assertEquals(0.5, config.listingFeePercent());
    assertEquals(9000, config.busyTimeoutMs());
    assertTrue(warnings.isEmpty());
}

@Test
void emptyConfigYieldsEveryDocumentedDefault() {
    FmConfig config = FmConfig.load(new MapConfigSource(Map.of()), w -> {});

    assertEquals(1.0, config.listingFeePercent());
    assertEquals(7.0, config.salesTaxPercent());
    assertEquals(0.5, config.taxBurnShare());
    assertEquals(40, config.xpPerDiamond());
    assertEquals(14, config.listingDurationDays());
    assertEquals(40, config.maxListingsPerPlayer());
    assertEquals(4, config.buyLimitWindowHours());
    assertEquals(2, config.minPlaytimeHours());
    assertEquals(2, config.maxVendorsPerPlayer());
    assertEquals(24, config.vendorMinDistanceBlocks());
    assertEquals(168, config.stallBidPeriodHours());
    assertEquals(1, config.maxStallsPerPlayer());
    assertEquals(60, config.chartRefreshSeconds());
    assertEquals(3.0, config.outlierFilterMad());
    assertEquals(365, config.historyRetentionDays());
    assertEquals(5000, config.busyTimeoutMs());
    assertEquals("plugins/FarmersMarket/tmp", config.sqliteTmpdir());
    assertEquals(BarGlyphs.DENSITY_4, config.barGlyphs());
    assertEquals(VendorPlacementMode.OWNED_AND_DISTRICT, config.vendorPlacementMode());
}

@Test
void unknownEnumValueWarnsAndFallsBack() {
    List<String> warnings = new ArrayList<>();
    FmConfig config = FmConfig.load(
            new MapConfigSource(Map.of("ui.bar-glyphs", "rainbow")), warnings::add);

    assertEquals(BarGlyphs.DENSITY_4, config.barGlyphs());
    assertEquals(1, warnings.size());
}

@Test
void buybackFloorAtOrAboveFarmOutputCostIsRefused() {
    List<String> warnings = new ArrayList<>();
    MapConfigSource source = new MapConfigSource(Map.of(
            "liquidity.buyback-floors", Map.of("DIAMOND", 10.0),
            "liquidity.farm-output-costs", Map.of("DIAMOND", 10.0)));

    FmConfig config = FmConfig.load(source, warnings::add);

    assertTrue(config.buybackFloors().isEmpty(),
            "a floor at or above production cost is an infinite money faucet and must not load");
    assertTrue(warnings.get(0).contains("DIAMOND"));
}

@Test
void buybackFloorWithNoFarmOutputCostIsRefused() {
    List<String> warnings = new ArrayList<>();
    MapConfigSource source = new MapConfigSource(Map.of(
            "liquidity.buyback-floors", Map.of("IRON_INGOT", 1.0)));

    FmConfig config = FmConfig.load(source, warnings::add);

    assertTrue(config.buybackFloors().isEmpty(),
            "an unestimated item is the dangerous case; fail closed");
}

@Test
void buybackFloorBelowFarmOutputCostLoads() {
    FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
            "liquidity.buyback-floors", Map.of("IRON_INGOT", 1.0),
            "liquidity.farm-output-costs", Map.of("IRON_INGOT", 4.0))), w -> {});

    assertEquals(1, config.buybackFloors().size());
}

@Test
void disablingTheAuditStillLoadsFloorsButWarnsLoudly() {
    List<String> warnings = new ArrayList<>();
    FmConfig config = FmConfig.load(new MapConfigSource(Map.of(
            "liquidity.farm-output-audit", false,
            "liquidity.buyback-floors", Map.of("DIAMOND", 999.0))), warnings::add);

    assertEquals(1, config.buybackFloors().size());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("farm-output-audit")));
}
```

Write `MapConfigSource` as a test fixture in the same test file — a `ConfigSource` backed by a `Map<String, Object>` that returns the supplied default when a key is absent or wrongly typed.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=FmConfigTest`
Expected: FAIL — `FmConfig` does not exist.

- [ ] **Step 3: Implement `ConfigSource`**

```java
public interface ConfigSource {
    int getInt(String path, int def);
    long getLong(String path, long def);
    double getDouble(String path, double def);
    boolean getBoolean(String path, boolean def);
    String getString(String path, String def);
    List<String> getStringList(String path);
    Map<String, Double> getDoubleMap(String path);
    Map<String, Integer> getIntMap(String path);
}
```

- [ ] **Step 4: Implement `FmConfig`**

Immutable, built by a private constructor from a static `load`. Two supporting enums live in the `config` package: `BarGlyphs` with constants `DENSITY_4` and `RAMP_8`, and `VendorPlacementMode` with `OWNED_AND_DISTRICT`, `DISTRICT_ONLY`, `ANYWHERE`. Both parse case-insensitively from the kebab-case config strings (`density-4`, `owned-and-district`).

Keys, types, defaults, and validation ranges — use these verbatim:

| Config path | Accessor | Type | Default | Valid range |
|---|---|---|---|---|
| `economy.listing-fee-percent` | `listingFeePercent()` | double | `1.0` | `0.0`–`100.0` |
| `economy.sales-tax-percent` | `salesTaxPercent()` | double | `7.0` | `0.0`–`100.0` |
| `economy.tax-burn-share` | `taxBurnShare()` | double | `0.5` | `0.0`–`1.0` |
| `economy.xp-per-diamond` | `xpPerDiamond()` | int | `40` | `> 0` |
| `economy.listing-duration-days` | `listingDurationDays()` | int | `14` | `1`–`90` |
| `economy.max-listings-per-player` | `maxListingsPerPlayer()` | int | `40` | `> 0` |
| `liquidity.buyback-enabled` | `buybackEnabled()` | boolean | `true` | — |
| `liquidity.buyback-floors` | `buybackFloors()` | `Map<String,Double>` | empty | see audit below |
| `liquidity.farm-output-costs` | `farmOutputCosts()` | `Map<String,Double>` | empty | values `> 0` |
| `liquidity.farm-output-audit` | `farmOutputAudit()` | boolean | `true` | — |
| `limits.buy-limit-enabled` | `buyLimitEnabled()` | boolean | `true` | — |
| `limits.buy-limit-window-hours` | `buyLimitWindowHours()` | int | `4` | `1`–`168` |
| `limits.buy-limits` | `buyLimits()` | `Map<String,Integer>` | empty | values `> 0` |
| `limits.min-playtime-hours` | `minPlaytimeHours()` | int | `2` | `>= 0` |
| `vendor.max-per-player` | `maxVendorsPerPlayer()` | int | `2` | `> 0` |
| `vendor.placement-mode` | `vendorPlacementMode()` | enum | `OWNED_AND_DISTRICT` | enum |
| `vendor.min-distance-blocks` | `vendorMinDistanceBlocks()` | int | `24` | `>= 0` |
| `vendor.label-enabled` | `vendorLabelEnabled()` | boolean | `true` | — |
| `stalls.enabled` | `stallsEnabled()` | boolean | `true` | — |
| `stalls.bid-currency` | `stallBidCurrency()` | String | `xp` | `xp` or `diamonds` |
| `stalls.bid-period-hours` | `stallBidPeriodHours()` | int | `168` | `> 0` |
| `stalls.max-stalls-per-player` | `maxStallsPerPlayer()` | int | `1` | `> 0` |
| `ui.bar-glyphs` | `barGlyphs()` | enum | `DENSITY_4` | enum |
| `ui.charts-enabled` | `chartsEnabled()` | boolean | `true` | — |
| `ui.chart-refresh-seconds` | `chartRefreshSeconds()` | int | `60` | `>= 10` |
| `ui.bedrock-touch-simplify` | `bedrockTouchSimplify()` | boolean | `true` | — |
| `analytics.index-basket` | `indexBasket()` | `List<String>` | empty | — |
| `analytics.outlier-filter-mad` | `outlierFilterMad()` | double | `3.0` | `> 0` |
| `analytics.history-retention-days` | `historyRetentionDays()` | int | `365` | `> 0` |
| `storage.sqlite-tmpdir` | `sqliteTmpdir()` | String | `plugins/FarmersMarket/tmp` | non-blank |
| `storage.busy-timeout-ms` | `busyTimeoutMs()` | int | `5000` | `> 0` |

**The buy-back audit**, applied after both maps are read. For each entry in `buybackFloors()`:

- If `farmOutputAudit()` is `false`: keep the entry, and emit exactly one warning naming `farm-output-audit` and stating that the faucet check is disabled. Emit that warning once, not once per entry.
- Otherwise, drop the entry and warn if **either** there is no `farmOutputCosts()` entry for that key, **or** the floor is `>=` that cost.

The result of `buybackFloors()` contains only surviving entries.

- [ ] **Step 5: Implement `BukkitConfigSource`**

Wraps `org.bukkit.configuration.ConfigurationSection`. `getDoubleMap`/`getIntMap` read a child section and coerce each value, skipping non-numeric entries. This is the only class in `config` allowed to import `org.bukkit.*`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=FmConfigTest`
Expected: PASS, all tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/config src/test/java/org/xpfarm/farmersmarket/config
git commit -m "feat(config): validated immutable configuration with fail-closed buyback audit"
```

---

## Task 2: Storage

**Files:**
- Modify: `pom.xml` (add `org.xerial:sqlite-jdbc` at `provided` scope)
- Create: `src/main/java/org/xpfarm/farmersmarket/storage/Database.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/storage/Migrations.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/storage/DatabaseExecutor.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/storage/AccountRow.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/storage/AccountDao.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/storage/MigrationsTest.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/storage/AccountDaoTest.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/storage/DatabaseExecutorTest.java`

**Interfaces:**
- Consumes: `FmConfig.sqliteTmpdir()`, `FmConfig.busyTimeoutMs()`.
- Produces:
  - `Database.open(Path dbFile, String sqliteTmpdir, int busyTimeoutMs)` → `Database`; `Database.connection()` → `java.sql.Connection`; `Database.close()`.
  - `Migrations.applyTo(Connection)` → `int` (resulting schema version).
  - `DatabaseExecutor.submit(Callable<T>)` → `CompletableFuture<T>`; `DatabaseExecutor.close()` performing a bounded synchronous flush.
  - `AccountDao(Database)` with `balanceDust(UUID)` → `long`, `upsertBalance(UUID, long)`, `findAccount(UUID)` → `Optional<AccountRow>`, `insertLink(UUID floodgate, UUID java, long mergedAtEpochMs)`, `deleteAccount(UUID)`, `allLinks()` → `List<UUID[]>`.
  - `AccountRow(UUID uuid, long diamondsDust, long createdAtEpochMs, long updatedAtEpochMs)` as a `record`.

Tests use a real SQLite file in a JUnit `@TempDir`. No mocking — SQLite is fast enough and mocking a database proves nothing.

- [ ] **Step 1: Add the dependency**

In `pom.xml`, inside `<dependencies>`, after `paper-api`:

```xml
<!--
    Provided, never shaded: plugin.yml `libraries:` supplies it at runtime. Paper still
    bundles this driver but its own build file marks that legacy. If shading is ever
    added, org.sqlite.* must NOT be relocated - relocation breaks the driver's
    reflective and JNI paths (xerial/sqlite-jdbc#145).
-->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.53.2.0</version>
    <scope>provided</scope>
</dependency>
```

If `3.53.2.0` does not resolve from Maven Central, resolve the newest available `3.5x` release, use it here, **and** update the matching coordinate in `src/main/resources/plugin.yml` `libraries:` so the two never disagree. Report the version you settled on in your task report.

- [ ] **Step 2: Write the failing migration test**

```java
@Test
void appliesSchemaFromScratchAndIsIdempotent(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("market.db");
    try (Database database = Database.open(db, dir.resolve("tmp").toString(), 5000)) {
        int first = Migrations.applyTo(database.connection());
        int second = Migrations.applyTo(database.connection());

        assertEquals(first, second, "re-applying migrations must be a no-op");
        assertTrue(first >= 1);
        assertTrue(tableExists(database.connection(), "accounts"));
        assertTrue(tableExists(database.connection(), "account_links"));
        assertTrue(tableExists(database.connection(), "schema_version"));
    }
}

@Test
void enablesWalAndForeignKeys(@TempDir Path dir) throws Exception {
    try (Database database = Database.open(dir.resolve("m.db"), dir.resolve("tmp").toString(), 5000)) {
        assertEquals("wal", scalar(database.connection(), "PRAGMA journal_mode").toLowerCase(Locale.ROOT));
        assertEquals("1", scalar(database.connection(), "PRAGMA foreign_keys"));
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=MigrationsTest`
Expected: FAIL — `Database` does not exist.

- [ ] **Step 4: Implement `Database`**

Opens exactly one `Connection` to `jdbc:sqlite:<path>`. Before the first driver class load, set the system property `org.sqlite.tmpdir` to the configured directory and create that directory if missing. On the connection, execute in this order: `PRAGMA journal_mode=WAL`, `PRAGMA synchronous=NORMAL`, `PRAGMA busy_timeout=<configured>`, `PRAGMA foreign_keys=ON`. Implement `AutoCloseable`.

- [ ] **Step 5: Implement `Migrations`**

A private static ordered `List<String[]>` where each element is one migration's statements. `applyTo` creates `schema_version(version INTEGER NOT NULL)` if absent, reads the current version, applies every migration beyond it inside a transaction, and writes the new version. Migration 1 creates:

```sql
CREATE TABLE IF NOT EXISTS accounts (
    uuid            TEXT    PRIMARY KEY NOT NULL,
    diamonds_dust   INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    CHECK (diamonds_dust >= 0)
);

CREATE TABLE IF NOT EXISTS account_links (
    floodgate_uuid  TEXT    PRIMARY KEY NOT NULL,
    java_uuid       TEXT    NOT NULL,
    merged_at       INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_account_links_java ON account_links(java_uuid);
```

The `CHECK (diamonds_dust >= 0)` is deliberate: a negative balance is a bug the database itself should refuse, not merely a rule application code remembers.

- [ ] **Step 6: Implement `DatabaseExecutor`**

Wraps a single-thread `ExecutorService` from `Executors.newSingleThreadExecutor` with a named daemon thread (`FarmersMarket-DB`). `submit` returns a `CompletableFuture` completed on that thread. `close()` calls `shutdown()` then `awaitTermination(10, TimeUnit.SECONDS)`; if it times out, log a warning naming how many tasks were still queued and call `shutdownNow()`. Never silently drop work.

Test it: submit 100 increments of a shared counter, close, assert the counter is 100 — proving `close()` flushes rather than abandons.

- [ ] **Step 7: Implement `AccountRow` and `AccountDao`**

`AccountDao` uses prepared statements throughout — never string concatenation. `balanceDust` returns `0` for an unknown UUID rather than throwing; an account that has never been touched is indistinguishable from one holding nothing, and forcing callers to handle `Optional` for that is noise. `upsertBalance` uses `INSERT ... ON CONFLICT(uuid) DO UPDATE SET diamonds_dust = ?, updated_at = ?`.

- [ ] **Step 8: Write and run the DAO tests**

Cover: unknown UUID reads zero; upsert then read round-trips; upsert twice updates rather than duplicating; a negative balance is rejected by the CHECK constraint; a link inserts and reads back.

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='MigrationsTest,AccountDaoTest,DatabaseExecutorTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/org/xpfarm/farmersmarket/storage src/test/java/org/xpfarm/farmersmarket/storage
git commit -m "feat(storage): SQLite database, migrations, and single-writer executor"
```

---

## Task 3: Identity

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/identity/EditionResolver.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/identity/AccountMerge.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/identity/EditionResolverTest.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/identity/AccountMergeTest.java`

**Interfaces:**
- Consumes: `AccountRow` from Task 2.
- Produces:
  - `EditionResolver.create(Logger)` → `EditionResolver`; `EditionResolver.alwaysJava()`; `EditionResolver.isBedrock(UUID)` → `boolean`.
  - `AccountMerge.merge(AccountRow from, AccountRow into)` → `AccountRow` (static, pure).

**Read first:** `/home/carmelo/Projects/Minecraft/Plugins/magic-carpet/src/main/java/org/xpfarm/magiccarpet/visual/EditionResolver.java`. Port its structure — the enabled-check-then-reflect pattern, the `alwaysJava()` fallback, the `attemptLink` split that makes the reflective half testable without a server. Change the package, the class javadoc, and the method name to `isBedrock(UUID)`. Do not redesign it.

- [ ] **Step 1: Write the failing merge tests**

```java
@Test
void mergeSumsBalancesAndKeepsEarliestCreation() {
    AccountRow floodgate = new AccountRow(FLOODGATE_UUID, 2_500L, 1_000L, 5_000L);
    AccountRow java = new AccountRow(JAVA_UUID, 1_000L, 3_000L, 4_000L);

    AccountRow merged = AccountMerge.merge(floodgate, java);

    assertEquals(JAVA_UUID, merged.uuid(), "the surviving identity is the Java UUID");
    assertEquals(3_500L, merged.diamondsDust());
    assertEquals(1_000L, merged.createdAtEpochMs(), "earliest creation survives");
    assertEquals(5_000L, merged.updatedAtEpochMs(), "latest update survives");
}

@Test
void mergeIntoAnAbsentJavaAccountCarriesTheWholeBalance() {
    AccountRow floodgate = new AccountRow(FLOODGATE_UUID, 7_000L, 1_000L, 1_000L);
    AccountRow emptyJava = new AccountRow(JAVA_UUID, 0L, 9_000L, 9_000L);

    assertEquals(7_000L, AccountMerge.merge(floodgate, emptyJava).diamondsDust());
}

@Test
void mergeIsLosslessForAnyPairOfNonNegativeBalances() {
    for (long a = 0; a < 1_000; a += 37) {
        for (long b = 0; b < 1_000; b += 41) {
            AccountRow merged = AccountMerge.merge(
                    new AccountRow(FLOODGATE_UUID, a, 1L, 1L),
                    new AccountRow(JAVA_UUID, b, 1L, 1L));
            assertEquals(a + b, merged.diamondsDust());
        }
    }
}
```

- [ ] **Step 2: Write the failing resolver test**

Mirror `magic-carpet`'s `EditionResolverTest`: assert `alwaysJava()` reports every UUID as Java, and that `attemptLink(null)` returns `null` when Floodgate's classes are absent from the test classpath (they are — Floodgate is never a dependency). That is the whole testable surface; the rest needs a live server.

- [ ] **Step 3: Run to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='AccountMergeTest,EditionResolverTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 4: Implement both classes**

`AccountMerge.merge` is a pure static function with no I/O. Applying the merge to the database — deleting the Floodgate row, upserting the Java row, inserting the link — belongs to Task 5's wiring, not here. Keeping the rule pure is what makes it exhaustively testable.

- [ ] **Step 5: Run to verify pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='AccountMergeTest,EditionResolverTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/identity src/test/java/org/xpfarm/farmersmarket/identity
git commit -m "feat(identity): Floodgate edition detection and pure account-merge rules"
```

---

## Task 4: Ledger

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/ledger/Diamonds.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/ledger/LedgerException.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/ledger/Ledger.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/ledger/ExperienceMath.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/ledger/DiamondsTest.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/ledger/LedgerTest.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/ledger/ExperienceMathTest.java`

**Interfaces:**
- Consumes: `AccountDao` and `DatabaseExecutor` from Task 2.
- Produces:
  - `Diamonds` — immutable, wraps `long dust`. `Diamonds.DUST_PER_DIAMOND == 1000`. Static `ofDiamonds(long)`, `ofDust(long)`, `parse(String)`. Instance `dust()`, `plus(Diamonds)`, `minus(Diamonds)`, `isNegative()`, `compareTo`, `format()`.
  - `Ledger(AccountDao, DatabaseExecutor)` with `balance(UUID)` → `CompletableFuture<Diamonds>`, `deposit(UUID, Diamonds)` → `CompletableFuture<Diamonds>` (new balance), `withdraw(UUID, Diamonds)` → `CompletableFuture<Diamonds>`, `transfer(UUID from, UUID to, Diamonds)` → `CompletableFuture<Void>`, `mergeAccounts(UUID floodgate, UUID java, long nowEpochMs)` → `CompletableFuture<Void>`.
  - `ExperienceMath.totalPoints(int level, float progress)` → `int`; `ExperienceMath.levelForTotal(int points)` → `int`.

**One diamond is 1000 dust.** Every balance and amount is a `long` count of dust. This gives three decimal places of headroom so later milestones can take a 7% cut of small prices without rounding drift. No `double` may appear anywhere in this package.

- [ ] **Step 1: Write the failing `Diamonds` tests**

```java
@Test
void parsesWholeAndFractionalAmounts() {
    assertEquals(1_000L, Diamonds.parse("1").dust());
    assertEquals(1_500L, Diamonds.parse("1.5").dust());
    assertEquals(1L,     Diamonds.parse("0.001").dust());
    assertEquals(64_000L, Diamonds.parse("64").dust());
}

@Test
void rejectsExponentialNotationAtParse() {
    assertThrows(LedgerException.class, () -> Diamonds.parse("1e9"));
    assertThrows(LedgerException.class, () -> Diamonds.parse("1E9"));
    assertThrows(LedgerException.class, () -> Diamonds.parse("0x10"));
}

@Test
void rejectsNegativeAndMalformedInput() {
    assertThrows(LedgerException.class, () -> Diamonds.parse("-1"));
    assertThrows(LedgerException.class, () -> Diamonds.parse(""));
    assertThrows(LedgerException.class, () -> Diamonds.parse("  "));
    assertThrows(LedgerException.class, () -> Diamonds.parse("abc"));
    assertThrows(LedgerException.class, () -> Diamonds.parse("1.2.3"));
    assertThrows(LedgerException.class, () -> Diamonds.parse("Infinity"));
    assertThrows(LedgerException.class, () -> Diamonds.parse("NaN"));
}

@Test
void rejectsMorePrecisionThanDustAllows() {
    assertThrows(LedgerException.class, () -> Diamonds.parse("0.0001"));
}

@Test
void formatsWithoutTrailingNoise() {
    assertEquals("1", Diamonds.ofDust(1_000L).format());
    assertEquals("1.5", Diamonds.ofDust(1_500L).format());
    assertEquals("0.001", Diamonds.ofDust(1L).format());
    assertEquals("0", Diamonds.ofDust(0L).format());
}

@Test
void arithmeticIsExactOverManyRandomPairs() {
    Random random = new Random(20260721L);
    for (int i = 0; i < 10_000; i++) {
        long a = random.nextLong(0, 1_000_000_000L);
        long b = random.nextLong(0, 1_000_000_000L);
        assertEquals(a + b, Diamonds.ofDust(a).plus(Diamonds.ofDust(b)).dust());
    }
}

@Test
void overflowIsRefusedRatherThanWrapped() {
    Diamonds huge = Diamonds.ofDust(Long.MAX_VALUE);
    assertThrows(LedgerException.class, () -> huge.plus(Diamonds.ofDust(1L)));
}
```

`parse` must reject exponential notation explicitly rather than relying on `Long.parseLong` to fail — this is the exact defect that let `1e9` mint money in EssentialsX-family economies. Parse by regex first: `^\d+(\.\d{1,3})?$` and nothing else.

- [ ] **Step 2: Write the failing `Ledger` tests**

Use a real `Database` in a `@TempDir` plus a real `DatabaseExecutor`; join the futures in the test.

```java
@Test
void depositThenWithdrawSameAmountLeavesBalanceUnchanged() throws Exception {
    Diamonds start = ledger.balance(PLAYER).get();
    ledger.deposit(PLAYER, Diamonds.ofDiamonds(64)).get();
    ledger.withdraw(PLAYER, Diamonds.ofDiamonds(64)).get();

    assertEquals(start.dust(), ledger.balance(PLAYER).get().dust());
}

@Test
void withdrawingMoreThanHeldFailsAndChangesNothing() throws Exception {
    ledger.deposit(PLAYER, Diamonds.ofDiamonds(5)).get();

    ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> ledger.withdraw(PLAYER, Diamonds.ofDiamonds(6)).get());

    assertInstanceOf(LedgerException.class, thrown.getCause());
    assertEquals(5_000L, ledger.balance(PLAYER).get().dust());
}

@Test
void transferMovesExactlyTheAmountAndConservesTotal() throws Exception {
    ledger.deposit(ALICE, Diamonds.ofDiamonds(10)).get();
    ledger.deposit(BOB, Diamonds.ofDiamonds(3)).get();

    ledger.transfer(ALICE, BOB, Diamonds.ofDiamonds(4)).get();

    assertEquals(6_000L, ledger.balance(ALICE).get().dust());
    assertEquals(7_000L, ledger.balance(BOB).get().dust());
}

@Test
void failedTransferLeavesBothBalancesExactlyAsTheyWere() throws Exception {
    ledger.deposit(ALICE, Diamonds.ofDiamonds(2)).get();
    ledger.deposit(BOB, Diamonds.ofDiamonds(3)).get();

    assertThrows(ExecutionException.class,
            () -> ledger.transfer(ALICE, BOB, Diamonds.ofDiamonds(99)).get());

    assertEquals(2_000L, ledger.balance(ALICE).get().dust());
    assertEquals(3_000L, ledger.balance(BOB).get().dust());
}

@Test
void mergeMovesTheFloodgateBalanceOntoTheJavaAccountAndRecordsTheLink() throws Exception {
    ledger.deposit(FLOODGATE_UUID, Diamonds.ofDiamonds(12)).get();
    ledger.deposit(JAVA_UUID, Diamonds.ofDiamonds(3)).get();

    ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 1_700_000_000_000L).get();

    assertEquals(15_000L, ledger.balance(JAVA_UUID).get().dust());
    assertEquals(0L, ledger.balance(FLOODGATE_UUID).get().dust());
    assertEquals(1, dao.allLinks().size());
}

@Test
void mergeIsIdempotentAndDoesNotDoubleCredit() throws Exception {
    ledger.deposit(FLOODGATE_UUID, Diamonds.ofDiamonds(12)).get();

    ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 1L).get();
    ledger.mergeAccounts(FLOODGATE_UUID, JAVA_UUID, 2L).get();

    assertEquals(12_000L, ledger.balance(JAVA_UUID).get().dust());
}
```

`transfer` and `mergeAccounts` must run inside a single SQL transaction and roll back completely on failure. Idempotent merge means: if a link row already exists for that Floodgate UUID, the merge is a no-op.

- [ ] **Step 3: Write the failing `ExperienceMath` tests**

Vanilla formulas, exact:

```java
@Test
void totalPointsMatchesVanillaAtKnownLevels() {
    assertEquals(0,    ExperienceMath.totalPoints(0, 0f));
    assertEquals(7,    ExperienceMath.totalPoints(1, 0f));
    assertEquals(315,  ExperienceMath.totalPoints(15, 0f));
    assertEquals(352,  ExperienceMath.totalPoints(16, 0f));
    assertEquals(394,  ExperienceMath.totalPoints(17, 0f));
    assertEquals(1395, ExperienceMath.totalPoints(30, 0f));
    assertEquals(1507, ExperienceMath.totalPoints(31, 0f));
    assertEquals(1628, ExperienceMath.totalPoints(32, 0f));
}

@Test
void roundTripsThroughLevelForTotal() {
    for (int level = 0; level <= 200; level++) {
        assertEquals(level, ExperienceMath.levelForTotal(ExperienceMath.totalPoints(level, 0f)));
    }
}

@Test
void eachLevelCostsTheVanillaAmount() {
    assertEquals(37,  ExperienceMath.totalPoints(16, 0f) - ExperienceMath.totalPoints(15, 0f));
    assertEquals(42,  ExperienceMath.totalPoints(17, 0f) - ExperienceMath.totalPoints(16, 0f));
    assertEquals(121, ExperienceMath.totalPoints(32, 0f) - ExperienceMath.totalPoints(31, 0f));
}

@Test
void progressWithinALevelAddsProportionally() {
    int atLevel16 = ExperienceMath.totalPoints(16, 0f);
    int halfway   = ExperienceMath.totalPoints(16, 0.5f);
    assertTrue(halfway > atLevel16 && halfway < ExperienceMath.totalPoints(17, 0f));
}
```

Vanilla's piecewise formula. **The two formulas below use different breakpoints, and that is not a
typo** — mixing them up is the classic bug in XP code, which is why both are pinned by tests above.

Cumulative points to reach a level:

| Level range | Total points |
|---|---|
| `0 ≤ level ≤ 16` | `level² + 6·level` |
| `17 ≤ level ≤ 31` | `2.5·level² − 40.5·level + 360` |
| `level ≥ 32` | `4.5·level² − 162.5·level + 2220` |

Points to advance one level:

| Level range | Cost of the next level |
|---|---|
| `0 ≤ level ≤ 15` | `2·level + 7` |
| `16 ≤ level ≤ 30` | `5·level − 38` |
| `level ≥ 31` | `9·level − 158` |

Compute the cumulative totals in integer arithmetic — multiply the fractional coefficients through
(e.g. express `2.5·level² − 40.5·level + 360` as `(5·level² − 81·level + 720) / 2`, which divides
exactly for every integer level in range). No floating-point intermediate may appear in the
cumulative-total or cost-of-next-level calculations.

The one permitted exception is the `float progress` parameter itself, which necessarily arrives as a
`float` because that is what Bukkit's `Player#getExp()` returns. `totalPoints(level, progress)` adds
`Math.round(progress * costOfNextLevel(level))` to the cumulative total for `level`, converting to
`int` immediately. This is XP progress, not money — the money ban in the Global Constraints is
about balances and amounts, and no `Diamonds` value is involved here.

- [ ] **Step 4: Run all three to verify failure**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='DiamondsTest,LedgerTest,ExperienceMathTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 5: Implement all four classes**

`LedgerException` extends `RuntimeException` and carries a `Reason` enum: `INSUFFICIENT_FUNDS`, `MALFORMED_AMOUNT`, `AMOUNT_TOO_LARGE`, `NEGATIVE_AMOUNT`. The command layer in Task 5 maps each reason to a player-facing message; the ledger never formats player text itself.

- [ ] **Step 6: Run to verify pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest='DiamondsTest,LedgerTest,ExperienceMathTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/ledger src/test/java/org/xpfarm/farmersmarket/ledger
git commit -m "feat(ledger): integer diamond ledger, atomic transfers, and XP math"
```

---

## Task 5: Plugin wiring and the /market command

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/FarmersMarketPlugin.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/command/MarketCommand.java`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java`

**Interfaces:**
- Consumes: `FmConfig`, `BukkitConfigSource`, `Database`, `Migrations`, `DatabaseExecutor`, `AccountDao`, `Ledger`, `Diamonds`, `LedgerException`, `EditionResolver`.
- Produces: the plugin's runtime entry point. No later M1 task depends on it.

**Correction from Task 4, supersedes the Task 4 interface block above:** `Ledger`'s constructor is
`Ledger(Database, AccountDao, DatabaseExecutor)` — **three arguments, not two**. The two-arg form
this plan originally specified could not satisfy the atomicity requirement, because `AccountDao`
runs no transactions and exposes no connection. Wire all three.

**Trim `plugin.yml` to what M1 actually implements.** Declaring commands and permissions for unbuilt features is a lie to operators. Remove `farmersmarket.vendor.place`, `farmersmarket.stall.rent`, `farmersmarket.chart`, `farmersmarket.admin.floor`, `farmersmarket.admin.audit`, `farmersmarket.admin.freeze`, `farmersmarket.admin.pot`, `farmersmarket.bypass.fees`, and `farmersmarket.bypass.buylimit`. Keep `farmersmarket.use`, `farmersmarket.admin`, and `farmersmarket.admin.reload`, and drop the removed children from the `farmersmarket.admin` `children:` block. Update the `usage:` string to `/market [balance | deposit | withdraw | reload]`. Later milestones re-add their own nodes.

- [ ] **Step 1: Update `PluginDescriptorTest` first**

Change the permission loop to exactly the three surviving nodes, and add an assertion that the retired nodes are **absent** — that is what stops a future edit silently re-introducing an undeclared-but-checked node:

```java
@Test
void pluginYmlDeclaresEveryPermissionTheCodeChecks() throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");
    assertNotNull(permissions, "permissions section is required");

    for (String node : new String[] {
        "farmersmarket.use", "farmersmarket.admin", "farmersmarket.admin.reload",
    }) {
        assertTrue(permissions.containsKey(node), node + " must be declared");
    }
}

@Test
void pluginYmlDoesNotDeclarePermissionsForUnbuiltMilestones() throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");

    for (String unbuilt : new String[] {
        "farmersmarket.vendor.place", "farmersmarket.stall.rent", "farmersmarket.chart",
        "farmersmarket.admin.floor", "farmersmarket.admin.audit",
        "farmersmarket.admin.freeze", "farmersmarket.admin.pot",
        "farmersmarket.bypass.fees", "farmersmarket.bypass.buylimit",
    }) {
        assertFalse(permissions.containsKey(unbuilt),
                unbuilt + " belongs to a later milestone and must not be declared yet");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PluginDescriptorTest`
Expected: FAIL — the retired nodes are still declared.

- [ ] **Step 3: Trim `plugin.yml`, then re-run**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PluginDescriptorTest`
Expected: PASS.

- [ ] **Step 4: Implement `FarmersMarketPlugin`**

`onEnable`, in order:

1. `saveDefaultConfig()`.
2. Load `FmConfig` from a `BukkitConfigSource` over `getConfig()`, routing each warning to `getLogger().warning(...)`.
3. Resolve the database path to `getDataFolder().toPath().resolve("market.db")`, creating the data folder if absent. Resolve `storage.sqlite-tmpdir` relative to the server root if it is not absolute.
4. Open the `Database`, run `Migrations.applyTo`, log the resulting schema version at INFO.
5. Construct `DatabaseExecutor`, `AccountDao`, `Ledger`.
6. Construct `EditionResolver.create(getLogger())`.
7. Register `MarketCommand` against `getCommand("market")`. **If `getCommand("market")` returns null, log SEVERE and disable the plugin** rather than proceeding with a null reference.
8. Register a `PlayerJoinEvent` listener that, for a player the `EditionResolver` reports as Bedrock, checks whether a Floodgate→Java merge is needed and calls `ledger.mergeAccounts(...)` asynchronously. Do not send forms on join — Floodgate #605 throws NPE during the 1.20.2+ configuration phase.

**Any failure in steps 3–5 must disable the plugin cleanly** — log the exception with context, close whatever was opened, and call `getServer().getPluginManager().disablePlugin(this)`. A plugin that enables with a dead database and then throws on every command is worse than one that refuses to start.

`onDisable`: close `DatabaseExecutor` first (its bounded flush drains pending writes), then `Database`. Guard both against null so a failed enable does not produce an NPE cascade in disable.

- [ ] **Step 5: Implement `MarketCommand`**

Implements `CommandExecutor` and `TabCompleter`. Subcommands:

| Input | Permission | Behaviour |
|---|---|---|
| `/market` or `/market balance` | `farmersmarket.use` | Shows diamond balance and current XP points |
| `/market deposit [qty]` | `farmersmarket.use` | Moves diamonds from inventory to ledger; no qty means the whole inventory's diamonds |
| `/market withdraw <qty>` | `farmersmarket.use` | Moves ledger diamonds back to inventory |
| `/market reload` | `farmersmarket.admin.reload` | Re-reads config.yml; never a server-wide plugin reload |

Rules:

- **Console cannot run `balance`, `deposit`, or `withdraw`** — they need a player. Reply with a clear message rather than throwing a `ClassCastException`. `reload` must work from console, because that is how gate 7a exercises it over RCON.
- **Every database call is async; every Bukkit API call returns to the main thread.** Use `getServer().getScheduler().runTask(plugin, ...)` in the future's completion, never touch inventories from the executor thread.
- **`deposit` must remove items before crediting**, and if the credit fails, give the items back. **`withdraw` must debit before giving items**, and if the inventory has no room, re-credit and tell the player. Never take without giving, never give without taking.
- **Withdraw must respect inventory space.** Compute how much fits; if none does, refuse with a message and do not debit.
- All output is plain legacy-coloured chat. No hover, no click, no hex, no strikethrough, no underline.
- `LedgerException.Reason` maps to messages here: `INSUFFICIENT_FUNDS` → "You only have X diamonds."; `MALFORMED_AMOUNT` → "That is not a valid amount."; `AMOUNT_TOO_LARGE` → "That amount is too large."; `NEGATIVE_AMOUNT` → "Amount must be positive."
- Tab completion offers only the subcommands the sender has permission for.

- [ ] **Step 6: Run the full build**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, all tests passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket src/main/resources/plugin.yml src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java
git commit -m "feat(plugin): lifecycle wiring and the /market command tree"
```

---

## Out of scope for M1 — do not build

Naming these explicitly because a helpful implementer will otherwise add them:

- Listings, buy orders, sell orders, escrow, fees, sales tax, the community pot. **M2.**
- Item identity, `ItemStack` serialization, commodity vs unique keys. **M2.**
- Any inventory GUI, any Cumulus form, any `BarGlyphs` rendering. **M3.**
- Vendor entities, `TextDisplay` labels, stalls, sealed bids. **M4.**
- Price history, indices, map charts, `MapRenderer`. **M5.**
- The `trades` table and the immutable trade log. **M2** — it must land in the same milestone as the first trade, never later.

`FmConfig` parses the settings those milestones need. That is deliberate and is not permission to implement the features.
