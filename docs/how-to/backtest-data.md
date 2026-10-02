# Backtest data: getting it and storing it

Everything a backtest reads comes from one local store on disk. This guide covers every way
to fill that store — let the backtest fetch ticks for you, pull broker bars with `qkt fetch`,
drop in your own CSVs, or convert to the fast binary format — and exactly where each lands.

If you only remember one thing: **the store lives at `~/.qkt/data`, and every command reads
and writes the same place** (override with `--data-root <dir>` or the `QKT_DATA_HOME` env var;
the flag wins). Point `fetch`, `convert`, and `backtest` at the same root and they find each
other's data.

## Two kinds of data, two layouts

A backtest is tick-driven, but it can run off either ticks or bars:

| | Ticks | Bars (OHLC candles) |
|---|---|---|
| What | Individual price prints | One candle per timeframe (1m, 5m, 1h…) |
| Where it comes from | Dukascopy (free public feed), auto-fetched | Your broker's history API, via `qkt fetch` |
| On disk | `~/.qkt/data/symbols/<SYMBOL>/<day>.{bin,csv.gz,csv}` | `~/.qkt/data/bars/<BROKER>/<SYMBOL>/<TF>/<day>.csv` |
| Keyed by | **bare** symbol (`XAUUSD`) — no broker prefix | **broker + symbol** (`EXNESS/XAUUSD`) |
| Use when | Research on realistic ticks (the common case) | Broker-exact history, or a bars-only venue (crypto) |

The engine runs the same pipeline either way: when a backtest reads a bar, it replays it as four
synthetic ticks (open → low → high → close) so indicators and candle-close rules see the identical
OHLC. See [Backtest model](../concepts/backtest-model.md) for the intrabar-fidelity caveat.

### How a strategy symbol picks a source

The `NAME:` prefix on a strategy's symbol decides where data is read:

- `BACKTEST:XAUUSD` — "no real broker; use the local tick store / Dukascopy." Ticks are read from
  `symbols/XAUUSD`. This is the usual research setup.
- `EXNESS:XAUUSD` — a real broker. Bars are read from `bars/EXNESS/XAUUSD/<tf>` when present;
  if any day is missing there, it falls back to aggregating the bare-keyed ticks in `symbols/XAUUSD`.

Either way the tick store is keyed by the **bare** symbol, so `BACKTEST:XAUUSD` and `EXNESS:XAUUSD`
share the same `symbols/XAUUSD/` tick files.

## Scenario 1 — Just run a backtest (auto-fetch ticks)

The default. No separate download step, no broker or gateway running:

```bash
qkt backtest strategies/my_strategy.qkt --from 2024-01-01 --to 2024-02-01 --json
```

What happens:

1. The symbols are read from the strategy's `SYMBOLS` block.
2. Any days not already cached are downloaded from Dukascopy into
   `~/.qkt/data/symbols/<SYMBOL>/<day>.csv.gz`. Days already on disk are reused.
3. Coverage is checked **hour by hour** against the symbol's trading calendar. A missing trading
   day — or a gap during an active session hour — is a hole.
4. On a hole, the backtest **refuses to run** and lists the gaps, rather than handing you a
   clean-looking result built on incomplete data.

```text
qkt: error: incomplete data for XAUUSD:
  2024-01-18  empty (empty hours 13,14,15)
  re-run with --allow-incomplete to proceed anyway
```

Two flags control this:

- `--no-fetch` — use only what's already cached. Still validated; still fails on holes. Use this
  for offline runs or when you've placed data manually (see Scenario 4).
- `--allow-incomplete` — run despite holes; prints exactly which days/hours are being ignored.

### What Dukascopy covers

FX majors, metals, and major indices: `XAUUSD`, `XAGUSD`, `EURUSD`, `GBPUSD`, `USDJPY`, `EURJPY`,
`GBPJPY`, plus index proxies (`DXY`, `SPX`, `NDX`, `DJI`, `RUT`). A symbol with no mapping fails fast
with a clear message rather than guessing — add it to `DukascopyInstrument` if you need it.

Dukascopy is an independent aggregate feed, **not your exact broker's ticks** — prices and spreads
differ slightly from any one venue. It's the standard answer to "does my strategy logic hold up on
realistic ticks." For broker-exact data, use Scenario 2.

