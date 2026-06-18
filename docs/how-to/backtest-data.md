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
| `BACKTEST` | (refused) | `BACKTEST` *is* the local store — nothing to fetch from. Use a real broker prefix. |

Notes:

- **One timeframe per run.** Want 1m and 5m? Run `qkt fetch` twice; the store keys by timeframe.
- **Idempotent per day-file.** An existing day file is skipped without hitting the broker. To
  re-fetch a corrupt day, delete the file and re-run.
- MT5 history APIs are broker-dependent — some throttle hard or serve only a limited window.

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

## Contract specs and commission (`instruments.yaml`)

To turn lots into dollars a backtest needs each instrument's contract specs (lot size, lot step,
price precision). qkt ships standard specs for every FX major and metal it can fetch, so gold and
FX backtests size correctly out of the box.

`instruments.yaml` is an **override**, not a requirement. Drop it at `<data-root>/instruments.yaml`
(i.e. `~/.qkt/data/instruments.yaml`) or pass `--instruments <path>` to set a broker's commission
(default is zero) or add a symbol the standard table doesn't cover. Entries are keyed by the
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
```

A symbol present in the file wins; anything omitted falls back to the built-in specs.

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
