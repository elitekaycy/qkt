# Regime-gated portfolio

Three strategies — trend-follow, mean-revert, breakout — hosted in one `PORTFOLIO` file. A regime detector picks the right one for current market conditions; the others sleep until their regime returns. This is the simplest form of strategy composition in qkt.

## What it does

- **Three child strategies**, each appropriate for a different market regime
- **A regime detector** based on ADX(14), a trend-strength indicator, decides which one is active
- **Only one strategy trades at a time** — the others have positions closed when they deactivate
- **Daemon-level fan-out** — each child gets its own observability port, log file, and risk slice

This is the simplest example of qkt's portfolio composition. More sophisticated patterns combine children that run concurrently (different assets, different timeframes) or weight allocations by regime.

## The strategy files

### The three children

```qkt title="strategies/trend.qkt"
STRATEGY trend VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1h
RULES
    WHEN ema(btc.close, 12) CROSSES ABOVE ema(btc.close, 48) AND POSITION.btc = 0
    THEN BUY btc SIZING 0.05 BRACKET { STOP_LOSS BY 200, TAKE_PROFIT BY 600 }
    WHEN ema(btc.close, 12) CROSSES BELOW ema(btc.close, 48) AND POSITION.btc > 0
    THEN CLOSE btc
```

```qkt title="strategies/meanrev.qkt"
STRATEGY meanrev VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1h
RULES
    WHEN rsi(btc.close, 2) < 10 AND POSITION.btc = 0
    THEN BUY btc SIZING 0.05 BRACKET {
           STOP_LOSS AT btc.close - atr(btc, 14) * 2,
           TAKE_PROFIT AT btc.close + atr(btc, 14) * 2
         }
```

```qkt title="strategies/breakout.qkt"
STRATEGY breakout VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1h
RULES
    WHEN btc.close > highest(btc.close, 20) AND POSITION.btc = 0
    THEN BUY btc SIZING 0.05 BRACKET {
           STOP_LOSS AT btc.close - atr(btc, 14) * 2,
           TAKE_PROFIT AT btc.close + atr(btc, 14) * 20
         }
    WHEN btc.close < lowest(btc.close, 10) AND POSITION.btc > 0
    THEN CLOSE btc
```

The breakout child exits on its lowest-low rule. A bracket always carries both a stop and a
target, so its target sits 20 ATRs away as a backstop.

### The portfolio file

```qkt title="strategies/portfolio.qkt"
PORTFOLIO btc_regimes VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1h

IMPORT 'trend.qkt'     AS trend
IMPORT 'meanrev.qkt'   AS meanrev
IMPORT 'breakout.qkt'  AS breakout

RULES
    -- strong trend, either direction: run the trend-follower
    WHEN adx(btc.candle, 14) > 30  RUN trend

    -- weak trend / ranging: run mean-reversion
    WHEN adx(btc.candle, 14) < 20  RUN meanrev

    -- transitional: run breakout
    WHEN adx(btc.candle, 14) BETWEEN 20 AND 30  RUN breakout
```

A portfolio has no `LET` or `DEFAULTS` block, so the regime test is written into each rule.
ADX is direction-blind: it rises in strong trends either way. Adjust the thresholds to your asset;
what counts as a strong trend on BTC at 1h isn't the same as on gold at 5m.

## How to run it

```bash
qkt parse strategies/portfolio.qkt
qkt backtest strategies/portfolio.qkt --from 2026-01-02 --to 2026-09-15 --no-fetch
```

Or in the daemon:

```bash
qkt daemon &
qkt deploy strategies/portfolio.qkt --as btc-regimes
qkt list
```

`qkt list` shows the portfolio row and one row per child, each with its own port for `/status` and
its own log file. After editing the portfolio file, run
`qkt resync strategies/portfolio.qkt --as btc-regimes --dry-run` and then
`qkt resync strategies/portfolio.qkt --as btc-regimes` to replace the whole
portfolio entry under the same name.

## What to expect

qkt 0.56.2, `BTCUSD` ticks from 2 January to 14 September 2026, the default paper broker and a
10,000 USD account:

