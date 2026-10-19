# Import data from any source

`qkt data import` turns a market-data file you already have (an MT5 export, a Dukascopy or HistData
download, a TradingView chart export, a Binance archive, your broker's own CSV) into qkt's store, so
backtests read it like any other data. It converts exactly, proves what it can, refuses what it cannot
prove, and records what it did.

```bash
qkt data import ~/Downloads/XAUUSD_M1_2024.csv --as EXNESS:XAUUSD --dry-run   # look first
qkt data import ~/Downloads/XAUUSD_M1_2024.csv --as EXNESS:XAUUSD             # then write
```

`--dry-run` reads and checks everything and writes nothing. The report shows which source it recognised,
the clock and the evidence for it, the first and last rows next to what they become, and every row it
dropped or flagged.

## What it can import

| Kind (`--kind`) | Lands in | Read by |
|---|---|---|
| `ticks` | `symbols/<SYMBOL>/<day>.csv.gz` + `manifest.json` | tick backtests, `--tick-fills`, bars built from ticks |
| `bars` | `bars/<SOURCE>/<SYMBOL>/<tf>/<day>.csv` + `manifest.json` | streams named `SOURCE:SYMBOL`, rolled up to coarser timeframes on read |
| `funding` | `funding/<VENUE>/<NAME>.csv` | perpetual funding charges |
| `open-interest` | `open_interest/<VENUE>/<NAME>.csv` | `<alias>.open_interest` |
| `series` | `bars/<SOURCE>/<NAME>/1d/<day>.csv` (value as open, high, low and close) | a daily stream, e.g. COT positions |

Bars need a source prefix (`--as EXNESS:XAUUSD`): the bar store keeps each source apart, and a strategy
reads them as `EXNESS:XAUUSD EVERY 15m`. Import the finest timeframe you have; qkt builds 5m, 1h, 4h and
1d from 1m bars itself, on the UTC grid, so every timeframe sees the same prices.

## Sources it recognises without flags

`qkt data presets` lists them. Each preset is a small YAML file describing a source, and adding one is
adding a file, not code.

| Preset | Source |
|---|---|
| `mt5-bars`, `mt5-ticks` | MetaTrader 5 Symbols window export (tab-separated, UTF-8 or UTF-16, on your broker's server time: see [MetaTrader files](#metatrader-files-are-on-your-brokers-clock)) |
| `mt5-bars-daily` | MetaTrader 5 D1 export: recognised and refused (its days start at broker midnight, not UTC) |
| `mt4-csv` | MetaTrader 4 History Center CSV |
| `histdata-m1`, `histdata-ticks`, `histdata-mt` | HistData.com (EST all year, no DST) |
| `dukascopy-gmt-bars`, `dukascopy-local-bars`, `dukascopy-gmt-ticks` | Dukascopy website / JForex export |
| `dukascopy-node-bars`, `dukascopy-node-ticks` | dukascopy-node CSV |
| `dukascopy-bi5` | Dukascopy datafeed hour files `<YYYY>/<MM from 00>/<DD>/<HH>h_ticks.bi5`, decoded as qkt's own fetcher does |
| `databento-ohlcv`, `databento-trades`, `databento-mbp1` | Databento DBN files (versions 1 to 3, uncompressed: `zstd -d` a `.dbn.zst` first), one instrument a file, read natively: bars at `ts_event`, trades and top of book at `ts_recv`, prices exact from 1e-9 integers; a book update with an empty side is skipped and counted, its trades kept; `mbo`, `mbp-10`, definitions and statistics are refused |
| `sierra-scid-ticks`, `sierra-scid-bars` | Sierra Chart intraday files (`.scid`), read natively: UTC times (current microsecond or old day-count format), float32 prices rounded to the instrument's digits (pass `--digits` when qkt has no spec); a file mixing trades and bars is refused |
| `mt4-hst` | MetaTrader 4 history files (`<SYMBOL><PERIOD>.hst`, version 400 or 401), read natively: bid bars on the broker clock, shifted to mid by the stored spread, prices rounded to the header's digits (which become the instrument's unless `--digits` says otherwise) |
| `tradingview-unix`, `tradingview-iso` | TradingView chart export |
| `binance-klines`, `binance-klines-header`, `binance-klines-json` | Binance public data and REST klines (ms, and µs for spot since 2025) |
| `binance-trades`, `binance-trades-header`, `binance-agg-trades`, `binance-agg-trades-header` | Binance public-data trades and aggTrades, spot and futures (files named `<SYMBOL>-trades-<date>` / `-aggTrades-`); trades build the venue's own klines exactly, aggTrades can differ where one order fills across a bar boundary |
| `binance-funding`, `binance-funding-api`, `binance-metrics` | Binance funding rates and 5-minute open interest |
| `bybit-trades`, `bybit-spot-trades`, `okx-candles`, `okx-trades`, `kraken-ohlcvt` | exchange archives |
| `truefx-ticks` | TrueFX |
| `yfinance-daily`, `yfinance-daily-adjusted`, `yfinance-history-daily`, `yfinance-intraday`, `yfinance-multi` | Yahoo Finance via yfinance: `download()` saved flat or with its three-line header (one ticker or several), and `Ticker.history()`; float32 prices restored to their decimals |
| `alphavantage-intraday`, `alphavantage-daily` | Alpha Vantage CSV |
| `polygon-aggs-flat`, `polygon-aggs-json` | Polygon (Massive) flat files and REST aggregates |
| `oanda-candles`, `ccxt-ohlcv` | OANDA v20 candles, ccxt `fetch_ohlcv` dumps |
| `qkt-cot` | COT tables with a `visible_session` column |

Files can be plain, `.gz` or `.zip` (every data member is read; checksum and report members are skipped),
or a directory of them. Dukascopy publishes bars as one file per side (`…_BID_…csv`, `…_ASK_…csv`):
import both together and each pair of bars becomes one mid bar (open, high, low and close the mean of the
two sides, volume their sum); a bar on one side without its partner is refused.

## Any other source

Describe the file once with flags, check it with `--dry-run`, then save the description with `--plan`:

```bash
qkt data import broker.csv --as MYBROKER:EURUSD --kind bars \
  --map time=Date+Time,open=Open,high=High,low=Low,close=Close,volume=Vol \
  --time-format 'dd/MM/yyyy HH:mm' --tz ny-close --dry-run

qkt data import broker.csv --as MYBROKER:EURUSD --kind bars --map ... --plan > mybroker.yaml
qkt data import next-month.csv --as MYBROKER:EURUSD --preset mybroker.yaml
```

- With `--kind` and no matching preset, a column named exactly like a field (`open`, `bid`, `volume`, any
  case) maps itself, and the one column named `time`, `timestamp`, `datetime` or `date` is the time.
- `--map field=column`: columns by header name (spaces allowed), `$N` by position, `a.b` inside JSON.
  Expressions are exact: `close=<CLOSE>+<SPREAD>*point/2`. A trailing `?` makes a column optional.
- Fields: ticks `bid ask price volume bidVolume askVolume`; bars `open high low close volume spread`;
  funding `rate price`; open interest `openInterest`; series `value`.
- `--time-format`: `epoch` (the unit is read from each file), `epoch:s|ms|us|ns`, `iso`, or a `java.time`
  pattern (`yyyy.MM.dd HH:mm:ss`, `dd.MM.yyyy HH:mm:ss.SSS 'GMT'xx`). Without it qkt tries the common
  ones and refuses when two fit (`03/04/2024` could be March or April).
- `--tz`: `UTC`, a fixed offset (`+02:00`), `ny-close`, or an IANA zone (`Europe/Athens`).
- `--where column=value`: keep only matching rows, e.g. one ticker from a file of many.
- `--price bid|ask|mid|last`, `--label open|close`, `--tf`, `--digits`, `--decimal`, `--delimiter`,
  `--format json --rows results`, `--encoding latin-1`.

## Clocks

A timestamp that carries its zone (an epoch, `Z`, an offset) needs nothing. A wall-clock time needs its
clock, and getting it wrong moves every bar without any error, so qkt checks it.

- **`ny-close`** is the retail broker clock written "GMT+2/+3": New York time plus seven hours, so each
  trading day starts at 00:00 server time. It follows US daylight-saving dates. `Europe/Athens` does
  not: it switches on EU dates, two to three weeks apart in spring and about a week in autumn.
- For FX and metals, every week opens at 17:00 New York (metals 18:00). qkt reads each week's first
  timestamp on the stated clock and refuses when the opens disagree, naming the clocks that would fit:

  ```text
  qkt: the weekly opens contradict the clock (stated by --tz): Europe/Athens: 51 weekly opens, 47 at
       17:00 New York, 4 elsewhere. Clocks that fit: ny-close. Pass the right --tz, or --trust-clock if
       the source really opens this way
    week opening 2024-03-10T22:00:00Z at 18:00 New York
    week opening 2024-03-17T22:00:00Z at 18:00 New York
    week opening 2024-03-24T22:00:00Z at 18:00 New York
    week opening 2024-10-27T22:00:00Z at 18:00 New York
    Nothing was written.
  ```

  The four weeks are exactly those when the US had switched its clocks and Europe had not.

  With no `--tz`, an FX file whose opens fit exactly one candidate clock is read on it, and the report
  says so. Add `--trust-clock` only when the source really does open at an unusual time.
- A wall-clock time inside a DST gap (the clock skipped it) is refused. Inside the repeated autumn hour
  the file's own order decides which pass it is.
- Crypto and other round-the-clock symbols carry no such evidence: naive times need `--tz`.
- A week whose first timestamp comes after the others' (HistData EURUSD on 2026-03-29 opens at 18:00 New
  York, its first hour missing) is accepted on a stated clock and named in the report; a whole season of
  late opens is a wrong DST rule and still refuses. A silence longer than five days is a hole in the data,
  not a weekend, and says nothing about the clock.

