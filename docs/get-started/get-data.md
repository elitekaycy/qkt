# Get data

A backtest needs market data on your disk. This page shows the three ways to get it there, where qkt keeps it, and how
qkt reads it. Read it top to bottom once; after that the table is all you need.

| You have | Do this | Takes |
|---|---|---|
| Nothing yet, and you trade FX, metals or indices | [Run a backtest](#1-no-data-yet-let-the-backtest-fetch-it): free Dukascopy ticks download themselves | one command |
| A broker account (MT5, Bybit, Deribit) | [`qkt fetch`](#2-from-your-broker-qkt-fetch) your broker's own bars | one command per timeframe |
| Your own files: CSVs, MT5 or TradingView exports, Dukascopy, Binance, Databento… | [`qkt data import`](#3-your-own-files-qkt-data-import) them | a dry run, then the import |

## Where your data lives

Everything goes in one folder, the **data root**: `~/.qkt/data` unless you say otherwise. Every command that reads or
writes data uses the same one.

```text
~/.qkt/data/
├── symbols/EURUSD/2026-10-13.csv.gz          ticks, one file per UTC day
├── bars/BINANCE/BTCUSDT/1m/2026-10-13.csv    bars, by source, symbol and timeframe
└── instruments.yaml                           contract specs, fees (optional)
```

To use a different folder:

| How | Applies to | Example |
|---|---|---|
| `--data-root <dir>` | that one command | `qkt backtest s.qkt --data-root ./data` |
| `QKT_DATA_HOME` | every command in that shell | `export QKT_DATA_HOME=/mnt/ssd/qkt-data` (add it to `~/.bashrc` to keep it) |
| `data_root:` in `qkt.config.yaml` | every command run in that project | `data_root: ./data` (relative to the config file) |
| nothing | everything | `~/.qkt/data` |

The first one set wins, in that order. Every command prints the folder it uses and why, e.g.
`qkt: data root /home/me/proj/data (from data_root in /home/me/proj/qkt.config.yaml)`.

- **Per project:** a `data_root` in the project's `qkt.config.yaml` is found from the project folder or any folder
  inside it, and a relative path means the project's own folder, wherever you run the command from. Projects made
  with `qkt create template` already have it: `data_root: ${QKT_DATA_HOME:-./data}` means `data/` next to the
  config, and `/data` inside their Docker container.
- **A folder that isn't there:** an absolute `data_root` that doesn't exist (for example `/data` copied from a
  Docker setup and run on your machine) is refused, never swapped for another folder: pass `--data-root` or fix the
  path. A missing relative folder is created when data is first written.

## 1. No data yet: let the backtest fetch it

For FX, metals and indices (`EURUSD`, `XAUUSD`, `GBPUSD`, `USDJPY`, `SPX`, …), name the symbol with `BACKTEST:` and run
the backtest. The ticks it needs come from Dukascopy, free and without an account:

```qkt
STRATEGY ema VERSION 1
SYMBOLS
    eur = BACKTEST:EURUSD EVERY 15m
```

```bash
qkt backtest ema.qkt --from 2026-10-12 --to 2026-10-15
```

```text
qkt: fetching EURUSD ticks for 2026-10-12 (1 of 3)
qkt: fetching EURUSD ticks for 2026-10-13 (2 of 3)
qkt: fetching EURUSD ticks for 2026-10-14 (3 of 3)
qkt: tick coverage EURUSD 3/3 trading days
```

The first run can take a while: Dukascopy serves one file per hour, and it slows down or refuses busy clients. When it
does, qkt says so (`dukascopy did not answer … retrying in 8s`) and keeps going. Every finished day is saved at once,
so stopping with Ctrl-C loses nothing, and the next run fetches only what is still missing. Later runs over the same
days start straight away.

Dukascopy is an independent feed, not your broker's: its prices and spreads differ slightly from any one venue. That
is fine for testing an idea; for broker-exact prices, fetch from your broker.

## 2. From your broker: `qkt fetch`

```bash
qkt fetch EXNESS:XAUUSD --tf 1m --from 2026-01-01 --to 2026-09-30
```

This pulls the broker's own bars through the gateway you set up in [the MT5 gateway](mt5-gateway.md) (or a venue
gateway for Bybit and Deribit) into `bars/EXNESS/XAUUSD/1m/`. A strategy reads them by naming the same source:
`gold = EXNESS:XAUUSD EVERY 15m`. Fetch the **finest** timeframe you will use: 15m, 1h, 4h and 1d are built from it
(see [Timeframes](#timeframes-bars-from-bars)). Days already on disk are skipped.

## 3. Your own files: `qkt data import`

This is how CSVs and exports you already have get into qkt. Always look first with `--dry-run`, which checks everything
and writes nothing:

```bash
qkt data import BTCUSDT-1m-2026-10-13.zip BTCUSDT-1m-2026-10-14.zip --as BINANCE:BTCUSDT --dry-run
```

```text
Read     2 input(s), 2880 rows → 2880 bars records
Format   binance-klines (matched): Binance public-data klines without a header (data.binance.vision spot, …)
Time     epoch:us
Clock    timestamps carry their own zone
Bars     1m

  raw line → stored (UTC)
  1791849600000000,81850.01000000,81938.68000000,81850.000 → 2026-10-13T00:00:00Z o=81850.01 h=81938.68 l=81850 c=81878.41 v=15.55685
  …
Checks   0 duplicate(s) dropped · 0 crossed quote(s) dropped · 0 spike(s) flagged (kept)
Days     2  2026-10-13 … 2026-10-14
Writes   nothing (dry run) → bars/BINANCE/BTCUSDT/1m
```

Check that the first and last rows became the times and prices you expect, then run it again without `--dry-run`.

- **`--as SOURCE:SYMBOL`** is the name your strategy will use. The source part (`BINANCE`, `MYBROKER`, `EXNESS`) keeps
  each source's bars apart; pick any name for your own data.
- **Recognised formats need no other flags.** qkt knows MT5 and MT4 exports, Dukascopy, HistData, TradingView, Binance,
  Bybit, OKX, Kraken, Databento, Sierra Chart, Yahoo Finance, Polygon and more: `qkt data presets` lists them.
- **Any other CSV** needs its columns named once, e.g.
  `--kind bars --map time=Date,open=Open,high=High,low=Low,close=Close,volume=Vol --tz UTC`. A column already named
  `open`, `close`, `bid`, `time`… maps itself.
- **The clock matters.** A timestamp without a zone (`2026-10-13 09:00`) needs `--tz`: `UTC`, `ny-close` (the "GMT+2/+3"
  clock most MT5 brokers use) or a zone like `Europe/London`. For FX and metals qkt checks the clock against when each
  week opens, and refuses a wrong one before writing anything.
- **Ticks or bars?** A recognised format knows which it holds; for any other file say so with `--kind ticks` or
  `--kind bars`. Ticks go to `symbols/`, bars to `bars/`.

Every refusal happens before the first write, so a failed import leaves your store exactly as it was. The full guide,
with every format, flag and refusal, is [Import data from any source](../how-to/import-data.md).

## How a strategy finds its data

The prefix on a strategy's symbol decides where qkt looks:

| Strategy says | qkt reads | Filled by |
|---|---|---|
| `BACKTEST:EURUSD` | ticks in `symbols/EURUSD/` | the backtest itself (Dukascopy), or an imported tick file |
| `EXNESS:XAUUSD`, `BINANCE:BTCUSDT`, any `SOURCE:SYMBOL` | bars in `bars/SOURCE/SYMBOL/`; any day missing there is built from the ticks in `symbols/SYMBOL/` | `qkt fetch`, `qkt data import` |

Bars you import or fetch need no special flag: `qkt backtest` uses them as they are, and `qkt backtest --bars` reads
them too (each day's built bars first, else that day's imported ones).

## Timeframes: bars from bars

Store the finest bars you have, usually 1m. A strategy asking for `EVERY 1h` gets hourly bars built from them as it
runs, cut on the UTC clock, so every timeframe sees the same prices. Built from Binance's 1m bars, qkt's 24 hourly bars
for 2026-10-14 are identical to the hourly bars Binance publishes:

```bash
qkt data compare BINANCE:BTCUSDT --against BINANCEH1:BTCUSDT --from 2026-10-14 --to 2026-10-14 --tf 1h
```

```text
  identical: 24 bars matched, 0 only in the first, 0 only in the second, offset 0m, close Δ median 0 p99 0 max 0
```

(`BINANCEH1` here is Binance's own 1h file, imported under a second name to compare against.)

Have ticks and want quick research runs on bars? `qkt data build-bars EURUSD --tf 1m` builds 1m bars from your stored
ticks once, and `qkt backtest --bars` then runs on them instead of replaying every tick.

Don't import 4h or daily bars from a broker: those are cut at the broker's midnight, not UTC, so qkt refuses them and
builds its own from 1h or finer.

## Check your data

Give `verify` the name your strategy uses: a bare symbol checks ticks, `SOURCE:SYMBOL` checks bars.

```bash
qkt data verify EURUSD             # ticks
qkt data verify BINANCE:BTCUSDT    # bars (every timeframe stored; --tf 1m for one)
```

```text
qkt data verify: BINANCE:BTCUSDT 1m .csv bars, read by backtests (2 day files) under ~/.qkt/data/bars/BINANCE/BTCUSDT/1m
  2026-10-13  bars=1440  maxGap=1m  ok  imported binance-klines
  2026-10-14  bars=1440  maxGap=1m  ok  imported binance-klines
qkt data verify: done — 2 days, 2880 bars, 0 flagged
```

Each day shows how many ticks or bars it holds and the longest gap. A day is flagged when its file can't be read, its
bars are out of order or off the timeframe's grid, or a price is impossible (a high below the close, say). Weekdays
with no file between stored days are listed as `MISSING`, a hole or a holiday:

```text
  2026-04-16 … 2026-09-07  MISSING (103 weekday(s) with no file between stored days: a hole or a holiday)
```

The exit code is non-zero when anything is flagged. To measure your data against your broker's, `qkt data compare` it
with bars you `qkt fetch`. A backtest also checks coverage before it runs, and refuses to run on missing days unless
you pass `--allow-incomplete`.

## How the cache works

The data root *is* the cache: there is nothing else to manage.

- **A day on disk is reused.** A backtest downloads only the days it lacks; `qkt fetch` skips days it already has.
- **A day is saved as soon as it lands,** so an interrupted fetch keeps every day it finished.
- **An incomplete fetched day is fetched again once** before a backtest gives up on it.
- **Your imported days are never replaced by a download.** Re-importing the same file changes nothing.
- **Offline or pinned?** `--no-fetch` uses only what is on disk, and still refuses gaps.
- **Faster repeated runs:** `qkt data convert EURUSD` writes a compact `.bin` beside each tick day, which qkt then
  reads first.
- **To refetch a day,** delete its file (`symbols/EURUSD/2026-10-13.csv.gz`) and run again.

## Next

- [Quickstart](quickstart.md): your first strategy on this data.
- [Import data from any source](../how-to/import-data.md): every format, flag and refusal.
- [Getting and storing data](../how-to/backtest-data.md): futures, options, funding, open interest, order-book depth.
