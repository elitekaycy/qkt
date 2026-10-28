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

Exness takes new positions in US stock CFDs **09:40-15:45 New York** on NYSE trading days, **09:40-13:00** on the
exchange's early-close days, and not at all on NYSE holidays. It quotes them from 06:00, but until 09:40 it is
close-only: an opening order there is refused with MT5 retcode 10044 ("session closed"). The `exness` profile gives
every symbol in its `Stocks` folder that session (`nyse[09:40-15:45]`), so nothing needs configuring. A strategy still
sees the pre-open bars; an order it sends before 09:40 is refused, in a backtest as live
([trading only in session](../concepts/trading-calendars.md#trading-only-in-session)):

```console
$ qkt calendars my_stocks.qkt --config qkt.config.yaml
symbol       calendar            source
EXNESS:AAPL  nyse[09:40-15:45]   exness calendars 'category:Stocks'
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

## Dividends and splits

Backtests read a share's corporate actions from `<data root>/corporate-actions/<VENUE>/<NAME>.csv`; daemon and
`qkt bot` live sessions read the same file under their config's `data_root` (`qkt run` reads no data-root
instrument files, so it has none):

```text
ex_date,kind,value,currency,pay_date
2026-06-08,dividend,0.25,USD,2026-06-26
2026-06-09,split,10,,
```

- **Dividend:** at the start of the ex-date's session, every strategy holding the share from before then is credited
  `quantity x contract size x amount` when long and debited it when short, the way a CFD broker books it. The run
  reports it as its own line, `dividends`, beside swap and funding. Live, the venue books it, and its deal reaches
  Insights as an `ADJUSTMENT`.
- **Split:** a backtest whose window holds a split reads the share's history split-adjusted: every price before the
  ex-date session is divided by the ratio (10 for a 10-for-1, 0.125 for a 1-for-8 reverse split) and volume multiplied
  by it, so indicators, stops and fills all read one series in the units the share trades at the run's end. A
  dividend paid before the split is per adjusted share. A run that ends before the split reads raw prices. A position
  sized as a fixed lot count before a split holds fewer real shares than the same lots would buy afterwards: size
  stocks by money or risk (`SIZING 500 USD`, `SIZING RISK`) when a backtest spans a split, which then differs from
  raw only by lot rounding.
- **No new position into a split:** from four calendar days before a split's ex-date through that date, opening orders
  on the share are refused, in backtests and live alike; closing orders pass. qkt does not restate a live position
  for a split the venue applies, so flatten a position you hold before the window, or expect the reconciler to flag
  the venue's new quantity.

Two actions of one kind on one ex-date are refused, as are a non-positive split ratio and a dividend in a currency
other than the share's.

## Not modelled yet

Restating a live position or resting order for a split, and reading the actions from a venue (Alpaca reports them)
rather than the data root. See the equities plan, `docs/superpowers/plans/2026-10-27-equities.md`.