## Scenario 2 — Pre-fetch broker bars with `qkt fetch`

`qkt fetch` pulls historical OHLC bars from a broker's native API. Use it for broker-exact history,
for bars-only venues (crypto, where there are no ticks to replay), or to pre-populate an offline box.

```bash
# Explicit UTC date range (inclusive).
qkt fetch EXNESS:XAUUSD --tf 5m --from 2024-01-01 --to 2024-12-31

# Or the last N days from today.
qkt fetch BYBIT_SPOT:BTCUSDT --tf 1h --last 90d
```

```text
qkt fetch: EXNESS:XAUUSD @ 5m from 2024-01-01 to 2024-01-03 (3 days)
  [1/3] 2024-01-01  fetched 288 bars
  [2/3] 2024-01-02  fetched 288 bars
  [3/3] 2024-01-03  skipped (already on disk)
qkt fetch: done — fetched=2 skipped=1 total=3
```

Then a backtest whose strategy uses that broker-prefixed symbol reads the bars automatically — every
UTC day in range on disk means it reads the bar store directly; any missing day falls back to tick
aggregation.

| Broker prefix | Backend | Notes |
|---|---|---|
| `EXNESS`, `ICMARKETS`, `FTMO`, `PEPPERSTONE`, … | MT5 gateway (per broker profile in `qkt.config.yaml`) | The profile's `gatewayUrl` must be reachable. `qkt brokers list` shows what's resolved. |
| `BYBIT_SPOT` / `BYBIT_LINEAR` | `api.bybit.com /v5/market/kline` | Public kline, no auth. |
| A `type: gateway` account (`DERIBIT`, …) | The venue gateway's `GET /v1/bars` | Contracts the gateway lists (futures, perpetuals); the entry's `api_key` must resolve. The bars live warmup reads, so a backtest and its live warmup see the same history. |
| `BACKTEST` | (refused) | `BACKTEST` *is* the local store — nothing to fetch from. Use a real broker prefix. |

Notes:

- **One timeframe per run.** Want 1m and 5m? Run `qkt fetch` twice; the store keys by timeframe.
- **Idempotent per day-file.** An existing day file is skipped without hitting the broker. To
  re-fetch a corrupt day, delete the file and re-run.
- MT5 history APIs are broker-dependent — some throttle hard or serve only a limited window.

## Scenario 2b — Futures contracts (Binance USDⓈ-M quarterlies, free)

Dated futures are stored one contract at a time. `BINANCE_UM` reads Binance's free public archive
(`data.binance.vision`), so no account or API key is needed.

```bash
# The root's contract list: every quarterly with its expiry and, once settled, its delivery price.
qkt fetch BINANCE_UM:BTCUSDT --catalog

# Bars for one contract, at the timeframe the strategy uses.
qkt fetch BINANCE_UM:BTCUSDT_240927 --tf 15m --from 2024-06-01 --to 2024-09-27
```

The catalog lands in `contracts/BINANCE_UM/BTCUSDT.json`, the bars in
`bars/BINANCE_UM/BTCUSDT_240927/15m/`. Declare the root under `futures:` in `instruments.yaml`
so the backtest knows each contract's multiplier, tick and fees:

```yaml
futures:
  - root: BINANCE_UM:BTCUSDT
    currency: USDT
    multiplier: 1
    tickSize: 0.1
    volumeStep: 0.001
    volumeMin: 0.001
    takerFeeRate: 0.0005
```

A day before a contract listed, or after it delivered, has no file and is recorded empty only after
delivery. A contract the strategy names but the catalog does not list fails the run up front; refresh
the catalog with `--catalog`.

