# Farmers Market

A player-run market economy for Paper servers. Sell anything you own, buy what anyone
else is selling, and let a vendor shaped like a wandering trader mind the shop while
you are offline.

Running on **`play.xpfarm.org`** (Java and Bedrock, via Geyser + Floodgate).

> **Status: planned, not yet implemented.** The repository currently holds the design,
> the lifecycle checklist, build metadata, and CI. Plugin code lands at gate 4.

## The idea

**Diamonds are the money. XP pays the fees.**

Diamonds are what you trade with — deposit them, spend them, withdraw them. XP is the
transaction currency: it covers listing fees and stall rent, and it can never be
transferred between players. Because XP cannot be traded it cannot be cornered, farmed
for resale, or hoarded as a speculative asset. It can only be burned on friction.

Each currency has its own sink, which is the point. A currency backed by an unbounded
faucet with nothing consuming it is how server economies die.

## Two markets, because bulk goods and unique gear want opposite things

| | Commodity exchange | Unique board |
|---|---|---|
| What | Stackable plain items — cobblestone, iron, wheat, diamonds | Enchanted, renamed, damaged, custom, containers |
| How | Anonymous buy/sell offer matching | Browsable listings, instant buyout |
| Feels like | RuneScape's Grand Exchange | An in-town market stall |
| Why | Liquidity. No order book to snipe, no undercutting war | Character. You browse, you find something, you buy it |

Splitting them also sidesteps the nastiest bug class in shop plugins: item matching that
silently fails on enchanted gear and produces accidental infinite-stock shops.

## Vendors

Plant a vendor at your base or rent a stall in the market town. It is a real wandering
trader — stationary, invulnerable, unpushable, and it survives restarts. Right-click
opens the market, never the vanilla trade screen.

Vendors hold your stock in escrow, so they keep selling while you are offline.

## Both editions, no commands required

Java players get an inventory GUI. Bedrock players get native Bedrock forms. Same
navigation, same market, rendered natively for each platform — and every task is
completable without typing a command, which matters when some of your players are young.

Price charts render to map items, which is the one technique that produces genuine
pixel graphics identically on Java and Bedrock.

## Commands

The UI covers everything; the command tree exists for accessibility and admin work.

| Command | Permission | What it does |
|---|---|---|
| `/market` | `farmersmarket.use` | Opens the market |
| `/market browse [query]` | `farmersmarket.use` | Searches every listing |
| `/market sell [price] [qty]` | `farmersmarket.use` | Lists the held item |
| `/market buy <item> <qty> <max>` | `farmersmarket.use` | Places a commodity buy order |
| `/market orders` | `farmersmarket.use` | Your listings and orders |
| `/market balance` | `farmersmarket.use` | Diamonds, XP, and escrow |
| `/market deposit [qty]` | `farmersmarket.use` | Diamonds into the ledger |
| `/market withdraw <qty>` | `farmersmarket.use` | Diamonds back to inventory |
| `/market chart <item> [7d\|30d]` | `farmersmarket.chart` | A rendered price chart |
| `/market index` | `farmersmarket.use` | The Server Market Index |
| `/market vendor place\|list\|remove` | `farmersmarket.vendor.place` | Manage your vendors |
| `/market stall rent <id> <bid>` | `farmersmarket.stall.rent` | Sealed weekly bid on a stall |
| `/market admin …` | `farmersmarket.admin` | Reload, floors, audit, freeze, pot |

Aliased to `/mkt` and `/fm`.

## Economy health

- **1% listing fee** in XP, charged at listing, non-refundable.
- **7% sales tax** in diamonds — half destroyed, half into a community pot for server events.
- **Server buy-back floors** guarantee you can always convert loot to currency, even when
  nobody else is online. Floors are mechanically audited against automated-farm output cost
  and refuse to load if they would create a money faucet.
- **Rolling per-item buy limits** stop any one player cornering a market.
- **Every trade is logged immutably**, from the first release. You cannot retrofit an audit
  trail onto an economy that has already been exploited.

There are no tradeable securities, shares, dividends, or player-facing banks, and there
never will be. The market analytics are read-only by design.

## Building

Requires Java 25 and Maven.

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

## Documentation

- [`docs/PLUGIN_CHECKLIST.md`](docs/PLUGIN_CHECKLIST.md) — lifecycle checklist, scope, acceptance checks
- [`docs/superpowers/specs/2026-07-21-farmers-market-design.md`](docs/superpowers/specs/2026-07-21-farmers-market-design.md) — design and rationale

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).
