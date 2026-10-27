# Trade stock CFDs on an MT5 broker

A stock CFD is a contract on a share's price: no ownership, financed overnight, dividends paid or charged as cash.
qkt trades them through the same MT5 connector as FX and metals. This page uses Exness, measured on its demo
account (2026-10); another broker works the same way with its own numbers.

## What the venue says

```console
$ qkt instruments pull --config qkt.config.yaml --symbols EXNESS:AAPL,EXNESS:FB --out data/instruments.yaml
```

```yaml
  - qktSymbol: EXNESS:AAPL
    contractSize: 100.0      # 1 lot = 100 shares; volumeMin 0.01 = 1 share
    volumeStep: 0.01
    volumeMin: 0.01
    volumeMax: 10.0
    pointSize: 0.01
    digits: 2
    swapLongPoints: -6.1     # financing, both sides pay
    swapShortPoints: -5.3
    swapTripleDay: FRIDAY    # three nights booked on Friday, not FX's Wednesday
    category: Stocks         # Exness files it under Standard\Stocks\
  - qktSymbol: EXNESS:FB
    tradeMode: close_only    # Facebook's old ticker; Meta trades as EXNESS:META
```

Exness keeps symbols it no longer trades in its list: `FB` is close-only, `ATVI`, `BBBY` and `BRQS` are disabled
with quotes frozen in 2023. qkt refuses an opening order on them before the venue does, in backtest and live
(`EXNESS:FB is close_only at the venue: opening BUY refused`), and lets an existing position close.

## Hours

Exness quotes US stock CFDs **06:00-15:45 New York** on NYSE trading days, **06:00-13:00** on the exchange's
early-close days, and not at all on NYSE holidays. The `exness` profile gives every symbol in its `Stocks` folder
that session (`nyse[06:00-15:45]`), so nothing needs configuring:

```console
$ qkt calendars my_stocks.qkt --config qkt.config.yaml
symbol       calendar            source
EXNESS:AAPL  nyse[06:00-15:45]   exness calendars 'category:Stocks'
```

A `DAY` order expires at that session's end (15:45 New York), and one placed after the close lives through the
next session.

## Sizing

Sizing reads the contract size, so dollars and risk turn into whole shares:

```qkt
STRATEGY stocks VERSION 1
SYMBOLS
    nvda = EXNESS:NVDA EVERY 1h
RULES
    WHEN nvda.close > sma(nvda.close, 20) AND POSITION.nvda = 0
    THEN BUY nvda SIZING 500 USD                       -- $500 at $220 = 2 shares (0.02 lot)
    WHEN nvda.close < sma(nvda.close, 20) AND POSITION.nvda > 0
    THEN CLOSE nvda
```

`SIZING RISK $ 5 BRACKET { STOP LOSS BY 2.5, ... }` is 2 shares too: $5 / ($2.50 x 100 shares a lot).

## Backtest data

Dukascopy has no single stocks. Capture the venue's own ticks and bars through the gateway, then import them:

```console
$ qkt fetch EXNESS:AAPL --tf 1m --from 2026-09-01 --to 2026-10-26 --config qkt.config.yaml
$ qkt data import ticks/AAPL --as EXNESS:AAPL --kind ticks --map time=time_ms --map bid=bid --map ask=ask \
    --time-format epoch:ms --digits 2
```

Coverage is judged by the stock session, so NYSE holidays count as closed days, not missing data. Exness keeps
about eight months of ticks and minute bars for its stocks, and hourly bars back to 2025.

## Not modelled yet

Dividend adjustments in backtests (live, a venue's dividend booking reaches Insights as an `ADJUSTMENT` deal of
the position's strategy) and corporate actions such as splits. See the equities plan,
`docs/superpowers/plans/2026-10-27-equities.md`.