### MetaTrader files are on your broker's clock

MT5 and MT4 exports (`mt5-bars`, `mt5-ticks`, `mt4-csv`, `mt4-hst`) are written in the broker's server
time, which differs by broker: **Exness runs on UTC**; IC Markets, Pepperstone, FTMO and most others on
`ny-close`. MetaTrader shows it as the Market Watch clock. qkt reads the clock from the file's weekly
opens when they single one out (several clocks that put every row at the same instant, such as `ny-close`
and `+03:00` through a US summer, count as one), and otherwise refuses and asks:

```bash
qkt data import EURUSD_M1.csv --as EXNESS:EURUSD --tz UTC        # Exness
qkt data import EURUSD_M1.csv --as ICM:EURUSD --tz ny-close      # IC Markets
```

A short export with no weekend in it carries no evidence at all, so it always needs `--tz`.

## What it refuses, and why

| Refusal | Why |
|---|---|
| ambiguous delimiter, decimal comma or date order | a wrong guess changes prices or dates silently |
| a time unit that changes within a file, an epoch outside 1990 to now | a wrong unit puts bars in the wrong decade |
| out-of-order rows (unless `--sort`), tick files overlapping in time | ties and duplicates could not be resolved deterministically |
| two different bars at one time, a bar whose high is below its close | the source contradicts itself |
| bars off the UTC grid, e.g. broker H4 bars cut at broker midnight | qkt's bars are cut at UTC; import H1 or finer and let qkt build H4 and D1 |
| daily bars dated by day only on a broker clock (MT5 D1), or FX/metals dates on no stated clock | each bar would hold another day's prices; import H1 or finer, or pass `--tz UTC` if the dates are UTC days |
| a `series` without `--available` or `--available-lag` | stamping a published value on the date it describes leaks the future |
| Parquet | convert it: `duckdb -c "COPY (FROM 'x.parquet') TO 'x.csv'"` (the refusal prints the command for your file) |
| MT5 `.hcc`/`.tkc` caches | undocumented and changed between terminal builds: export from MT5's Symbols window instead |
| a file of several tickers (yfinance `download(["AAPL","MSFT"])`) whose tickers do not include `--as`, or any file with two columns of one name | reading either column would be a guess; qkt reads the columns under the `--as` ticker when the file names them |