Futures fill on qkt's exchange simulator, whatever `--broker` says. Market orders fill at the current
price and then slip by the run's slippage model; with `--slippage instrument` that is the root's
optional `slippageTicks` (whole ticks against the order). Limit and stop prices off the contract's
tick grid are snapped to it in the direction that never fills early. The root's fees are charged on every fill and included in the report's
`commissionPaid`. A contract held into expiry is settled at the catalog's delivery price (exit reason
`EXPIRY`), and orders on it after expiry are rejected. In the last `expiryGuardHours` before expiry
(a root key, default 24; 0 turns it off) the exchange takes only orders that reduce a position; a
root whose roll would fall inside that window is refused when a continuous stream is built from it.
Give a root `margin: { initial, maintenance, basis: notional | per_contract }` and the backtest
refuses any order that opens or adds exposure when the account's equity could not carry the initial
margin of every futures position after it (pending entries included); exits always pass. A root's
`calendar:` (`crypto`, `fx`, `nyse`, `cme_globex`) names its exchange hours; a run whose symbols are
all futures sharing one calendar trades on it (CME Globex: Sunday 17:00 to Friday 16:00 Chicago time,
halted 16:00–17:00 each weekday, so DAY orders expire at the 16:00 close). Any CFD in the run keeps
the usual calendar of the first symbol.

### Continuous futures streams (`@front`, `@next`)

A strategy can follow a root instead of one contract: `btc = BINANCE_UM:BTCUSDT@front EVERY 15m`
trades whichever contract is front and rolls to the next one on schedule; `@next` follows the one
after it. Give the root a roll policy and measure its rolls once:

```yaml
futures:
  - root: BINANCE_UM:BTCUSDT
    # …multiplier, tickSize, volumeStep, volumeMin as above…
    roll: { daysBeforeExpiry: 8, atUtc: "08:00", adjust: panama }   # adjust: none | panama | ratio
```

```bash
qkt fetch BINANCE_UM:BTCUSDT --rolls   # fetches missing roll days, writes contracts/BINANCE_UM/BTCUSDT.rolls.json
```

The series is adjusted forward from the first measured roll, so history never changes when new rolls
are added and nothing leaks from the future. Each contract's bars must be fetched at the strategy's
timeframe, and that timeframe must divide the roll time (an 08:00 roll works with 15m or 1h bars,
not 1d).

Orders on a continuous stream trade the contract that is front at the time; the engine sees fills in
the adjusted series. At each roll every open position is closed on the old contract and reopened on
the new one, and resting orders move to the new contract at the same series level. What the roll cost
against the roll's reference prices (slippage and fees) is booked as a cost, so the stream's P&L
equals the P&L of the contracts actually traded. Trading a continuous stream needs `adjust: panama`;
`ratio` and `none` streams can be read but not traded. If the new contract refuses a roll, the
position is closed at the old contract's fill (exit reason `ROLL_FAILED`) and the strategy cannot add
exposure on that stream for the rest of the run. Live, a continuous stream trades on a `type: gateway`
account, its rolls measured from the venue's own bars (parity rows A53-A57). A run window that reaches past a stream's last listed contract (for `@next`, past the roll that
makes its last contract the front one) is refused with the instant the stream ends; refresh the catalog
or end the run earlier.

## Scenario 2c — Option chains (Deribit linear USDC options, free)

Options are recorded as point-in-time chains: one row per contract per snapshot instant. Deribit's
public API serves them without an account. Only the linear `<COIN>_USDC` options are accepted;
the inverse `BTC-…` ones are priced in coin and refused. Declare the root under `options:`:

```yaml
options:
  - root: DERIBIT:BTC_USDC
    currency: USDC
    contractSize: 1
    tickSize: 5
    tickSteps: [{above: 1000, tick: 20}]   # must match the venue's tick schedule, or --catalog refuses
    volumeStep: 0.01
    volumeMin: 0.01
    underlyingIndex: btc_usdc
```

```bash
# Every listed and expired contract, plus daily delivery prices (contracts/DERIBIT/BTC_USDC.options.json).
qkt fetch DERIBIT:BTC_USDC --catalog

# History: chains for completed UTC days, built from the venue's trade history.
qkt fetch DERIBIT:BTC_USDC --chains --from 2026-09-24 --to 2026-09-30 [--every 1h] [--max-mark-age 1d]

# Forward: one snapshot of the live book now; run it on a schedule to build bid/ask history.
qkt fetch DERIBIT:BTC_USDC --chains --live
```

