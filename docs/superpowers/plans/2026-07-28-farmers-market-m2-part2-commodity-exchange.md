# Farmers Market M2 Part 2 — the commodity exchange Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the anonymous commodity exchange — a buy-side-only order book where buyers rest priced bids and sellers dump stacks instantly against the best bids and a pot-funded server floor — on top of the Part 1 market spine, shipping as `0.3.0`.

**Architecture:** Commodities (stackable, no meaningful components) are fungible by material. Buyers place **resting bids** (a new `commodity_offers` table); a bid escrows the buyer's diamonds into the offer row and costs the buyer a 1% XP fee. A **market-sell** is instant and priceless: it walks the resting bids for that material highest-price-first then oldest-first, filling each in one atomic `TransactionRunner` transaction (money move + escrow spend + offer decrement + buyer delivery to `pending_items` + append-only `trades` row), and dumps any remainder to the server floor — the community pot buying at the audited floor price. Every new value/DAO/math class stays Bukkit-free; only `BukkitItemCodec`, `MarketCommand`, and `FarmersMarketPlugin` touch `org.bukkit`.

**Tech Stack:** Java 25, Paper 26.1.2 API, SQLite (provided `org.xerial:sqlite-jdbc`), JUnit 5. No new dependencies.

## Global Constraints

Binding on every task. A task that violates any of these is not complete. Copied from Part 1 with only the version line updated; everything else still binds.

