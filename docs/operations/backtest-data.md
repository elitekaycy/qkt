# Backtest data

`qkt backtest <strategy> --from <date> --to <date>` gets its own market data — no separate
download step, and no broker or gateway running.

## How it works

1. The symbols to fetch are read from the strategy's `SYMBOLS` block.
2. Any missing days are downloaded from dukascopy (free, public tick data) into
   `~/.qkt/data/symbols/<SYMBOL>/<day>.csv.gz`. Days already on disk are reused.
3. Coverage is checked against the symbol's trading calendar, hour by hour. A missing trading
   day — or a gap during an active session hour — counts as a hole.
4. On a hole, the backtest refuses to run and lists the gaps, rather than producing a
   clean-looking result from incomplete data. Re-run with `--allow-incomplete` to proceed anyway.

## Flags

- `--no-fetch` — use only what's already cached (still validated; fails on holes).
- `--allow-incomplete` — run despite missing/incomplete days; prints what is being ignored.

The legacy `--fetcher dukascopy --fetcher-script <path>` still works for a custom downloader, and
takes precedence when set.

## What's covered

Dukascopy provides FX majors and metals (for example `XAUUSD`, `EURUSD`). A symbol with no
mapping fails fast with a clear message rather than guessing — add it to `DukascopyInstrument` if
you need it.

## Contract specs (lot size, commission)

To turn lots into dollars, the backtest needs each instrument's contract specs — how big one lot
is (gold = 100 oz, an FX major = 100,000 units), the lot step, and price precision. Live trading
gets these from the broker automatically; a backtest has no broker, so qkt ships them.

- **Built in, no setup.** For every FX major and metal it can fetch, qkt knows the standard specs,
  so `qkt backtest <gold strategy>` sizes and prices correctly out of the box.
- **`instruments.yaml` is an override, not a requirement.** Drop a file at `<data-root>/instruments.yaml`
  (or pass `--instruments <path>`) to override any symbol — that's where you set the broker's
  **commission** (built-in default is zero) or add a symbol the standard table doesn't cover. Each
  entry is keyed by the broker-prefixed symbol from your strategy, e.g. `BACKTEST:XAUUSD`:

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

  A symbol present in the file wins; anything you omit falls back to the built-in specs.

## Good to know

- Dukascopy is an independent feed, not your exact broker's ticks — its prices and spreads differ
  slightly from any one venue. It's the standard answer to "does my strategy logic hold up on
  realistic ticks"; broker-exact data is a separate, future option.
- Completeness is judged per session hour: a trading day must have ticks through its active hours
  (the very first and last hour of a session are treated leniently, since they can be thin at the
  open and close).