A strategy names a contract by its qkt code, the venue name with each `-` written `_`
(`DERIBIT:BTC_USDC_25DEC26_92000_C` for Deribit's `BTC_USDC-25DEC26-92000-C`); catalogs and chain
files keep the venue's names.

Each source is a separate series: trade-built days land in
`chains/DERIBIT/BTC_USDC/trade/<YYYY-MM-DD>.csv.gz` and live book snapshots in
`chains/DERIBIT/BTC_USDC/book/<YYYY-MM-DD>.csv.gz`, so live snapshots never block a backfill. Both
use the columns `atMs,contract,bid,ask,mark,markIv,underlying,rate,markAgeMs,source`, where an
empty cell means absent.

- **Trade history is sparse.** BTC_USDC trades a few hundred times a day. A contract is quoted
  from its last trade (mark, IV, index) only once it has traded and until its expiry. `markAgeMs`
  says how old that trade is, and contracts quiet for longer than `--max-mark-age` drop out.
  These rows have no bid or ask. Each day reads trades from `--max-mark-age` before its start, so
  a day's file is the same however the range is split. A day can be fetched from 5 minutes after it
  ends (the history host trails by about a minute). Days already on disk are skipped; delete a file
  to rebuild it. `--every` is at least `1m` and must divide a day.
- **The live book is dense.** Every listed contract has its best bid and ask (a missing side stays
  empty), mark, mark IV, rate, and its expiry's forward as `underlying`. The snapshot is stamped at
  its newest row, and each row carries its own small age. A contract still listed after its expiry
  is left out. `--live` adds to the day's file, and overlapping runs wait for each other.
- A trade-built day that traded a contract missing from the catalog is refused rather than written
  incomplete. Refresh the catalog with `--catalog` and fetch again. A live snapshot quotes the
  contracts it knows and warns about the rest.
- `--live`, `--every` and `--max-mark-age` need `--chains`; `--chains` does not combine with
  `--catalog`, `--rolls` or `--tf`.

### Backtesting options on the chain

Declare how a root trades and name a contract in a strategy:

```yaml
options:
  - root: DERIBIT:BTC_USDC
    # …contractSize, tickSize, volumeStep, volumeMin, underlyingIndex as above…
    chains: trade            # or book: the stored series to trade on
    markSpread: 0.05         # trade series only: half-spread as a fraction of the mark
    maxQuoteAgeMinutes: 60   # older trade marks are not tradeable
    takerFeeRate: 0.0003     # of the underlying index, per contract
    deliveryFeeRate: 0.00015 # of the delivery price, at an in-the-money expiry
    feeCapRate: 0.125        # each fee capped at 12.5% of the option's value
```

```
STRATEGY call VERSION 1
SYMBOLS
    c = DERIBIT:BTC_USDC_26SEP26_84000_C EVERY 1h
RULES
    WHEN c.close > 0
    THEN BUY c SIZING 0.1
```

- The stream's price is the chain's mark at each snapshot; its bid and ask are the tradeable sides.
- Orders fill on the option venue, whatever `--broker` says. A market order fills at the next quote of its
  contract after the decision, never the one it was decided on: a buy at the ask, a sell at the bid. A quote
  without that side cancels the order, with the reason in the log. With hourly snapshots, a 1h strategy
  decides on one quote and fills on the next.
- Limits are snapped so they never fill early, and fill at their limit when a later quote reaches them.
- Selling opens a short. Before an option order is accepted, equity must cover the account's
  worst-case expiry loss per root and expiry:
  - a long option needs its premium;
  - a credit spread needs its width less its credit;
  - a short put needs its strike less its mark (cash-secured).

  A naked short call has unlimited loss and is refused. So is a short call covered only by another
  expiry's call. Buying back always passes.
- A contract held to expiry settles in cash at its intrinsic value from the catalog's delivery price, less
  the capped delivery fee: a long receives it, a short pays it. Out of the money it settles at zero.
- The run checks that every day up to each contract's expiry has a stored chain day of the declared series,
  and names the `qkt fetch … --chains` that fills a gap.
- The chain's implied volatility and skew can drive rules as read-only streams:
  `iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.30d EVERY 1h` (see [chain analytics](../reference/dsl/chain.md)).

## Scenario 3 — Speed up repeated backtests (CSV → binary)

Cached ticks start life as gzipped CSV (`*.csv.gz`). Converting them to the binary format decodes
~2.7× faster and is read in preference to CSV automatically.

```bash
# Convert a symbol's cached days to .bin (idempotent — skips days already converted).
qkt data convert XAUUSD --from 2024-01-01 --to 2024-12-31

# Add --prune to delete the .csv.gz after a successful convert (reclaims disk).
qkt data convert XAUUSD --prune
```

When reading, the store prefers `<day>.bin`, then `<day>.csv.gz`, then `<day>.csv` — so `.bin` and
CSV can coexist and you can convert incrementally.

Check integrity at any time:

```bash
qkt data verify XAUUSD     # reports tick counts, max intra-day gap, and flags EMPTY/CORRUPT/GAP days
```

## Scenario 4 — Place your own tick data manually

You can drop CSVs straight into the tick store — handy for broker-exported ticks or a private feed.

**Where:** `~/.qkt/data/symbols/<BARE_SYMBOL>/<YYYY-MM-DD>.csv` (one UTC day per file). The directory
is the **bare** symbol — `XAUUSD`, not `BACKTEST:XAUUSD`. `.csv.gz` is also accepted.

**Exact format** — the header must match byte-for-byte, every row has 8 fields, timestamps are epoch
milliseconds and **strictly non-decreasing**. `price`, `volume`, `bid`, `ask`, `bidVolume`, `askVolume`
are each optional (leave blank); supply at least `price`, or `bid`+`ask` (mid is derived):

```text
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
1705276800000,XAUUSD,2050.50000000,1.0,,,,
1705276860000,XAUUSD,2050.75000000,1.5,,,,
```

!!! warning "Pass `--no-fetch`, or your file gets re-fetched over"
    The backtest decides *what to fetch* from each symbol's `manifest.json` (the coverage ledger),
    not from which files exist. A day you dropped in but did not record in the manifest looks
    "missing" to the fetcher, so a normal run will **re-download it from Dukascopy and overwrite your
    file**. Two ways to keep your data:

    - **Run with `--no-fetch`** (simplest). Fetching is skipped entirely; the completeness check and
      the backtest both read your actual files.
    - **Or write the manifest** so the fetcher counts those days as covered:

      ```json
      {
        "schemaVersion": 1,
        "schema": "qkt-csv-v1",
        "symbol": "XAUUSD",
        "ranges": [ { "from": "2024-01-15", "to": "2024-01-17" } ],
        "lastUpdated": "2024-01-17T00:00:00Z"
      }
      ```
      Save it as `~/.qkt/data/symbols/XAUUSD/manifest.json`. `to` is exclusive (the day after the last
      covered day).

The completeness check and the read path always look at the real files on disk, so manually placed
data is used as-is once fetching won't clobber it.

## Scenario 5 — Custom downloader (legacy script fetcher)

To supply ticks from your own script instead of the built-in Dukascopy client:

```bash
qkt backtest strategies/my_strategy.qkt --from 2024-01-01 --to 2024-02-01 \
  --fetcher dukascopy --fetcher-script scripts/fetch-dukascopy.sh
```

The script is invoked as `script SYMBOL YYYY-MM-DD TARGET_PATH` and must write a gzipped tick CSV
(same format as Scenario 4) to `TARGET_PATH`. `scripts/fetch-dukascopy.sh` (a `dukascopy-node`
wrapper) ships as a reference. This path takes precedence over the built-in fetcher when set.

## Contract specs, commission, and swap (`instruments.yaml`)

To turn lots into dollars a backtest needs each instrument's contract specs (lot size, lot step,
price precision). qkt ships standard specs for every FX major and metal it can fetch, so gold and
FX backtests size correctly out of the box.

