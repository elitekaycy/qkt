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

## Good to know

- Dukascopy is an independent feed, not your exact broker's ticks — its prices and spreads differ
  slightly from any one venue. It's the standard answer to "does my strategy logic hold up on
  realistic ticks"; broker-exact data is a separate, future option.
- Completeness is judged per session hour: a trading day must have ticks through its active hours
  (the very first and last hour of a session are treated leniently, since they can be thin at the
  open and close).