Every refusal happens before the first write, so a refused import leaves the store as it was.

Some things are fixed or flagged rather than refused, and the report counts each:

- exact duplicate bars and crossed quotes are dropped (qkt's reader drops crossed quotes anyway);
- price spikes that jump and immediately revert are flagged and kept;
- digits past the eighth decimal are rounded to qkt's precision;
- Yahoo (yfinance) prices are float32 values printed as doubles (`340.79998779296875` is the float32
  nearest 340.80); qkt stores the shortest decimal naming that float32 (340.8), and keeps any value
  that is not a float32 as written;
- two funding or open-interest values at one time refuse, unless `--on-conflict last` keeps the later file's;
- bid bars without a spread column are stored as they are, with a note that qkt reads bar prices as mid.

## Several sources for one symbol

A symbol's ticks (and one `SOURCE:SYMBOL` bar series) can come from several imports, say Dukascopy for
Monday to Friday and an MT5 export from Wednesday to the next Friday. qkt keeps the series consistent:

- **A day comes whole from one source.** Days are never spliced: a switch inside a day would be a jump at
  an arbitrary minute.
- **`--on-overlap fill` (the default)** writes the days the store lacks and keeps each stored day unless
  the incoming one covers more hours of it. A source's partial first or last day therefore never replaces a
  complete one, and a complete day replaces a partial one (the old file goes to `.import-backup/`).
  `--on-overlap skip` always keeps stored days, `replace` always takes the incoming ones, `refuse` stops.
- **Shared days are checked first.** The stored and incoming prices are compared minute by minute; if they
  line up only when shifted (one source on the wrong clock), the import refuses. The report shows the price
  difference between the sources, which is how an unknown source is checked against a trusted one:

  ```text
  Overlap  stored vs incoming over 3 shared day(s): matches: 3960 bars matched, ... offset 0m,
           close Δ median 2 pt ...
  Seam     2024-03-08 → 2024-03-11: imported dukascopy-bi5 → this import (mt5-ticks), price step 3 pt
  ```
- **Every switch of source is a seam**, listed with the price step across it. Two vendors never quote the
  same prices, so a strategy sees that step; the report and `.import.json` make it visible. Prices are never
  adjusted to hide it.
- Funding and open interest merge by time: `fill` adds the times the store lacks and keeps stored values
  where the two disagree (listed); `replace` overwrites them.

## After importing

- Re-importing the same files the same way changes nothing (`unchanged` days).
- `qkt data bars <SYMBOL> --tf 1h --from D --to D` prints the bars a backtest reads, as CSV: imported bars,
  bars qkt rolls up from them, or bars built from ticks. Compare them with your source's own coarser bars.
- Every target directory has `.import.json`, recording the input files' SHA-256, the preset, the clock
  and its evidence, and the counts. `qkt data verify <SYMBOL>` shows which days were imported and judges coverage as a
  backtest does: a trading day with no file (`MISSING`), no data (`EMPTY`) or over 90 minutes of session time
  without any (`GAP`) is flagged, holidays and holiday short sessions are shown
  ([How coverage is judged](backtest-data.md#how-coverage-is-judged)); a seam line also says how many weekdays lie
  between two sources.
- A backtest never deletes an imported tick day to refetch it from Dukascopy, and says when its window
  mixes imported and fetched days.
- `qkt data compare EXNESS:XAUUSD --against MT5:XAUUSD --from 2024-09-01 --to 2024-09-30` measures an
  import against your venue's own bars (`qkt fetch` them first): the clock offset, the price difference
  in points, and the bars missing on either side. `identical` and `matches` (within two points on the same
  clock) exit 0; `differs` exits 1.