`instruments.yaml` is an **override**, not a requirement. Drop it at `<data-root>/instruments.yaml`
(i.e. `~/.qkt/data/instruments.yaml`) or pass `--instruments <path>` to set a broker's commission
and overnight swap (both default to zero), or add a symbol the standard table doesn't cover.
The repository's `data/instruments.yaml` is a starter for the bundled
`BACKTEST:BTCUSDT` tutorial. Copy an entry and replace every contract field from
your venue's symbol specification before using another non-standard instrument.
Entries are keyed by the
broker-prefixed symbol from your strategy:

```yaml
instruments:
  - qktSymbol: BACKTEST:XAUUSD
    contractSize: 100
    volumeStep: 0.01
    volumeMin: 0.01
    pointSize: 0.001
    digits: 3
    tradeStopsLevelPoints: 0
    commissionPerLot: 7.0   # optional — $ per lot per fill
    swapLongPoints: -32.4   # optional — signed points per long lot per rollover
    swapShortPoints: 14.1   # optional — signed points per short lot per rollover
    swapRolloverHourUtc: 21 # optional — 0..23, default 21
    swapTripleDay: WEDNESDAY # optional — Monday..Friday, default Wednesday
```

A symbol present in the file wins; anything omitted falls back to the built-in specs.
Positive swap points credit PnL and negative points debit it. The configured triple-day
rollover is multiplied by three and weekend rollovers are skipped. Refresh these values
from the broker before a cost-sensitive run because broker schedules can change.

