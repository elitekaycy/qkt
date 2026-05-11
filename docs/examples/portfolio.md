# Regime-gated portfolio

Three strategies — trend-follow, mean-revert, breakout — hosted in one `PORTFOLIO` file. A regime detector picks the right one for current market conditions; the others sleep until their regime returns. This is the simplest form of strategy composition in qkt.

## What it does

- **Three child strategies**, each appropriate for a different market regime
- **A regime detector** based on ADX (trend strength) decides which one is active
- **Only one strategy trades at a time** — the others have positions closed when they deactivate
- **Daemon-level fan-out** — each child gets its own observability port, log file, and risk slice

This is the simplest example of qkt's portfolio composition. More sophisticated patterns combine children that run concurrently (different assets, different timeframes) or weight allocations by recent performance.

## The strategy files

### The three children

```qkt title="strategies/trend.qkt"
STRATEGY trend VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
RULES
    WHEN ema(btc.close, 12) CROSSES ABOVE ema(btc.close, 48)
    THEN BUY btc SIZING 0.05 BRACKET { STOP_LOSS BY 200, TAKE_PROFIT BY 600 }
    WHEN ema(btc.close, 12) CROSSES BELOW ema(btc.close, 48)
    THEN CLOSE btc
```

```qkt title="strategies/meanrev.qkt"
STRATEGY meanrev VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
RULES
    WHEN rsi(btc.close, 2) < 10 AND position(btc) = 0
    THEN BUY btc SIZING 0.05 BRACKET {
           STOP_LOSS AT btc.close - atr(btc, 14) * 2,
           TAKE_PROFIT AT btc.close + atr(btc, 14) * 2
         }
```

```qkt title="strategies/breakout.qkt"
STRATEGY breakout VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
RULES
    WHEN btc.close > highest(btc.close, 20) AND position(btc) = 0
    THEN BUY btc SIZING 0.05 STOP_LOSS AT btc.close - atr(btc, 14) * 2
    WHEN btc.close < lowest(btc.close, 10) AND position(btc) > 0
    THEN CLOSE btc
```

### The portfolio file

```qkt title="strategies/portfolio.qkt"
PORTFOLIO btc_regimes VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

IMPORT 'trend.qkt'     AS trend
IMPORT 'meanrev.qkt'   AS meanrev
IMPORT 'breakout.qkt'  AS breakout

LET adxValue = adx(btc, 14)

RULES
    -- strong trend: run the trend-follower
    WHEN adxValue > 30  RUN trend

    -- weak trend / ranging: run mean-reversion
    WHEN adxValue < 20  RUN meanrev

    -- transitional / breakout candidate: run breakout
    WHEN adxValue BETWEEN 20 AND 30  RUN breakout
```

`ADX` (Average Directional Index) measures trend strength on a 0–100 scale. Above 30 = strong trend. Below 20 = ranging. The portfolio rules pick the right child for each regime.

## How to run it

```bash
qkt fetch BTCUSDT --from 2024-01-01 --to 2024-06-01 --resolution 1h
qkt backtest strategies/portfolio.qkt --from 2024-01-01 --to 2024-06-01
```

Or in the daemon:

```bash
qkt daemon &
qkt deploy strategies/portfolio.qkt --as btc-regimes
qkt list
# Output:
#   NAME              KIND       PORT     TRADES   STATE
#   btc-regimes       portfolio  47291    -        running
#     trend           child      47292    8        active
#     meanrev         child      47293    23       inactive
#     breakout        child      47294    5        inactive
```

Each child appears as its own row, with its own port for `/status` and its own log file. The portfolio row aggregates.

## What to expect

A 6-month BTC backtest under all three regimes typically produces:

```text
Strategy: btc_regimes
Trades:         36   (sum across all children)
Final realized: 1,840.00
Sharpe:         1.42
Max drawdown:   -315.00

  Child:  trend        trades=8   pnl=+1,120  active_pct=42%
  Child:  meanrev      trades=23  pnl=+340    active_pct=38%
  Child:  breakout     trades=5   pnl=+380    active_pct=20%
```

`active_pct` is the fraction of bars where the child was the active strategy. The numbers sum to ~100% (with small gaps for indicator warmup).

## How to adapt it

### Regime detector based on something other than ADX

Realized volatility (rolling stddev of returns):

```qkt
LET realVol = sqrt(252 * sum(pow(btc.close / btc.close[1] - 1, 2), 20))

RULES
    WHEN realVol > 0.6  RUN volStrategy
    WHEN realVol < 0.3  RUN quietStrategy
```

Or the relationship between two moving averages:

```qkt
LET maRatio = sma(btc.close, 20) / sma(btc.close, 100)

RULES
    WHEN maRatio > 1.02  RUN bullStrategy
    WHEN maRatio < 0.98  RUN bearStrategy
    WHEN maRatio BETWEEN 0.98 AND 1.02  RUN neutralStrategy
```

### Run children concurrently (multi-asset)

If your children trade different symbols, you may want all of them running at once:

```qkt
PORTFOLIO multi_asset VERSION 1

IMPORT 'btc_strat.qkt'  AS btcChild
IMPORT 'eur_strat.qkt'  AS eurChild
IMPORT 'gold_strat.qkt' AS goldChild

RULES
    WHEN TRUE  RUN btcChild      -- always on
    WHEN TRUE  RUN eurChild
    WHEN TRUE  RUN goldChild
```

Each child runs in parallel; daemon-level risk rules still apply across them all.

### `HOLD` mode for children with positions

By default, when a regime gate goes false, the child's open position is **closed at market**. To keep the position (let it close naturally via the child's own exit rules):

```qkt
IMPORT 'trend.qkt'    AS trend HOLD
```

Use `HOLD` when child strategies have long-horizon positions you don't want to flush on every regime flip.

### Operator manual override

Force a specific child active regardless of regime:

```bash
qkt start btc-regimes/meanrev
```

This clears the regime gate for that child and keeps it active until you stop it. Useful for testing one child in isolation.

## Common gotchas

- **Regime detectors are themselves indicators with warmup.** ADX(14) needs ~14 bars to produce values. Until then, no child is active. Add a fallback or extend the warmup.
- **Children share the symbol declaration.** All three children in this example trade the same BTCUSDT 1h stream. The portfolio's SYMBOLS block dominates; children inherit. If you need different symbols per child, declare them in the child files and don't reference them in the portfolio rules.
- **Risk halts apply daemon-wide.** A child triggering `max-daily-loss` halts the whole portfolio (and every other strategy in the daemon). This is by design — risk is account-level.
- **Switching children flat-closes by default.** A trend child that's holding a position when ADX drops below 30 will get its position closed at market when `meanrev` takes over. Use `HOLD` if that's not what you want.

## What this example demonstrates

- The `PORTFOLIO` file form with `IMPORT ... AS ... [HOLD]`
- Regime-gated child activation via `RUN` rules
- `BETWEEN` operator on indicator values
- The relationship between portfolio rules and child rules
- Daemon fan-out (one row per child in `qkt list`)
- Phase 13b + Phase 14 features in production form