```text
Backtest   2026-01-02 to 2026-09-15 (257 trading days)
Account    10,000.00 USD

Result
  Profit             +42.16 USD   (+0.4%)
  Round trips        196 closed, 84 won (42.9%)
  Open at the end    none
  Worst drop         6.3%    biggest fall from a peak
  Worst day          1.1%
  Sharpe ratio       0.13     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

The report's `result.json` splits it by child under `perStrategy` (`tradeCount` counts fills, two
per round trip):

| Child | `realizedTotal` (USD) | `tradeCount` | `winRate` |
|---|---|---|---|
| `btc_regimes:trend` | 12.85950000 | 14 | 0.28571429 |
| `btc_regimes:meanrev` | -333.63300000 | 186 | 0.39784946 |
| `btc_regimes:breakout` | 362.93200000 | 192 | 0.46875000 |

The three add up to the 42.16 USD total: the trend child traded 7 times, and mean-reversion lost
what breakout made.

## How to adapt it

### Regime detector based on something other than ADX

Relative volatility (ATR as a fraction of price):

```qkt
RULES
    WHEN atr(btc.candle, 14) / btc.close > 0.01   RUN volStrategy
    WHEN atr(btc.candle, 14) / btc.close < 0.005  RUN quietStrategy
```

Or the relationship between two moving averages:

```qkt
RULES
    WHEN sma(btc.close, 20) / sma(btc.close, 100) > 1.02  RUN bullStrategy
    WHEN sma(btc.close, 20) / sma(btc.close, 100) < 0.98  RUN bearStrategy
    WHEN sma(btc.close, 20) / sma(btc.close, 100) BETWEEN 0.98 AND 1.02  RUN neutralStrategy
```

For named states shared by several rules, and weights instead of on/off switching, use `REGIMES`
and `ALLOCATE`; see [PORTFOLIO files](../reference/dsl/portfolio.md#regime-weighted-allocation).

### Run children concurrently (multi-asset)

If your children trade different symbols, you may want all of them running at once:

```qkt
PORTFOLIO multi_asset VERSION 1

IMPORT 'btc_strat.qkt'  AS btcChild
IMPORT 'gold_strat.qkt' AS goldChild

RULES
    RUN btcChild      -- no WHEN: always on
    RUN goldChild
```

Each child runs in parallel; daemon-level risk rules still apply across them all.

### `HOLD` mode for children with positions

By default, when a regime gate goes false, the child's open position is **closed at market**. To keep the position (let it close naturally via the child's own exit rules):

```qkt
IMPORT 'trend.qkt'    AS trend HOLD
```

Use `HOLD` when child strategies have long-horizon positions you don't want to flush on every regime flip.

### Operator stop and start for one child

Take one child out of rotation without touching the others, then put it back:

```bash
qkt stop btc-regimes/meanrev
qkt start btc-regimes/meanrev
```

`qkt start` clears the operator-stop flag on the child; it does not override the regime gate. The
child trades again the next time its `RUN` rule is true.

## Common gotchas

- **Regime detectors are themselves indicators with warmup.** ADX needs about 2×14 bars. Until then, all branch conditions are null/false and no child is active. Add an ungated `RUN someDefault` rule if you want to always have something running.
- **Children share the symbol declaration.** All three children in this example trade the same BTCUSD 1h stream. The portfolio's SYMBOLS block dominates; children inherit. If you need different symbols per child, declare them in the child files and don't reference them in the portfolio rules.
- **Risk halts apply daemon-wide.** A child triggering `max-daily-loss` halts the whole portfolio (and every other strategy in the daemon). This is by design — risk is account-level.
- **Switching children flat-closes by default.** A trend child that's holding a position when the regime flips will get its position closed at market when `meanrev` takes over. Use `HOLD` if that's not what you want.

## What this example demonstrates

- The `PORTFOLIO` file form with `IMPORT ... AS ... [HOLD]`
- Regime-gated child activation via `RUN` rules
- `BETWEEN` operator on indicator values
- The relationship between portfolio rules and child rules
- Daemon fan-out (one row per child in `qkt list`)