## Reference

### Store layout

```
~/.qkt/data/
├── symbols/                      # ticks, keyed by BARE symbol
│   └── XAUUSD/
│       ├── 2024-01-15.bin        # preferred (fast); produced by `qkt data convert`
│       ├── 2024-01-16.csv.gz     # auto-fetched / script-fetched ticks
│       ├── 2024-01-17.csv        # uncompressed manual drop-in
│       └── manifest.json         # fetch-coverage ledger (contiguous date ranges)
├── bars/                         # OHLC bars, keyed by BROKER/symbol/timeframe
│   └── EXNESS/XAUUSD/5m/
│       ├── 2024-01-01.csv
│       └── manifest.json
└── instruments.yaml              # optional contract-spec / commission overrides
```

Read precedence for a tick day: `.bin` → `.csv.gz` → `.csv`.

### Data flags (`qkt backtest` / `qkt sweep`)

| Flag | Default | Effect |
|---|---|---|
| `--from <date>` / `--to <date>` | required | Backtest range. `--from` inclusive, `--to` exclusive. ISO date or datetime. |
| `--data-root <dir>` | `~/.qkt/data` (or `$QKT_DATA_HOME`) | Store root; flag wins over env var. |
| `--no-fetch` | off (fetch enabled) | Use only cached data; never download. |
| `--allow-incomplete` | off (strict) | Proceed despite holes; print what's ignored. |
| `--fetcher dukascopy --fetcher-script <p>` | built-in client | Use a custom downloader script (takes precedence). |
| `--instruments <path>` | `<root>/instruments.yaml` | Contract-spec / commission overrides. |

### File formats

- **Tick CSV** (`symbols/<SYM>/<day>.csv`): header `timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume`;
  8 fields per row; epoch-ms timestamps, strictly non-decreasing; price/bid/ask optional.
- **Bar CSV** (`bars/<BROKER>/<SYM>/<TF>/<day>.csv`): header `timestamp,open,high,low,close,volume`;
  epoch-ms bar-start timestamps.
- **Binary tick** (`.bin`): columnar, scaled-integer encoding of the same fields; produced only by
  `qkt data convert`; not hand-editable.

### Common errors

| Symptom | Cause / fix |
|---|---|
| `incomplete data for <SYM>: … re-run with --allow-incomplete` | Real or session-hour gap. Let it fetch, or accept gaps with `--allow-incomplete`. |
| `dukascopy fetch failed: HTTP 503` | Dukascopy throttling a month-block. Retry later, or fetch in smaller ranges. |
| `no dukascopy mapping for <SYM>` | Symbol isn't in `DukascopyInstrument`. Use a mapped symbol, place data manually, or add the mapping. |
| `unexpected header at …` | A manual tick CSV's header doesn't match exactly. Use the header in File formats above. |
| Backtest re-downloads data you placed by hand | Pass `--no-fetch`, or record the days in `manifest.json` (Scenario 4). |
| Backtest can't find data you fetched | `fetch`/`convert`/`backtest` pointed at different roots. Use the same `--data-root` (or none — they all default to `~/.qkt/data`). |

## Related

- [Backtest model](../concepts/backtest-model.md) — what the engine guarantees, and the intrabar caveat.
- [Backtest vs live parity](../parity/backtest-vs-live.md) — why backtest and live produce identical fills.
- [Phase 25B — live auto-warmup](../phases/phase-25b-live-auto-warmup.md) — live deploys auto-fetch warmup history; you don't pre-fetch for them.
