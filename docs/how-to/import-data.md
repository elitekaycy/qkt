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
| `mt5-bars`, `mt5-ticks` | MetaTrader 5 Symbols window export (UTF-16, tab-separated, broker server time) |
| `mt4-csv` | MetaTrader 4 History Center CSV |
| `histdata-m1`, `histdata-ticks`, `histdata-mt` | HistData.com (EST all year, no DST) |
| `dukascopy-gmt-bars`, `dukascopy-local-bars`, `dukascopy-gmt-ticks` | Dukascopy website / JForex export |
| `dukascopy-node-bars`, `dukascopy-node-ticks` | dukascopy-node CSV |
| `tradingview-unix`, `tradingview-iso` | TradingView chart export |
| `binance-klines`, `binance-klines-header`, `binance-klines-json` | Binance public data and REST klines (ms, and µs for spot since 2025) |
| `binance-funding`, `binance-funding-api`, `binance-metrics` | Binance funding rates and 5-minute open interest |
| `bybit-trades`, `bybit-spot-trades`, `okx-candles`, `okx-trades`, `kraken-ohlcvt` | exchange archives |
| `truefx-ticks` | TrueFX |
| `yfinance-daily`, `yfinance-intraday`, `yfinance-multi` | Yahoo Finance via yfinance |
| `alphavantage-intraday`, `alphavantage-daily` | Alpha Vantage CSV |
| `polygon-aggs-flat`, `polygon-aggs-json` | Polygon (Massive) flat files and REST aggregates |
| `oanda-candles`, `ccxt-ohlcv` | OANDA v20 candles, ccxt `fetch_ohlcv` dumps |
| `qkt-cot` | COT tables with a `visible_session` column |

Files can be plain, `.gz` or `.zip` (every data member is read; checksum and report members are skipped),
or a directory of them.

## Any other source

Describe the file once with flags, check it with `--dry-run`, then save the description with `--plan`:

```bash
qkt data import broker.csv --as MYBROKER:EURUSD --kind bars \
  --map time=Date+Time,open=Open,high=High,low=Low,close=Close,volume=Vol \
  --time-format 'dd/MM/yyyy HH:mm' --tz ny-close --dry-run

qkt data import broker.csv --as MYBROKER:EURUSD --kind bars --map ... --plan > mybroker.yaml
qkt data import next-month.csv --as MYBROKER:EURUSD --preset mybroker.yaml
```

- `--map field=column`: columns by header name (spaces allowed), `$N` by position, `a.b` inside JSON.
  Expressions are exact: `close=<CLOSE>+<SPREAD>*point/2`. A trailing `?` makes a column optional.
- Fields: ticks `bid ask price volume bidVolume askVolume`; bars `open high low close volume spread`;
  funding `rate price`; open interest `openInterest`; series `value`.
- `--time-format`: `epoch` (the unit is read from each file), `epoch:s|ms|us|ns`, `iso`, or a `java.time`
  pattern (`yyyy.MM.dd HH:mm:ss`, `dd.MM.yyyy HH:mm:ss.SSS 'GMT'xx`). Without it qkt tries the common
  ones and refuses when two fit (`03/04/2024` could be March or April).
- `--tz`: `UTC`, a fixed offset (`+02:00`), `ny-close`, or an IANA zone (`Europe/Athens`).
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
  qkt: the weekly opens contradict the clock (stated by --tz): Europe/Athens: 51 weekly opens,
       47 at 17:00 New York, 4 elsewhere. Clocks that fit: ny-close.
  ```

  With no `--tz`, an FX file whose opens fit exactly one candidate clock is read on it, and the report
  says so. Add `--trust-clock` only when the source really does open at an unusual time.
- A wall-clock time inside a DST gap (the clock skipped it) is refused. Inside the repeated autumn hour
  the file's own order decides which pass it is.
- Crypto and other round-the-clock symbols carry no such evidence: naive times need `--tz`.

## What it refuses, and why

| Refusal | Why |
|---|---|
| ambiguous delimiter, decimal comma or date order | a wrong guess changes prices or dates silently |
| a time unit that changes within a file, an epoch outside 1990 to now | a wrong unit puts bars in the wrong decade |
| out-of-order rows (unless `--sort`), tick files overlapping in time | ties and duplicates could not be resolved deterministically |
| two different bars at one time, a bar whose high is below its close | the source contradicts itself |
| bars off the UTC grid, e.g. broker H4 or D1 bars cut at broker midnight | qkt's bars are cut at UTC; import H1 or finer and let qkt build H4 and D1 |
| a `series` without `--available` or `--available-lag` | stamping a published value on the date it describes leaks the future |
| days already stored by something else | use `--on-overlap skip` or `--on-overlap replace` (the old files go to `.import-backup/`) |
| binary files (`.bi5`, Parquet, `.hst`, `.scid`, DBN) | export them to CSV first |

Every refusal happens before the first write, so a refused import leaves the store as it was.

Some things are fixed or flagged rather than refused, and the report counts each:

- exact duplicate bars and crossed quotes are dropped (qkt's reader drops crossed quotes anyway);
- price spikes that jump and immediately revert are flagged and kept;
- digits past the eighth decimal are rounded to qkt's precision;
- bid bars without a spread column are stored as they are, with a note that qkt reads bar prices as mid.

## After importing

- Re-importing the same files the same way changes nothing (`unchanged` days).
- Every target directory has `.import.json`, recording the input files' SHA-256, the preset, the clock
  and its evidence, and the counts. `qkt data verify <SYMBOL>` shows which days were imported.
- A backtest never deletes an imported tick day to refetch it from Dukascopy, and says when its window
  mixes imported and fetched days.
- `qkt data compare EXNESS:XAUUSD --against MT5:XAUUSD --from 2024-09-01 --to 2024-09-30` measures an
  import against your venue's own bars (`qkt fetch` them first): the clock offset, the price difference
  in points, and the bars missing on either side. `identical` and `matches` (within two points on the same
  clock) exit 0; `differs` exits 1.