- **Java 25**, **Paper `26.1.2` build 74**, `api-version: '26.1'` in `plugin.yml`. Do not lower `api-version`.
- Maven group `org.xpfarm`, artifactId `farmers-market`, **version `0.3.0`**. Root package `org.xpfarm.farmersmarket`. (Task 1 bumps `pom.xml`; `plugin.yml` already reads `${project.version}`.)
- **AGPL-3.0-or-later.** Every new `.java` file carries the project's license header. Match the header in `src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java` verbatim in structure, changing only the one-line description.
- **No external services.** Zero outbound network calls. No HTTP clients, Ollama, Umami, or telemetry.
- **No new dependencies.** Built on M1's four packages (`config`, `storage`, `identity`, `ledger`) plus the Part 1 `market` package plus Bukkit. Do not add Mockito, MockBukkit, HikariCP, Guava, Apache Commons, or any other library.
- **Tests ship with the code, not after it.** Any logic separable from the Bukkit runtime is unit tested in the same task that writes it. Classes in `config`, `storage`, `identity`, `ledger`, and `market` must not import `org.bukkit.*` at all, with the sole exceptions of the named Bukkit adapters (`BukkitConfigSource`, `EditionResolver`, `BukkitItemCodec`, `MarketCommand`, `FarmersMarketPlugin`). That import ban is what makes them testable without a server.
- **Money is integer-only.** No `double`, `float`, or `BigDecimal` ever holds a balance or an amount. Every balance change goes through the `Diamonds` type and its overflow-refusing arithmetic — including inside the fill transaction, which must never add or subtract raw `long` dust. The one permitted `double` is a **config-supplied rate** (fee/tax percent, burn share) converted to integer basis points **once** (Part 1's `MarketMath` already does this) or a **config-supplied floor price** (diamonds) converted to dust **once** at the command layer and never touched as a `double` again.
- **Geyser/Floodgate/Bedrock safety.** All player-facing output is plain legacy-coloured chat text. Forbidden: hover events, click events, hex/RGB colours, gradients, strikethrough, underline, and reliance on `▁▂▃▄▅▆▇`. Legacy colour codes and `█▓▒░` are safe. Commodities are plain stackable items, so the Geyser #3001 heavy-NBT-icon hazard does not apply here — but no item icon is drawn at all in Part 2 (M3 adds UI); a commodity is named in chat text only.
- **Never key player data on username.** Always UUID — buyer, seller, and the community pot.
- Follow the house style already in this repo — package layout, value-type shape, prepared-statement DAO shape, javadoc voice, comment density. Read the neighbouring Part 1 class before writing a new one.
- Build must pass `mvn --batch-mode --no-transfer-progress clean verify`.

### Settled facts — do not re-derive, do not "improve"

M1's and Part 1's settled facts still bind (SQLite driver provided-not-shaded, `org.sqlite.*` never relocated, one connection on one thread, `foreign_keys=ON`, `onDisable` flushes, item identity via `serializeAsBytes`/`deserializeBytes`, the append-only `trades` table with its two conservation CHECKs, the atomic operation is one `TransactionRunner.inTransaction` over `AccountDao` + `MarketDao` and never `Ledger.transfer`, the community pot is the nil-UUID `accounts` row, the listing fee is real vanilla XP charged *after* the escrow write succeeds and is non-refundable, and every diamond spent comes from the ledger balance not the inventory). Add these, specific to Part 2 — each was decided in the 2026-07-28 brainstorming session and is not open for reinterpretation by an implementer:

1. **Buy-side-only order book.** Only buy offers rest. A sell never rests. There is no server *ask* (the server never sells commodities to players); there is only the server *bid* (the buy-back floor). Do not add a resting sell table or a symmetric book.
2. **Priceless market-sell.** `/market sell` on a commodity takes a quantity, never a price. It always executes at the best available price: highest resting bid first, ties broken by oldest bid, then the remainder to the floor. Whatever cannot fill (no bids and no/insufficient floor) is returned to the seller's inventory, never rested.
3. **The floor is the community pot.** A floor buy is `COMMUNITY_POT → seller`, **untaxed and unburned** (the floor price is already what the seller nets). It drains the pot by exactly `qty × floorPrice`. When the pot cannot cover the whole remainder, the floor buys only what the pot balance affords; the rest returns to the seller. The floor is a backstop, not a guarantee. A floor for a material exists only when `liquidity.buyback-enabled` is true *and* that material has a `liquidity.buyback-floors` entry that survived the Part 1 farm-output audit at config load.
4. **"Resting in the book costs 1% XP."** Uniform rule. Uniques: the seller pays it (Part 1). Commodities: the **buyer** pays it when placing a bid, computed as `MarketMath.listingFeeXp(bidValue, ...)` where `bidValue = priceEach × qty`. Non-refundable on cancel or fill. Charged in real XP at the command layer *after* the diamond escrow write succeeds, exactly as Part 1 charges the sell fee.
5. **Every player-to-player fill is taxed; floor sales are not.** A fill against a player bid taxes the seller's proceeds via `MarketMath.taxOnSale` (7% default, half burned, half to pot) — identical to Part 1's unique sale. A floor sale (buyer = the pot) is exempt (fact 3).
6. **Escrowed diamonds live in the offer row, not in any account.** `placeBid` debits the buyer's `accounts` balance and records the amount in `commodity_offers.escrowed_dust`. Money supply = Σ account balances + Σ active-offer `escrowed_dust`. A fill moves escrow → seller/pot and burns the burn share, so supply falls by exactly the burn. A cancel returns the *remaining* escrow to the buyer. This mirrors how a listing escrows an *item* out of the world into the row.
7. **Buy limits are enforced at fill time, fill-to-cap-then-cancel.** A buyer's rolling-window usage is `SUM(trades.amount)` where `buyer = X, material_key = Y, happened_at ≥ now − window`. When a fill would carry a buyer past `liquidity.buy-limits[material]`, the fill takes only up to the cap, and the bid's remaining quantity is then **cancelled and its remaining escrow refunded** — the bid does not survive to the next window. Placement is not limited. Buy limits apply only when `liquidity.buy-limit-enabled` is true.
8. **Bids do not expire.** Uniques expire (Part 1's scheduler); commodity bids rest until filled or cancelled. Do not add a bid-expiry sweep in Part 2.
9. **A seller never fills their own bid.** The fill loop skips any bid whose `buyer` equals the selling player — a self-fill is a wash trade and is silently skipped, not an error.
10. **Commodity delivery is always offline-safe.** The buyer of a fill is not the actor (the seller is), so filled commodities are written to the buyer's `pending_items` inside the fill transaction and claimed later with the existing `/market claim`. Never assume the buyer is online.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `pom.xml` | version `0.2.0` → `0.3.0` | 1 |
| `storage/Migrations.java` (modify) | add `MIGRATION_3` (the `commodity_offers` table) and append it to `MIGRATIONS` | 1 |
| `market/OfferStatus.java` (create) | `ACTIVE`/`FILLED`/`CANCELLED` enum with case-insensitive `fromStored` | 1 |
| `market/CommodityOfferRow.java` (create) | immutable row record for a resting bid | 1 |
| `market/MarketDao.java` (modify) | offer insert/find/best-bids/spend/cancel/by-buyer + `buyLimitUsage` + `bestBidPriceDust` | 1 |
| `market/CommodityMath.java` (create) | pure buy-limit allowance and pot-affordability clamps | 2 |
| `market/BukkitItemCodec.java` (modify) | resolve a material *name* to a canonical commodity spec; canonical single-item form of a held commodity | 3 |
| `market/CommoditySpec.java` (create) | Bukkit-free value: `(materialKey, itemKey, byte[] oneItemBytes, displayName)` | 3 |
| `market/MarketException.java` (modify) | add `NOT_A_COMMODITY`; remove the now-dead `COMMODITY_NOT_YET` | 4 |
| `market/CommoditySaleResult.java` (create) | Bukkit-free result of a market-sell: `sold`, `unsold`, `proceeds`, fills | 4 |
| `market/MarketService.java` (modify) | `placeBid`, `marketSell` (the atomic multi-fill), `cancelBid`, `myBids`, `bestBidDust` | 4 |
| `command/MarketResolver.java` (modify) | `BID`/`PRICE`/`CANCELBID` subcommands, new outcomes, dual-parse for `SELL`, `messageFor` update | 5 |
| `command/MarketCommand.java` (modify) | dispatch `bid`/`price`/`cancelbid`, route `sell` by item class, extend `mine` | 6 |
| `FarmersMarketPlugin.java` (modify) | no new wiring beyond passing config values into the new command paths | 6 |
| `src/main/resources/plugin.yml` (modify) | `usage:` string names the new subcommands | 6 |
| `src/test/java/.../PluginDescriptorTest.java` (modify) | assert the new usage string; assert no new permissions | 6 |

Test files are created/extended alongside each task: `MigrationsTest`, `MarketDaoTest`, `CommodityMathTest`, `MarketServiceTest`, `MarketResolverTest`, `PluginDescriptorTest`.

---

## Task 1: Migration 3, the offer row, and the offer DAO

**Files:**
- Modify: `pom.xml` (version)
- Modify: `src/main/java/org/xpfarm/farmersmarket/storage/Migrations.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/OfferStatus.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/CommodityOfferRow.java`
- Modify: `src/main/java/org/xpfarm/farmersmarket/market/MarketDao.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/storage/MigrationsTest.java`, `src/test/java/org/xpfarm/farmersmarket/market/MarketDaoTest.java`

**Interfaces:**
- Consumes: `Database` (`storage`), `SystemAccounts.COMMUNITY_POT`, the existing `trades` table (columns `buyer_uuid`, `material_key`, `amount`, `happened_at`).
- Produces:
  - `enum OfferStatus { ACTIVE, FILLED, CANCELLED; static OfferStatus fromStored(String) }` — `fromStored` is case-insensitive and throws `IllegalArgumentException` on an unknown value (mirror `ListingStatus.fromStored`).
  - `record CommodityOfferRow(long id, UUID buyer, String materialKey, int qtyRemaining, long priceEachDust, long escrowedDust, int xpPaid, long createdAt, OfferStatus status)` with a compact constructor rejecting `qtyRemaining < 0`, `priceEachDust <= 0`, `escrowedDust < 0`, and null `buyer`/`materialKey`/`status`.
  - `MarketDao`: `long insertOffer(CommodityOfferRow)`, `Optional<CommodityOfferRow> findActiveOffer(long id)`, `List<CommodityOfferRow> bestActiveBids(String materialKey, int limit)`, `int spendFromOffer(long id, int qtyFilled, long escrowSpentDust)`, `int cancelOffer(long id)`, `List<CommodityOfferRow> offersByBuyer(UUID buyer, OfferStatus status)`, `int buyLimitUsage(UUID buyer, String materialKey, long sinceEpochMs)`, `Optional<Long> bestBidPriceDust(String materialKey)`.

- [ ] **Step 1: Bump the version**

In `pom.xml`, change the project `<version>` from `0.2.0` to `0.3.0`. (`plugin.yml` reads `${project.version}` — do not touch it.)

- [ ] **Step 2: Write the failing migration test**

Add to `MigrationsTest.java`:

```java
@Test
void migration3CreatesCommodityOffersAndReachesVersion3() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
        int version = Migrations.applyTo(c);
        assertEquals(3, version, "newest schema version");
        // commodity_offers exists and accepts a valid row
        try (var st = c.createStatement()) {
            st.execute("INSERT INTO commodity_offers"
                    + "(buyer_uuid, material_key, qty_remaining, price_each_dust, escrowed_dust, xp_paid, created_at)"
                    + " VALUES ('u', 'minecraft:iron_ingot', 64, 3000, 192000, 2, 100)");
        }
    }
}

@Test
void migration3RejectsNegativeQtyAndNonPositivePrice() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
        Migrations.applyTo(c);
        try (var st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO commodity_offers"
                    + "(buyer_uuid, material_key, qty_remaining, price_each_dust, escrowed_dust, xp_paid, created_at)"
                    + " VALUES ('u','k', -1, 3000, 0, 0, 1)"), "qty_remaining >= 0 CHECK");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO commodity_offers"
                    + "(buyer_uuid, material_key, qty_remaining, price_each_dust, escrowed_dust, xp_paid, created_at)"
                    + " VALUES ('u','k', 1, 0, 0, 0, 1)"), "price_each_dust > 0 CHECK");
        }
    }
}
```

- [ ] **Step 3: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MigrationsTest test`
Expected: FAIL — `applyTo` returns 2, and `commodity_offers` does not exist.

- [ ] **Step 4: Add MIGRATION_3**

In `Migrations.java`, add after `MIGRATION_2` (mirror its text-block style and javadoc voice):

```java
/**
 * Migration 3: the commodity exchange's resting bids. Only buy offers rest (Part 2 is a
 * buy-side-only book), so this is a single mutable table -- like {@code listings}, not
 * append-only like {@code trades}. {@code escrowed_dust} holds the buyer's diamonds that were
 * debited from their account at bid time and that a fill spends or a cancel refunds; the three
 * CHECKs are the same fail-closed discipline as {@code accounts.diamonds_dust >= 0}.
 */
private static final String[] MIGRATION_3 = {
        """
        CREATE TABLE IF NOT EXISTS commodity_offers (
            id                INTEGER PRIMARY KEY AUTOINCREMENT,
            buyer_uuid        TEXT    NOT NULL,
            material_key      TEXT    NOT NULL,
            qty_remaining     INTEGER NOT NULL,
            price_each_dust   INTEGER NOT NULL,
            escrowed_dust     INTEGER NOT NULL,
            xp_paid           INTEGER NOT NULL,
            created_at        INTEGER NOT NULL,
            status            TEXT    NOT NULL DEFAULT 'ACTIVE',
            CHECK (qty_remaining >= 0),
            CHECK (price_each_dust > 0),
            CHECK (escrowed_dust >= 0)
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_offers_active ON commodity_offers"
                + "(material_key, status, price_each_dust DESC, created_at ASC)",
        "CREATE INDEX IF NOT EXISTS idx_offers_buyer ON commodity_offers(buyer_uuid, status)"
};
```

Then extend the `MIGRATIONS` list:

```java
private static final List<String[]> MIGRATIONS = List.<String[]>of(MIGRATION_1, MIGRATION_2, MIGRATION_3);
```

- [ ] **Step 5: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MigrationsTest test`
Expected: PASS.

- [ ] **Step 6: Create `OfferStatus`**

Create `market/OfferStatus.java` mirroring `ListingStatus` exactly (license header, javadoc, case-insensitive `fromStored` that uppercases and calls `valueOf`, throwing `IllegalArgumentException` on unknown input). Constants: `ACTIVE, FILLED, CANCELLED`.

- [ ] **Step 7: Create `CommodityOfferRow`**

Create `market/CommodityOfferRow.java` as the record described in the Interfaces block, with a compact constructor that runs `Objects.requireNonNull` on `buyer`, `materialKey`, `status` and throws `IllegalArgumentException` for `qtyRemaining < 0`, `priceEachDust <= 0`, or `escrowedDust < 0`. Match `ListingRow`'s header and voice.

- [ ] **Step 8: Write the failing DAO tests**

Add to `MarketDaoTest.java` (reuse the file's existing in-memory `Database` fixture and helpers):

```java
@Test
void insertAndFindActiveOfferRoundTrips() throws Exception {
    UUID buyer = UUID.randomUUID();
    long id = dao.insertOffer(new CommodityOfferRow(0L, buyer, "minecraft:iron_ingot",
            64, 3000L, 192000L, 2, 100L, OfferStatus.ACTIVE));
    CommodityOfferRow row = dao.findActiveOffer(id).orElseThrow();
    assertEquals(buyer, row.buyer());
    assertEquals(64, row.qtyRemaining());
    assertEquals(3000L, row.priceEachDust());
    assertEquals(192000L, row.escrowedDust());
}

@Test
void bestActiveBidsOrdersByPriceThenAge() throws Exception {
    UUID a = UUID.randomUUID();
    long older = dao.insertOffer(new CommodityOfferRow(0L, a, "k", 10, 5000L, 50000L, 1, 100L, OfferStatus.ACTIVE));
    long higher = dao.insertOffer(new CommodityOfferRow(0L, a, "k", 10, 9000L, 90000L, 1, 200L, OfferStatus.ACTIVE));
    long olderSamePrice = dao.insertOffer(new CommodityOfferRow(0L, a, "k", 10, 5000L, 50000L, 1, 50L, OfferStatus.ACTIVE));
    List<CommodityOfferRow> bids = dao.bestActiveBids("k", 10);
    assertEquals(higher, bids.get(0).id(), "highest price first");
    assertEquals(olderSamePrice, bids.get(1).id(), "same price -> oldest (created_at 50) first");
    assertEquals(older, bids.get(2).id());
}

@Test
void spendFromOfferDecrementsAndFillsAtZero() throws Exception {
    long id = dao.insertOffer(new CommodityOfferRow(0L, UUID.randomUUID(), "k", 10, 1000L, 10000L, 0, 1L, OfferStatus.ACTIVE));
    assertEquals(1, dao.spendFromOffer(id, 4, 4000L));
    CommodityOfferRow after = dao.findActiveOffer(id).orElseThrow();
    assertEquals(6, after.qtyRemaining());
    assertEquals(6000L, after.escrowedDust());
    assertEquals(1, dao.spendFromOffer(id, 6, 6000L));
    assertTrue(dao.findActiveOffer(id).isEmpty(), "fully spent -> no longer ACTIVE");
}

@Test
void spendFromOfferOnNonActiveReturnsZero() throws Exception {
    long id = dao.insertOffer(new CommodityOfferRow(0L, UUID.randomUUID(), "k", 5, 1000L, 5000L, 0, 1L, OfferStatus.ACTIVE));
    assertEquals(1, dao.cancelOffer(id));
    assertEquals(0, dao.spendFromOffer(id, 1, 1000L), "cancelled offer cannot be spent");
}

@Test
void buyLimitUsageSumsTradesInWindow() throws Exception {
    UUID buyer = UUID.randomUUID();
    UUID seller = UUID.randomUUID();
    dao.insertTrade(new TradeRow(0L, 1000L, buyer, seller, ItemClass.COMMODITY, "ik", "k", 30,
            30000L, 0L, 0L, 0L, 30000L, null));
    dao.insertTrade(new TradeRow(0L, 2000L, buyer, seller, ItemClass.COMMODITY, "ik", "k", 20,
            20000L, 0L, 0L, 0L, 20000L, null));
    dao.insertTrade(new TradeRow(0L, 500L, buyer, seller, ItemClass.COMMODITY, "ik", "k", 99,
            99000L, 0L, 0L, 0L, 99000L, null)); // before the window
    assertEquals(50, dao.buyLimitUsage(buyer, "k", 1000L), "only happened_at >= 1000 counts");
}
```

- [ ] **Step 9: Run them, verify they fail**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MarketDaoTest test`
Expected: FAIL — the offer methods do not exist.

- [ ] **Step 10: Implement the offer DAO methods**

In `MarketDao.java`, add the methods below (match the file's prepared-statement shape and its `mapListing`/`generatedId` helper style). Add a `mapOffer(ResultSet)` private helper.

```java
public long insertOffer(CommodityOfferRow row) throws SQLException {
    String sql = "INSERT INTO commodity_offers"
            + "(buyer_uuid, material_key, qty_remaining, price_each_dust, escrowed_dust, xp_paid, created_at, status)"
            + " VALUES (?,?,?,?,?,?,?,?)";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setString(1, row.buyer().toString());
        ps.setString(2, row.materialKey());
        ps.setInt(3, row.qtyRemaining());
        ps.setLong(4, row.priceEachDust());
        ps.setLong(5, row.escrowedDust());
        ps.setInt(6, row.xpPaid());
        ps.setLong(7, row.createdAt());
        ps.setString(8, row.status().name());
        ps.executeUpdate();
        return generatedId(ps);
    }
}

public Optional<CommodityOfferRow> findActiveOffer(long id) throws SQLException {
    String sql = "SELECT * FROM commodity_offers WHERE id = ? AND status = 'ACTIVE'";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setLong(1, id);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(mapOffer(rs)) : Optional.empty();
        }
    }
}

public List<CommodityOfferRow> bestActiveBids(String materialKey, int limit) throws SQLException {
    String sql = "SELECT * FROM commodity_offers WHERE material_key = ? AND status = 'ACTIVE'"
            + " ORDER BY price_each_dust DESC, created_at ASC, id ASC LIMIT ?";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setString(1, materialKey);
        ps.setInt(2, limit);
        return queryOffers(ps);
    }
}

/** Decrements an ACTIVE offer; flips it to FILLED when its remaining quantity reaches zero. */
public int spendFromOffer(long id, int qtyFilled, long escrowSpentDust) throws SQLException {
    String sql = "UPDATE commodity_offers SET"
            + " qty_remaining = qty_remaining - ?,"
            + " escrowed_dust = escrowed_dust - ?,"
            + " status = CASE WHEN qty_remaining - ? = 0 THEN 'FILLED' ELSE status END"
            + " WHERE id = ? AND status = 'ACTIVE'";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setInt(1, qtyFilled);
        ps.setLong(2, escrowSpentDust);
        ps.setInt(3, qtyFilled);
        ps.setLong(4, id);
        return ps.executeUpdate();
    }
}

public int cancelOffer(long id) throws SQLException {
    String sql = "UPDATE commodity_offers SET status = 'CANCELLED' WHERE id = ? AND status = 'ACTIVE'";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setLong(1, id);
        return ps.executeUpdate();
    }
}

public List<CommodityOfferRow> offersByBuyer(UUID buyer, OfferStatus status) throws SQLException {
    String sql = "SELECT * FROM commodity_offers WHERE buyer_uuid = ? AND status = ? ORDER BY created_at DESC";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setString(1, buyer.toString());
        ps.setString(2, status.name());
        return queryOffers(ps);
    }
}

public int buyLimitUsage(UUID buyer, String materialKey, long sinceEpochMs) throws SQLException {
    String sql = "SELECT COALESCE(SUM(amount), 0) FROM trades"
            + " WHERE buyer_uuid = ? AND material_key = ? AND happened_at >= ?";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setString(1, buyer.toString());
        ps.setString(2, materialKey);
        ps.setLong(3, sinceEpochMs);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}

public Optional<Long> bestBidPriceDust(String materialKey) throws SQLException {
    String sql = "SELECT MAX(price_each_dust) FROM commodity_offers"
            + " WHERE material_key = ? AND status = 'ACTIVE'";
    try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
        ps.setString(1, materialKey);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long v = rs.getLong(1);
                return rs.wasNull() ? Optional.empty() : Optional.of(v);
            }
            return Optional.empty();
        }
    }
}
```

Add the `mapOffer` and `queryOffers` helpers mirroring `mapListing`/`queryListings`:

```java
private static List<CommodityOfferRow> queryOffers(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
        List<CommodityOfferRow> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(mapOffer(rs));
        }
        return rows;
    }
}

private static CommodityOfferRow mapOffer(ResultSet rs) throws SQLException {
    return new CommodityOfferRow(
            rs.getLong("id"),
            UUID.fromString(rs.getString("buyer_uuid")),
            rs.getString("material_key"),
            rs.getInt("qty_remaining"),
            rs.getLong("price_each_dust"),
            rs.getLong("escrowed_dust"),
            rs.getInt("xp_paid"),
            rs.getLong("created_at"),
            OfferStatus.fromStored(rs.getString("status")));
}
```

- [ ] **Step 11: Run the DAO tests, verify they pass**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MarketDaoTest test`
Expected: PASS.

- [ ] **Step 12: Mutation check the ordering and the fill-at-zero**

Temporarily change `ORDER BY price_each_dust DESC` to `ASC` in `bestActiveBids` and rerun — `bestActiveBidsOrdersByPriceThenAge` must fail. Revert. Then change `WHEN qty_remaining - ? = 0` to `= 1` in `spendFromOffer` and rerun — `spendFromOfferDecrementsAndFillsAtZero` must fail. Revert. Confirm both pass again.

- [ ] **Step 13: Full build and commit**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS.

```bash
git add pom.xml src/main/java/org/xpfarm/farmersmarket/storage/Migrations.java \
  src/main/java/org/xpfarm/farmersmarket/market/OfferStatus.java \
  src/main/java/org/xpfarm/farmersmarket/market/CommodityOfferRow.java \
  src/main/java/org/xpfarm/farmersmarket/market/MarketDao.java \
  src/test/java/org/xpfarm/farmersmarket/storage/MigrationsTest.java \
  src/test/java/org/xpfarm/farmersmarket/market/MarketDaoTest.java
git commit -m "feat(m2p2): Migration 3 commodity_offers table + offer DAO"
```

---

## Task 2: `CommodityMath` — the pure clamps

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/market/CommodityMath.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/market/CommodityMathTest.java`

**Interfaces:**
- Produces: `static int remainingBuyAllowance(int cap, int usedInWindow)` and `static int floorFillableByPot(int remainingQty, long potBalanceDust, long floorPriceDust)` — both pure, no Bukkit, no I/O.

**Rationale:** These two decisions — "how much may this buyer still buy this window" and "how many units can the pot afford at the floor" — are the only non-trivial arithmetic the fill loop does that is separable from the transaction. Isolating them here makes them unit-testable and mutation-checkable; the fill loop in Task 4 calls them.

- [ ] **Step 1: Write the failing tests**

Create `CommodityMathTest.java`:

```java
class CommodityMathTest {
    @Test
    void allowanceIsCapMinusUsed() {
        assertEquals(40, CommodityMath.remainingBuyAllowance(100, 60));
        assertEquals(0, CommodityMath.remainingBuyAllowance(100, 100));
    }

    @Test
    void allowanceNeverNegative() {
        assertEquals(0, CommodityMath.remainingBuyAllowance(100, 130), "over-cap clamps to 0, not -30");
    }

    @Test
    void negativeCapMeansUnlimited() {
        assertEquals(Integer.MAX_VALUE, CommodityMath.remainingBuyAllowance(-1, 1_000_000));
    }

    @Test
    void floorFillableIsPotBalanceDividedByPrice() {
        assertEquals(5, CommodityMath.floorFillableByPot(64, 5000L, 1000L), "5000/1000 = 5 affordable");
    }

    @Test
    void floorFillableIsBoundedByRemaining() {
        assertEquals(3, CommodityMath.floorFillableByPot(3, 5000L, 1000L), "want 3, can afford 5 -> 3");
    }

    @Test
    void floorFillableIsZeroWhenPotEmptyOrNoFloor() {
        assertEquals(0, CommodityMath.floorFillableByPot(10, 0L, 1000L));
        assertEquals(0, CommodityMath.floorFillableByPot(10, 5000L, 0L), "no floor price -> 0");
        assertEquals(0, CommodityMath.floorFillableByPot(0, 5000L, 1000L));
    }
}
```

- [ ] **Step 2: Run them, verify they fail**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=CommodityMathTest test`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `CommodityMath`**

```java
/** Pure clamps used by the commodity fill loop. No Bukkit, no I/O; every value is a plain count. */
public final class CommodityMath {
    private CommodityMath() {
    }

    /**
     * How many more units {@code cap} allows a buyer who has already bought {@code usedInWindow}.
     * A negative {@code cap} means "no limit configured" and returns {@link Integer#MAX_VALUE}.
     */
    public static int remainingBuyAllowance(int cap, int usedInWindow) {
        if (cap < 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, cap - usedInWindow);
    }

    /**
     * How many units the pot can buy at {@code floorPriceDust} each, bounded by {@code remainingQty}.
     * Returns 0 when there is no floor price, an empty pot, or nothing left to sell.
     */
    public static int floorFillableByPot(int remainingQty, long potBalanceDust, long floorPriceDust) {
        if (remainingQty <= 0 || potBalanceDust <= 0 || floorPriceDust <= 0) {
            return 0;
        }
        long affordable = potBalanceDust / floorPriceDust;
        return (int) Math.min((long) remainingQty, affordable);
    }
}
```

- [ ] **Step 4: Run them, verify they pass**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=CommodityMathTest test`
Expected: PASS.

- [ ] **Step 5: Mutation check**

Change `Math.max(0, cap - usedInWindow)` to `cap - usedInWindow` and rerun — `allowanceNeverNegative` must fail. Revert. Change `Math.min((long) remainingQty, affordable)` to just `affordable` and rerun — `floorFillableIsBoundedByRemaining` must fail. Revert. Confirm green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/market/CommodityMath.java \
  src/test/java/org/xpfarm/farmersmarket/market/CommodityMathTest.java
git commit -m "feat(m2p2): CommodityMath buy-limit and pot-affordability clamps"
```

---

## Task 3: `CommoditySpec` and the codec's material resolution

**Files:**
- Create: `src/main/java/org/xpfarm/farmersmarket/market/CommoditySpec.java`
- Modify: `src/main/java/org/xpfarm/farmersmarket/market/BukkitItemCodec.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/market/CommoditySpecTest.java`

**Interfaces:**
- Produces:
  - `record CommoditySpec(String materialKey, String itemKey, byte[] oneItemBytes, String displayName)` — Bukkit-free value describing the canonical single-unit form of one commodity. Compact constructor defensively copies `oneItemBytes` and rejects nulls; `oneItemBytes()` returns a copy.
  - `BukkitItemCodec`: `Optional<CommoditySpec> commoditySpecFor(String materialName)` — parses a typed material name to a canonical commodity, or empty if the name is unknown or the material is not a fungible commodity (single-stack tools/armour, or anything with meaningful components on the plain form). And `CommoditySpec commodityOf(ItemStack held)` — the canonical spec for a held commodity stack (throws `MarketException(NOT_A_COMMODITY)` if the held item is not a commodity), used by the sell path.

**Note on testing.** `BukkitItemCodec` is one of the named Bukkit adapters and is verified at gate 7a, not by unit tests — its `ItemStack`/`Material` calls need a running server. `CommoditySpec` itself is a Bukkit-free value and *is* unit-tested. The codec's material parsing is exercised live at gate 7a (Task 6 records the specific checks). Do not attempt to unit-test `commoditySpecFor` without a server; do not add MockBukkit to make it testable.

- [ ] **Step 1: Write the failing `CommoditySpec` test**

Create `CommoditySpecTest.java`:

```java
class CommoditySpecTest {
    @Test
    void defensivelyCopiesBytesOnBothSides() {
        byte[] src = {1, 2, 3};
        CommoditySpec spec = new CommoditySpec("minecraft:iron_ingot", "abc123", src, "Iron Ingot");
        src[0] = 99;
        assertEquals(1, spec.oneItemBytes()[0], "compact ctor copied input");
        spec.oneItemBytes()[0] = 42;
        assertEquals(1, spec.oneItemBytes()[0], "accessor returns a copy");
    }

    @Test
    void rejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> new CommoditySpec(null, "k", new byte[]{1}, "n"));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=CommoditySpecTest test`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `CommoditySpec`**

```java
/**
 * The canonical single-unit form of one commodity: the namespaced material key both a bid and a
 * sell match on, the content-hash item key the trade log records, the serialized bytes of exactly
 * one plain item (delivered {@code amount}x to a buyer on a fill), and a display name for chat.
 * Bukkit-free by construction so it can cross into the market service and be tested without a server.
 */
public record CommoditySpec(String materialKey, String itemKey, byte[] oneItemBytes, String displayName) {
    public CommoditySpec {
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(displayName, "displayName");
        oneItemBytes = oneItemBytes.clone();
    }

    @Override
    public byte[] oneItemBytes() {
        return oneItemBytes.clone();
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=CommoditySpecTest test`
Expected: PASS.

- [ ] **Step 5: Add the codec methods**

In `BukkitItemCodec.java`, add the two methods. Reuse the existing content-hash helper Part 1 uses for `ItemKey` and the existing classification (`ItemClass.classify` + the `hasMeaningfulComponents` check). A plain single item of a stackable material has no meaningful components, so `classify` returns `COMMODITY` iff `maxStackSize > 1`.

```java
/**
 * Resolves a typed material name (e.g. "iron_ingot", "minecraft:iron_ingot", "IRON_INGOT") to the
 * canonical commodity spec, or empty when the name matches no material or the material is not a
 * fungible commodity. This is the only place a raw player-typed material string becomes a
 * {@code Material}; the market package never sees Bukkit types.
 */
public Optional<CommoditySpec> commoditySpecFor(String materialName) {
    Material material = Material.matchMaterial(materialName);
    if (material == null || !material.isItem()) {
        return Optional.empty();
    }
    ItemStack one = new ItemStack(material, 1);
    if (ItemClass.classify(material.getMaxStackSize(), hasMeaningfulComponents(one)) != ItemClass.COMMODITY) {
        return Optional.empty();
    }
    return Optional.of(specOf(material, one));
}

/**
 * The canonical spec for a held commodity stack (its amount is ignored — the spec is per-unit).
 *
 * @throws MarketException {@code NOT_A_COMMODITY} if {@code held} is not a fungible commodity
 */
public CommoditySpec commodityOf(ItemStack held) {
    Material material = held.getType();
    ItemStack one = new ItemStack(material, 1);
    if (ItemClass.classify(material.getMaxStackSize(), hasMeaningfulComponents(held)) != ItemClass.COMMODITY) {
        throw new MarketException(MarketException.Reason.NOT_A_COMMODITY,
                material.getKey() + " is not a fungible commodity");
    }
    return specOf(material, one);
}

private CommoditySpec specOf(Material material, ItemStack one) {
    byte[] bytes = one.serializeAsBytes();
    return new CommoditySpec(material.getKey().toString(), contentHash(bytes), bytes,
            prettyName(material));
}
```

Where `contentHash(byte[])` is the Part 1 helper already used to build an `ItemKey` (call the existing method; do not duplicate the hashing), and `prettyName(Material)` produces a plain display string (reuse Part 1's summary/name helper if one exists, otherwise a title-cased material key — no hover/click, plain text only). `NOT_A_COMMODITY` is added in Task 4; if implementing tasks strictly in order, Step 5 will not compile until Task 4 adds the reason — either implement Task 4's Step "add `NOT_A_COMMODITY`" first, or stub the throw with an existing reason and correct it in Task 4. **Prefer adding the enum constant now** (it is a one-line change) and note it in the Task 4 commit.

- [ ] **Step 6: Full build and commit**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS (the new codec methods compile; they are exercised at gate 7a).

```bash
git add src/main/java/org/xpfarm/farmersmarket/market/CommoditySpec.java \
  src/main/java/org/xpfarm/farmersmarket/market/BukkitItemCodec.java \
  src/test/java/org/xpfarm/farmersmarket/market/CommoditySpecTest.java
git commit -m "feat(m2p2): CommoditySpec + codec material resolution"
```

---

## Task 4: `MarketService` — place bid, market-sell, cancel bid

**Files:**
- Modify: `src/main/java/org/xpfarm/farmersmarket/market/MarketException.java`
- Create: `src/main/java/org/xpfarm/farmersmarket/market/CommoditySaleResult.java`
- Modify: `src/main/java/org/xpfarm/farmersmarket/market/MarketService.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/market/MarketServiceTest.java`

**Interfaces:**
- Consumes: `AccountDao.balanceDust`/`upsertBalance`, `MarketDao` (Task 1 methods + existing `insertTrade`, `insertPending`), `MarketMath.taxOnSale`, `CommodityMath`, `SystemAccounts.COMMUNITY_POT`, `Diamonds`, `TransactionRunner.inTransaction`.
- Produces:
  - `MarketException.Reason.NOT_A_COMMODITY` (new); `COMMODITY_NOT_YET` removed.
  - `record CommoditySaleResult(int sold, int unsold, Diamonds proceeds)` — `sold + unsold == requested qty`; `proceeds` is the seller's net after tax across all player fills plus the untaxed floor gross.
  - `CompletableFuture<Long> placeBid(UUID buyer, String materialKey, int qty, Diamonds priceEach, int xpFee, long nowEpochMs)` — escrows `priceEach × qty` diamonds and inserts an ACTIVE offer; returns the offer id. Refuses `INSUFFICIENT_FUNDS` if the buyer's balance cannot cover the escrow; `AMOUNT_TOO_LARGE` on overflow. `xpFee` is stored in `xp_paid` (the command layer deducts the XP after this future succeeds).
  - `CompletableFuture<CommoditySaleResult> marketSell(UUID seller, CommoditySpec spec, int qty, double salesTaxPercent, double taxBurnShare, long floorPriceDust, boolean buyLimitEnabled, int buyLimitCap, long buyLimitWindowMs, long nowEpochMs)` — the atomic multi-fill.
  - `CompletableFuture<Void> cancelBid(UUID buyer, long offerId, long nowEpochMs)` — refunds remaining escrow; `LISTING_UNAVAILABLE` if not ACTIVE, `NOT_YOUR_LISTING` if not the buyer's.
  - `CompletableFuture<List<CommodityOfferRow>> myBids(UUID buyer)`; `CompletableFuture<Optional<Long>> bestBidDust(String materialKey)`.

- [ ] **Step 1: Add `NOT_A_COMMODITY`, remove `COMMODITY_NOT_YET`**

In `MarketException.java`, add `NOT_A_COMMODITY` to the `Reason` enum with a javadoc line ("a bid or sell names a material that is not a fungible commodity"). Remove the `COMMODITY_NOT_YET` constant. Grep for every use — Part 1's SELL path threw it for a held commodity, and `MarketResolver.messageFor` has a case for it; both are updated in Tasks 5–6, and a Part 1 test asserts it. Leave those failing for now (they are fixed in their own tasks) **only if** implementing sequentially with a subagent that owns those files next; otherwise, if the compile breaks the build here, temporarily comment the single Part 1 assertion and the `messageFor` case and restore them in Tasks 5–6. Record whichever you did in the commit message.

- [ ] **Step 2: Create `CommoditySaleResult`**

```java
/**
 * The outcome of a market-sell. {@code sold + unsold} equals the quantity the seller offered;
 * {@code unsold} is what the command layer returns to the seller's inventory. {@code proceeds} is
 * the total diamonds the seller received: the tax-netted amount from every player fill plus the
 * untaxed floor gross.
 */
public record CommoditySaleResult(int sold, int unsold, Diamonds proceeds) {
}
```

- [ ] **Step 3: Write the failing service tests**

Add to `MarketServiceTest.java` (reuse the file's in-memory `Database`, `AccountDao`, `MarketDao`, `DatabaseExecutor` fixture; helpers to seed balances already exist from Part 1). Use a fixed clock long and small round numbers.

```java
@Test
void placeBidEscrowsDiamondsFromBuyer() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedBalance(buyer, 100_000L); // 100 diamonds
    long id = service.placeBid(buyer, "minecraft:iron_ingot", 10, Diamonds.ofDust(3000L), 2, 1L)
            .get();
    assertEquals(70_000L, accounts.balanceDust(buyer), "10 x 3000 dust escrowed out of balance");
    CommodityOfferRow bid = market.findActiveOffer(id).orElseThrow();
    assertEquals(30_000L, bid.escrowedDust());
}

@Test
void placeBidRefusesWhenBalanceTooLow() {
    UUID buyer = UUID.randomUUID();
    seedBalance(buyer, 10_000L);
    ExecutionException ex = assertThrows(ExecutionException.class,
            () -> service.placeBid(buyer, "k", 10, Diamonds.ofDust(3000L), 0, 1L).get());
    assertEquals(MarketException.Reason.INSUFFICIENT_FUNDS,
            ((MarketException) ex.getCause()).reason());
}

@Test
void marketSellFillsBestBidFirstThenFloorAndConservesSupply() throws Exception {
    UUID seller = UUID.randomUUID();
    UUID low = UUID.randomUUID();
    UUID high = UUID.randomUUID();
    seedBalance(low, 1_000_000L);
    seedBalance(high, 1_000_000L);
    seedBalance(SystemAccounts.COMMUNITY_POT, 1_000_000L);
    long supplyBefore = totalSupply(); // sum of all account balances + active-offer escrow (test helper)

    service.placeBid(low, "minecraft:iron_ingot", 10, Diamonds.ofDust(2000L), 0, 1L).get();
    service.placeBid(high, "minecraft:iron_ingot", 5, Diamonds.ofDust(5000L), 0, 2L).get();

    CommoditySpec spec = ironSpec(); // test helper: a fixed CommoditySpec for iron_ingot
    // Sell 20: 5 to `high` @5000, 10 to `low` @2000, 5 remainder to floor @1000.
    CommoditySaleResult result = service.marketSell(seller, spec, 20,
            /*tax*/7.0, /*burnShare*/0.5, /*floorDust*/1000L,
            /*buyLimitEnabled*/false, /*cap*/-1, /*window*/0L, 100L).get();

    assertEquals(20, result.sold());
    assertEquals(0, result.unsold());
    assertEquals(supplyBefore - burnedAcrossTrades(), totalSupply(),
            "supply falls by exactly the burned tax; floor sales burn nothing");
    // both bids fully filled
    assertTrue(market.findActiveOffer(highBidId()).isEmpty());
}

@Test
void marketSellStopsAtPotCapacityAndReturnsRemainder() throws Exception {
    UUID seller = UUID.randomUUID();
    seedBalance(SystemAccounts.COMMUNITY_POT, 3000L); // affords 3 at floor 1000
    CommoditySaleResult result = service.marketSell(seller, ironSpec(), 10,
            7.0, 0.5, 1000L, false, -1, 0L, 100L).get();
    assertEquals(3, result.sold(), "no bids; pot affords only 3");
    assertEquals(7, result.unsold());
}

@Test
void marketSellSkipsSellersOwnBid() throws Exception {
    UUID seller = UUID.randomUUID();
    seedBalance(seller, 1_000_000L);
    service.placeBid(seller, "minecraft:iron_ingot", 10, Diamonds.ofDust(9000L), 0, 1L).get();
    // no other bids, no floor
    CommoditySaleResult result = service.marketSell(seller, ironSpec(), 10,
            7.0, 0.5, /*no floor*/0L, false, -1, 0L, 100L).get();
    assertEquals(0, result.sold(), "a seller cannot fill their own bid");
    assertEquals(10, result.unsold());
}

@Test
void marketSellHonoursBuyLimitThenCancelsRemainderOfBid() throws Exception {
    UUID seller = UUID.randomUUID();
    UUID buyer = UUID.randomUUID();
    seedBalance(buyer, 1_000_000L);
    long bidId = service.placeBid(buyer, "minecraft:iron_ingot", 100, Diamonds.ofDust(1000L), 0, 1L).get();
    // cap 30, empty window: fill 30, then cancel the remaining 70 and refund its escrow.
    long buyerBalAfterBid = accounts.balanceDust(buyer); // 1_000_000 - 100_000 = 900_000
    CommoditySaleResult result = service.marketSell(seller, ironSpec(), 100,
            7.0, 0.5, 0L, /*buyLimitEnabled*/true, /*cap*/30, /*window*/3_600_000L, 1_000_000L).get();
    assertEquals(30, result.sold(), "capped at 30");
    assertEquals(70, result.unsold(), "the other 70 could not be bought by the only bidder");
    assertTrue(market.findActiveOffer(bidId).isEmpty(), "bid remainder cancelled");
    // refund: escrow held 100_000; 30 spent (30_000); 70_000 returned to buyer.
    assertEquals(buyerBalAfterBid + 70_000L, accounts.balanceDust(buyer));
}

@Test
void cancelBidRefundsRemainingEscrowNotXp() throws Exception {
    UUID buyer = UUID.randomUUID();
    seedBalance(buyer, 100_000L);
    long id = service.placeBid(buyer, "k", 10, Diamonds.ofDust(3000L), 5, 1L).get();
    service.cancelBid(buyer, id, 2L).get();
    assertEquals(100_000L, accounts.balanceDust(buyer), "full escrow refunded (nothing filled)");
    assertTrue(market.findActiveOffer(id).isEmpty());
}

@Test
void cancelBidRefusesForeignOffer() throws Exception {
    UUID buyer = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    seedBalance(buyer, 100_000L);
    long id = service.placeBid(buyer, "k", 1, Diamonds.ofDust(1000L), 0, 1L).get();
    ExecutionException ex = assertThrows(ExecutionException.class,
            () -> service.cancelBid(other, id, 2L).get());
    assertEquals(MarketException.Reason.NOT_YOUR_LISTING, ((MarketException) ex.getCause()).reason());
}
```

Add the small test helpers (`totalSupply`, `burnedAcrossTrades`, `ironSpec`, `highBidId`) near the top of the test class; `ironSpec()` returns `new CommoditySpec("minecraft:iron_ingot", "ironkey", new byte[]{1}, "Iron Ingot")` and `totalSupply()` sums `SELECT SUM(diamonds_dust) FROM accounts` plus `SELECT COALESCE(SUM(escrowed_dust),0) FROM commodity_offers WHERE status='ACTIVE'`.

- [ ] **Step 4: Run them, verify they fail**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MarketServiceTest test`
Expected: FAIL — the new methods do not exist.

- [ ] **Step 5: Implement `placeBid`**

Mirror Part 1's `list`/`buy` future+`inTransaction` shape. The XP fee is *not* charged here (the command layer does that after success); `placeBid` only escrows diamonds.

```java
public CompletableFuture<Long> placeBid(UUID buyer, String materialKey, int qty,
        Diamonds priceEach, int xpFee, long nowEpochMs) {
    return executor.submit(() -> transactions.inTransaction(() -> {
        Diamonds escrow;
        try {
            escrow = priceEach.times(qty); // Diamonds overflow-refusing multiply
        } catch (ArithmeticException overflow) {
            throw new MarketException(MarketException.Reason.AMOUNT_TOO_LARGE,
                    "bid value overflows", overflow);
        }
        Diamonds balance = Diamonds.ofDust(accounts.balanceDust(buyer));
        Diamonds after = balance.minus(escrow);
        if (after.isNegative()) {
            throw new MarketException(MarketException.Reason.INSUFFICIENT_FUNDS,
                    buyer + " holds " + balance.format() + " but the bid escrows " + escrow.format());
        }
        accounts.upsertBalance(buyer, after.dust());
        return market.insertOffer(new CommodityOfferRow(0L, buyer, materialKey, qty,
                priceEach.dust(), escrow.dust(), xpFee, nowEpochMs, OfferStatus.ACTIVE));
    }));
}
```

If `Diamonds` has no `times(int)`, add it as an overflow-refusing multiply next to `plus`/`minus` (using `Math.multiplyExact` on dust) in the same commit, with a one-line unit test in `DiamondsTest`. Check first — Part 1 may already have it.

- [ ] **Step 6: Implement `marketSell` (the atomic multi-fill)**

The whole sale is one `inTransaction`. Walk bids best-first, apply the buy-limit clamp and fill-to-cap-then-cancel rule, deliver to each buyer's `pending_items`, then dump the remainder to the floor.

```java
public CompletableFuture<CommoditySaleResult> marketSell(UUID seller, CommoditySpec spec, int qty,
        double salesTaxPercent, double taxBurnShare, long floorPriceDust,
        boolean buyLimitEnabled, int buyLimitCap, long buyLimitWindowMs, long nowEpochMs) {
    return executor.submit(() -> transactions.inTransaction(() -> {
        int remaining = qty;
        long proceedsDust = 0L;

        for (CommodityOfferRow bid : market.bestActiveBids(spec.materialKey(), 256)) {
            if (remaining == 0) {
                break;
            }
            if (bid.buyer().equals(seller)) {
                continue; // fact 9: never fill your own bid
            }
            int allowance = Integer.MAX_VALUE;
            if (buyLimitEnabled) {
                int used = market.buyLimitUsage(bid.buyer(), spec.materialKey(),
                        nowEpochMs - buyLimitWindowMs);
                allowance = CommodityMath.remainingBuyAllowance(buyLimitCap, used);
            }
            int take = Math.min(Math.min(remaining, bid.qtyRemaining()), allowance);
            if (take > 0) {
                Diamonds gross = Diamonds.ofDust(bid.priceEachDust()).times(take);
                MarketMath.TaxSplit split = MarketMath.taxOnSale(gross, salesTaxPercent, taxBurnShare);

                accounts.upsertBalance(seller,
                        Diamonds.ofDust(accounts.balanceDust(seller)).plus(split.net()).dust());
                accounts.upsertBalance(SystemAccounts.COMMUNITY_POT,
                        Diamonds.ofDust(accounts.balanceDust(SystemAccounts.COMMUNITY_POT))
                                .plus(split.toPot()).dust());
                // split.burned() is credited to nobody -- the sink.

                if (market.spendFromOffer(bid.id(), take, gross.dust()) != 1) {
                    throw new MarketException(MarketException.Reason.NOTHING_WRITTEN,
                            "offer " + bid.id() + " left ACTIVE mid-fill; rolling back");
                }
                market.insertPending(new PendingItemRow(0L, bid.buyer(), spec.oneItemBytes(), take,
                        take + "x " + spec.displayName(), "commodity purchase", nowEpochMs, null));
                market.insertTrade(new TradeRow(0L, nowEpochMs, bid.buyer(), seller,
                        ItemClass.COMMODITY, spec.itemKey(), spec.materialKey(), take,
                        gross.dust(), split.tax().dust(), split.burned().dust(),
                        split.toPot().dust(), split.net().dust(), null));

                proceedsDust += split.net().dust();
                remaining -= take;
            }
            // fill-to-cap-then-cancel: if the buy limit stopped this bidder short of their bid,
            // cancel the bid's remainder and refund its escrow (fact 7).
            if (buyLimitEnabled && take < bid.qtyRemaining()) {
                CommodityOfferRow live = market.findActiveOffer(bid.id()).orElse(null);
                if (live != null && CommodityMath.remainingBuyAllowance(buyLimitCap,
                        market.buyLimitUsage(bid.buyer(), spec.materialKey(),
                                nowEpochMs - buyLimitWindowMs)) == 0) {
                    accounts.upsertBalance(bid.buyer(),
                            Diamonds.ofDust(accounts.balanceDust(bid.buyer()))
                                    .plus(Diamonds.ofDust(live.escrowedDust())).dust());
                    if (market.cancelOffer(bid.id()) != 1) {
                        throw new MarketException(MarketException.Reason.NOTHING_WRITTEN,
                                "offer " + bid.id() + " could not be cancelled at cap");
                    }
                }
            }
        }

        // Floor: sell the remainder to the pot at the floor price, bounded by the pot balance.
        if (remaining > 0 && floorPriceDust > 0) {
            long potDust = accounts.balanceDust(SystemAccounts.COMMUNITY_POT);
            int affordable = CommodityMath.floorFillableByPot(remaining, potDust, floorPriceDust);
            if (affordable > 0) {
                Diamonds gross = Diamonds.ofDust(floorPriceDust).times(affordable);
                accounts.upsertBalance(SystemAccounts.COMMUNITY_POT,
                        Diamonds.ofDust(potDust).minus(gross).dust());
                accounts.upsertBalance(seller,
                        Diamonds.ofDust(accounts.balanceDust(seller)).plus(gross).dust());
                market.insertPending(new PendingItemRow(0L, SystemAccounts.COMMUNITY_POT,
                        spec.oneItemBytes(), affordable, affordable + "x " + spec.displayName(),
                        "floor buyback", nowEpochMs, null));
                market.insertTrade(new TradeRow(0L, nowEpochMs, SystemAccounts.COMMUNITY_POT, seller,
                        ItemClass.COMMODITY, spec.itemKey(), spec.materialKey(), affordable,
                        gross.dust(), 0L, 0L, 0L, gross.dust(), null)); // untaxed
                proceedsDust += gross.dust();
                remaining -= affordable;
            }
        }

        return new CommoditySaleResult(qty - remaining, remaining, Diamonds.ofDust(proceedsDust));
    }));
}
```

Two things to get right: the floor `insertPending` credits the item to `COMMUNITY_POT` so the server's bought stock is not silently destroyed (it accumulates as claimable pot inventory — an admin feature later; the item conservation mirrors the money conservation). And every diamond move goes through `Diamonds`, never a raw `long +`.

- [ ] **Step 7: Implement `cancelBid`, `myBids`, `bestBidDust`**

```java
public CompletableFuture<Void> cancelBid(UUID buyer, long offerId, long nowEpochMs) {
    return executor.submit(() -> transactions.inTransaction(() -> {
        CommodityOfferRow offer = market.findActiveOffer(offerId)
                .orElseThrow(() -> new MarketException(MarketException.Reason.LISTING_UNAVAILABLE,
                        "offer " + offerId + " is not active"));
        if (!offer.buyer().equals(buyer)) {
            throw new MarketException(MarketException.Reason.NOT_YOUR_LISTING,
                    "offer " + offerId + " belongs to someone else");
        }
        accounts.upsertBalance(buyer, Diamonds.ofDust(accounts.balanceDust(buyer))
                .plus(Diamonds.ofDust(offer.escrowedDust())).dust());
        if (market.cancelOffer(offerId) != 1) {
            throw new MarketException(MarketException.Reason.NOTHING_WRITTEN,
                    "offer " + offerId + " left ACTIVE mid-cancel; rolling back");
        }
        return null;
    }));
}

public CompletableFuture<List<CommodityOfferRow>> myBids(UUID buyer) {
    return executor.submit(() -> market.offersByBuyer(buyer, OfferStatus.ACTIVE));
}

public CompletableFuture<Optional<Long>> bestBidDust(String materialKey) {
    return executor.submit(() -> market.bestBidPriceDust(materialKey));
}
```

- [ ] **Step 8: Run the service tests, verify they pass**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MarketServiceTest test`
Expected: PASS.

- [ ] **Step 9: Mutation checks (the money-critical ones)**

Do each, rerun `MarketServiceTest`, confirm the named test fails, then revert:
- In `marketSell`, change the pot credit `.plus(split.toPot())` to `.plus(split.net())` → `marketSellFillsBestBidFirstThenFloorAndConservesSupply` must fail (supply no longer falls by exactly the burn).
- Delete the `if (bid.buyer().equals(seller)) continue;` line → `marketSellSkipsSellersOwnBid` must fail.
- Change `CommodityMath.floorFillableByPot(remaining, potDust, floorPriceDust)` to pass `Integer.MAX_VALUE` as the first arg → `marketSellStopsAtPotCapacityAndReturnsRemainder` must fail (would overspend the pot / oversell).
- In `cancelBid`, delete the escrow-refund `upsertBalance` line → `cancelBidRefundsRemainingEscrowNotXp` must fail.

- [ ] **Step 10: Full build and commit**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/org/xpfarm/farmersmarket/market/MarketException.java \
  src/main/java/org/xpfarm/farmersmarket/market/CommoditySaleResult.java \
  src/main/java/org/xpfarm/farmersmarket/market/MarketService.java \
  src/test/java/org/xpfarm/farmersmarket/market/MarketServiceTest.java
git commit -m "feat(m2p2): MarketService placeBid, marketSell multi-fill, cancelBid"
```

---

## Task 5: `MarketResolver` — the new subcommands and dual-parse

**Files:**
- Modify: `src/main/java/org/xpfarm/farmersmarket/command/MarketResolver.java`
- Test: `src/test/java/org/xpfarm/farmersmarket/command/MarketResolverTest.java`

**Interfaces:**
- Produces:
  - New `Sub` constants: `BID("bid", USE_PERMISSION, true)`, `PRICE("price", USE_PERMISSION, false)`, `CANCELBID("cancelbid", USE_PERMISSION, true)`.
  - New `Outcome` constants: `BID`, `PRICE`, `CANCELBID`, and error outcomes `MISSING_MATERIAL`, `MISSING_PRICE`, `BAD_PRICE` (reuse `MISSING_AMOUNT`/`BAD_AMOUNT` for quantity, `MISSING_ID`/`BAD_ID` for cancelbid).
  - `Resolution` gains `String material()` and `int quantity()`; a delegating constructor keeps every Part 1 call site compiling.
  - `messageFor(MarketException.Reason)` gains a `NOT_A_COMMODITY` case and drops the `COMMODITY_NOT_YET` case (stays a defaultless switch).
  - `SELL` resolution now populates both `priceDust` (fractional-diamond parse, as Part 1) and `quantity` (non-negative int parse, `0` if the argument is absent or not an integer) so `MarketCommand` can pick by held-item class.

- [ ] **Step 1: Write the failing resolver tests**

Add to `MarketResolverTest.java`:

```java
@Test
void resolvesBidWithMaterialQuantityAndPrice() {
    MarketResolver.Resolution r = resolve("bid", "iron_ingot", "64", "3");
    assertEquals(MarketResolver.Outcome.BID, r.outcome());
    assertEquals("iron_ingot", r.material());
    assertEquals(64, r.quantity());
    assertEquals(3000L, r.priceDust(), "3 diamonds -> 3000 dust");
}

@Test
void bidMissingPieces() {
    assertEquals(MarketResolver.Outcome.MISSING_MATERIAL, resolve("bid").outcome());
    assertEquals(MarketResolver.Outcome.MISSING_AMOUNT, resolve("bid", "iron_ingot").outcome());
    assertEquals(MarketResolver.Outcome.MISSING_PRICE, resolve("bid", "iron_ingot", "64").outcome());
    assertEquals(MarketResolver.Outcome.BAD_AMOUNT, resolve("bid", "iron_ingot", "-1", "3").outcome());
    assertEquals(MarketResolver.Outcome.BAD_PRICE, resolve("bid", "iron_ingot", "64", "0").outcome());
}

@Test
void resolvesPriceView() {
    MarketResolver.Resolution r = resolve("price", "iron_ingot");
    assertEquals(MarketResolver.Outcome.PRICE, r.outcome());
    assertEquals("iron_ingot", r.material());
}

@Test
void resolvesCancelBidById() {
    MarketResolver.Resolution r = resolve("cancelbid", "7");
    assertEquals(MarketResolver.Outcome.CANCELBID, r.outcome());
    assertEquals(7L, r.listingId());
}

@Test
void sellCarriesBothPriceAndQuantity() {
    MarketResolver.Resolution r = resolve("sell", "5");
    assertEquals(MarketResolver.Outcome.SELL, r.outcome());
    assertEquals(5000L, r.priceDust(), "unique interpretation: 5 diamonds");
    assertEquals(5, r.quantity(), "commodity interpretation: 5 units");
}

@Test
void sellFractionalHasZeroQuantity() {
    MarketResolver.Resolution r = resolve("sell", "0.5");
    assertEquals(500L, r.priceDust(), "0.5 diamonds -> 500 dust (unique interpretation)");
    assertEquals(0, r.quantity(), "0.5 is not a whole quantity");
}
```

(Keep whatever `resolve(...)` helper and console/permission setup `MarketResolverTest` already uses; match its style. If Part 1 has a `commodityRejectedWithComodityNotYet`-style test, delete it — commodities are no longer rejected at resolve time.)

- [ ] **Step 2: Run them, verify they fail**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MarketResolverTest test`
Expected: FAIL — new outcomes/fields absent.

- [ ] **Step 3: Extend the enums and `Resolution`**

Add the three `Sub` constants and the new `Outcome` constants (add the three error outcomes to `isError()`'s disjunction). Add `String material` and `int quantity` to the `Resolution` record; add a delegating constructor that defaults them (`null`, `0`) so Part 1 call sites are unchanged.

- [ ] **Step 4: Implement the parsing**

In the resolver's dispatch, add branches:
- `BID`: require `args[1]` (material, else `MISSING_MATERIAL`), `args[2]` (quantity: parse a positive int, `MISSING_AMOUNT`/`BAD_AMOUNT`), `args[3]` (price-each: reuse Part 1's fractional-diamond→dust parse, `MISSING_PRICE`/`BAD_PRICE`, must be `> 0`). Build a `Resolution` with `material`, `quantity`, `priceDust`.
- `PRICE`: require `args[1]` (material, else `MISSING_MATERIAL`); build a `Resolution` with `material`.
- `CANCELBID`: reuse Part 1's id parse (`MISSING_ID`/`BAD_ID`) into `listingId`.
- `SELL`: keep the existing price parse into `priceDust`; additionally parse `args[1]` as a non-negative integer into `quantity` (`0` when absent or not an integer — never an error here, because which field is authoritative is decided by the command from the held item's class).

Update `messageFor`: add `case NOT_A_COMMODITY -> "That item is not a bulk commodity — list it on the board with /market sell <price> instead.";` and delete the `COMMODITY_NOT_YET` case.

- [ ] **Step 5: Run them, verify they pass**

Run: `mvn --batch-mode --no-transfer-progress -Dtest=MarketResolverTest test`
Expected: PASS.

- [ ] **Step 6: Mutation check the console/needsPlayer gate for a new sub**

Temporarily flip `BID`'s `needsPlayer` to `false` and confirm a resolver test that runs `bid` from a console sender now resolves past the `CONSOLE_NEEDS_PLAYER` guard (add a one-line assertion if none covers it), then revert. This proves the gate is real for the new subcommand.

- [ ] **Step 7: Full build and commit**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/org/xpfarm/farmersmarket/command/MarketResolver.java \
  src/test/java/org/xpfarm/farmersmarket/command/MarketResolverTest.java
git commit -m "feat(m2p2): resolver bid/price/cancelbid + sell dual-parse"
```

---

## Task 6: `MarketCommand` wiring, `plugin.yml`, descriptor test

**Files:**
- Modify: `src/main/java/org/xpfarm/farmersmarket/command/MarketCommand.java`
- Modify: `src/main/java/org/xpfarm/farmersmarket/FarmersMarketPlugin.java` (only if a config value must be threaded through; no new services)
- Modify: `src/main/resources/plugin.yml`
- Test: `src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java`

**Interfaces:**
- Consumes: everything from Tasks 4–5 plus `BukkitItemCodec.commoditySpecFor`/`commodityOf`, `FmConfig` (`buybackEnabled`, `buybackFloors`, `farmOutputAudit` already applied at load, `buyLimitEnabled`, `buyLimitWindowHours`, `buyLimits`, `salesTaxPercent`, `taxBurnShare`, `listingFeePercent`, `xpPerDiamond`).
- Produces: no new public API — this is the Bukkit dispatch layer. `MarketCommand` gains private `bid`, `price`, `cancelbid`, and a commodity branch inside `sell`.

- [ ] **Step 1: Update `plugin.yml` usage and the descriptor test (red first)**

Add `bid`, `price`, `cancelbid` to the `market` command's `usage:` string. In `PluginDescriptorTest.java`, update the assertion that the usage string names exactly the implemented subcommands to include the three new ones. Run:

Run: `mvn --batch-mode --no-transfer-progress -Dtest=PluginDescriptorTest test`
Expected: FAIL first if you update the test before `plugin.yml`, then PASS after — do the test edit first to keep it honest. No new permissions are added (all three are `farmersmarket.use`); keep the "unbuilt milestones' permissions are absent" assertion satisfied.

- [ ] **Step 2: Dispatch the new subcommands**

In `MarketCommand`'s `case` dispatch (mirror Part 1's `onMainThread(market.…)` pattern and its uncertain-outcome handling), add:

- `case BID -> bid((Player) sender, resolved.material(), resolved.quantity(), Diamonds.ofDust(resolved.priceDust()));`
- `case PRICE -> price(sender, resolved.material());`
- `case CANCELBID -> cancelbid((Player) sender, resolved.listingId());`

And in the existing `case SELL`, branch on the held item's class **before** using the resolver's numeric fields.

- [ ] **Step 3: Implement `bid`**

```java
private void bid(Player player, String materialName, int qty, Diamonds priceEach) {
    Optional<CommoditySpec> maybe = codec.commoditySpecFor(materialName);
    if (maybe.isEmpty()) {
        player.sendMessage(text(MarketResolver.messageFor(MarketException.Reason.NOT_A_COMMODITY))
                .color(NamedTextColor.RED));
        return;
    }
    CommoditySpec spec = maybe.get();
    Diamonds bidValue;
    try {
        bidValue = priceEach.times(qty);
    } catch (ArithmeticException tooBig) {
        player.sendMessage(text("That bid is too large.").color(NamedTextColor.RED));
        return;
    }
    int fee = MarketMath.listingFeeXp(bidValue, cfg.listingFeePercent(), cfg.xpPerDiamond());
    if (experiencePoints(player) < fee) {
        player.sendMessage(text("You need " + fee + " XP to place that bid.").color(NamedTextColor.RED));
        return;
    }
    // Escrow diamonds first; charge XP only after the escrow write succeeds (settled fact 4).
    onMainThread(market.placeBid(player.getUniqueId(), spec.materialKey(), qty, priceEach, fee,
            clock.millis()), (offerId, error) -> {
        if (error != null) {
            reportFailure(player, error);
            return;
        }
        player.giveExp(-fee);
        player.sendMessage(text("Bid placed: " + qty + "x " + spec.displayName() + " at "
                + priceEach.format() + " each. Escrowed " + bidValue.format()
                + ", fee " + fee + " XP.").color(NamedTextColor.GREEN));
    });
}
```

Follow the file's actual continuation/threading idiom (`onMainThread`, the uncertain-outcome reporter, the `experiencePoints` helper) — the snippet shows intent; match Part 1's exact helper names.

- [ ] **Step 4: Implement the `sell` commodity branch**

```java
// inside sell(...), after obtaining the held ItemStack `held`:
// Reuse the SAME classification call Part 1's SELL path already makes to detect a commodity
// (Part 1 rejected commodities with COMMODITY_NOT_YET, so a held-item classifier already exists in
// this method — read it and reuse it; do not invent a second one). Call its result `heldClass`.
if (heldClass == ItemClass.COMMODITY) {
    int qty = resolved.quantity() > 0 ? Math.min(resolved.quantity(), held.getAmount()) : held.getAmount();
    if (resolved.quantity() < 0 || (rawArgProvided && resolved.quantity() == 0)) {
        player.sendMessage(text("Commodity quantities are whole numbers.").color(NamedTextColor.RED));
        return;
    }
    CommoditySpec spec = codec.commodityOf(held);
    long floorDust = floorDustFor(held.getType()); // config lookup, 0 when no floor
    held.setAmount(held.getAmount() - qty); // remove the sold portion up front (escrow)
    onMainThread(market.marketSell(player.getUniqueId(), spec, qty, cfg.salesTaxPercent(),
            cfg.taxBurnShare(), floorDust, cfg.buyLimitEnabled(),
            cfg.buyLimits().getOrDefault(held.getType().name(), -1),
            cfg.buyLimitWindowHours() * 3_600_000L, clock.millis()), (result, error) -> {
        if (error != null) {
            // give the removed items back on failure
            giveOrDrop(player, spec, qty);
            reportFailure(player, error);
            return;
        }
        if (result.unsold() > 0) {
            giveOrDrop(player, spec, result.unsold());
        }
        player.sendMessage(text("Sold " + result.sold() + "x " + spec.displayName()
                + " for " + result.proceeds().format()
                + (result.unsold() > 0 ? " (" + result.unsold() + " returned — no buyers)" : ""))
                .color(NamedTextColor.GREEN));
    });
    return;
}
// else: existing Part 1 unique-listing path using resolved.priceDust()
```

Add the private helper `floorDustFor(Material m)`: returns `0L` when `!cfg.buybackEnabled()` or the material has no `cfg.buybackFloors()` entry; otherwise converts the configured floor (diamonds, `double`) to dust **once** with the same diamonds→dust conversion the resolver uses for prices, and returns it as a `long`. This is the one permitted `double`→dust conversion (Global Constraints). Add `giveOrDrop(Player, CommoditySpec, int amount)` that deserializes `spec.oneItemBytes()`, sets the amount (respecting max stack size across multiple stacks), and adds to the inventory or drops the overflow at the player's location — reuse Part 1's `giveOrDrop` if it already exists for the unique path.

- [ ] **Step 5: Implement `price` and `cancelbid`**

```java
private void price(CommandSender sender, String materialName) {
    Optional<CommoditySpec> maybe = codec.commoditySpecFor(materialName);
    if (maybe.isEmpty()) {
        sender.sendMessage(text(MarketResolver.messageFor(MarketException.Reason.NOT_A_COMMODITY))
                .color(NamedTextColor.RED));
        return;
    }
    CommoditySpec spec = maybe.get();
    long floorDust = floorDustFor(materialOf(materialName)); // 0 when no floor
    onMainThread(market.bestBidDust(spec.materialKey()), (bestBid, error) -> {
        if (error != null) {
            reportFailure(sender, error);
            return;
        }
        String bidLine = bestBid.isPresent()
                ? "Best bid: " + Diamonds.ofDust(bestBid.get()).format() + " each"
                : "No bids.";
        String floorLine = floorDust > 0
                ? "  Floor: " + Diamonds.ofDust(floorDust).format() + " each" : "  No floor.";
        sender.sendMessage(text(spec.displayName() + " — " + bidLine + floorLine)
                .color(NamedTextColor.YELLOW));
    });
}

private void cancelbid(Player player, long offerId) {
    onMainThread(market.cancelBid(player.getUniqueId(), offerId, clock.millis()), (ignored, error) -> {
        if (error != null) {
            reportFailure(player, error);
            return;
        }
        player.sendMessage(text("Bid " + offerId + " cancelled; remaining escrow refunded.")
                .color(NamedTextColor.GREEN));
    });
}
```

- [ ] **Step 6: Extend `mine` to list active bids**

In the existing `mine` handler, after listing the player's unique listings, also fetch `market.myBids(player.getUniqueId())` and print each active bid as `#<id> <qtyRemaining>x <materialKey> @ <price> each` (plain text). Reuse the existing two-stage `onMainThread` continuation shape; if `mine` currently makes one service call, chain the second.

- [ ] **Step 7: Full build**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, all tests green (target ≥ the Part 1 count plus the new unit tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/xpfarm/farmersmarket/command/MarketCommand.java \
  src/main/java/org/xpfarm/farmersmarket/FarmersMarketPlugin.java \
  src/main/resources/plugin.yml \
  src/test/java/org/xpfarm/farmersmarket/PluginDescriptorTest.java
git commit -m "feat(m2p2): command wiring for bid/sell/price/cancelbid + plugin.yml"
```

---

## What gate 7a must exercise (record for the release, do not skip)

The commodity fill loop, escrow, and codec material-resolution are runtime surfaces no unit test reaches (the codec needs a live server; the multi-fill needs a real inventory and an online-vs-offline buyer). When this plan is implemented, gate 7a for `0.3.0` must, over RCON where possible and at the gate-12 play-test where a client is required, cover:
- Migration 3 brings a **v2 database to v3** on the real server (schema version line reads 3), preserving existing accounts and listings.
- `/market bid iron_ingot 64 3` from a player escrows diamonds and charges the XP fee; the bid shows in `/market mine`.
- `/market price iron_ingot` shows the best bid and the floor.
- A second player `/market sell` of iron fills the bid (byte-identical iron delivered to the buyer's `/market claim`), taxes the seller 7%, and dumps the remainder to the floor (pot balance drops; `/market pot` reflects it).
- `/market bid` / `sell` / `cancelbid` are player-only and console-refuse cleanly; `/market price` runs from the console.
- A commodity sell with an empty pot and no bids returns the whole stack unsold.
- Buy-limit fill-to-cap-then-cancel: with a low cap configured, a large sell fills a bidder to the cap and cancels+refunds the remainder.

---

## Self-review

- **Spec coverage.** Buy-side-only book → Tasks 1/4. Priceless market-sell with price-time priority + floor → Task 4 `marketSell`. Pot-funded floor, untaxed → Task 4 (settled fact 3) + `CommodityMath.floorFillableByPot` (Task 2). XP fee on resting bids → Task 6 `bid` (settled fact 4). 7% tax on player fills → Task 4 via `MarketMath.taxOnSale`. Buy limits, fill-to-cap-then-cancel → Task 4 + `CommodityMath.remainingBuyAllowance` + `MarketDao.buyLimitUsage`. Escrow in the offer row → Task 1 schema + Task 4 `placeBid`/`cancelBid`. Anonymity (no per-bid browse) → only `bestBidDust`/`price` aggregate views exposed; no browse-offers method added. Offline delivery → `pending_items` in `marketSell`. Commodity identity by material → Task 3 codec. Command surface → Tasks 5–6. Version bump → Task 1.
- **Placeholder scan.** No "TBD"/"handle errors"/"similar to Task N" — every step carries real code or a named, exact edit. The one deliberate soft spot is `BukkitItemCodec` internals (`hasMeaningfulComponents`, `contentHash`, `prettyName`) which reuse **existing** Part 1 helpers rather than re-specifying them; the implementer reads the Part 1 class (Global Constraints require it) and calls them.
- **Type consistency.** `CommodityOfferRow` field names/types match between Task 1 (definition), the DAO (Task 1), and `MarketService` (Task 4). `CommoditySpec(materialKey, itemKey, oneItemBytes, displayName)` is consistent across Tasks 3, 4, 6. `CommoditySaleResult(sold, unsold, proceeds)` consistent between Task 4 and Task 6. `marketSell`'s parameter list in the Interfaces block matches its implementation and its Task 6 call site. `spendFromOffer(id, qtyFilled, escrowSpentDust)` and `buyLimitUsage(buyer, materialKey, sinceEpochMs)` signatures match between Task 1 and Task 4.
- **One risk flagged for the implementer.** Task 4 Step 1 removes `COMMODITY_NOT_YET`, which Part 1 code/tests reference. Whoever executes Task 4 must land Tasks 5–6's edits (or the temporary comment-out noted there) in the same review cycle so the build is never left red across a task boundary. The SDD reviewer should treat a red build at the Task 4 boundary as expected only if Task 5/6 follow immediately; otherwise it is a real failure.
