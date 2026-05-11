# Run a parameter sweep

Grid-search over your strategy's parameters to find what worked on historical data — then walk-forward to check it generalizes.

!!! warning "Overfitting is real"
    A sweep with 100 parameter combos and 50 trades per combo will find ~5 winners by chance alone. Walk-forward validation is mandatory before you trust any sweep result. See [Phase 10c — Walk-forward](../phases/phase-10c-walk-forward.md) for the proper protocol.

## 1. Parameterize your strategy

Use `LET` clauses for anything you want to sweep:

```qkt title="strategies/ema-cross.qkt"
STRATEGY ema_cross VERSION 1

LET fast = 9
LET slow = 21
LET stopPct = 1.0

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
    THEN BUY btc SIZING 0.1
         BRACKET { STOP_LOSS BY stopPct PCT, TAKE_PROFIT BY stopPct * 3 PCT }
```

## 2. Run the sweep

```bash
qkt sweep strategies/ema-cross.qkt \
    --from 2024-01-01 --to 2024-06-01 \
    --param "fast=5,9,12,15" \
    --param "slow=20,25,30,40" \
    --param "stopPct=0.5,1.0,2.0" \
    --output reports/sweep
```

This runs `4 × 4 × 3 = 48` backtests. With default parallelism it'll take a few minutes on a laptop.

To bound parallelism (e.g., on a constrained machine):

```bash
qkt sweep ... --parallel 4
```

## 3. Read the output

Two files land in `reports/sweep/`:

- **`summary.html`** — ranked table of all runs by Sharpe, with sortable columns
- **`summary.csv`** — same data, machine-readable

The summary ranks by Sharpe ratio by default. Other useful columns:

| column | what it means |
| --- | --- |
| `params` | The parameter combo for this run |
| `tradeCount` | How many trades fired — discard anything < 30 (statistically unreliable) |
| `winRate` | Fraction of trades that closed profitable |
| `sharpe` | Annualized Sharpe |
| `calmar` | Annualized return / max drawdown |
| `maxDD` | Worst peak-to-trough drawdown |
| `pf` | Profit factor (gross profit / gross loss) |

## 4. Walk-forward validate

A high in-sample Sharpe means nothing if the winning params crash on out-of-sample data. Run walk-forward:

```bash
qkt walkforward strategies/ema-cross.qkt \
    --from 2024-01-01 --to 2024-06-01 \
    --param "fast=5,9,12,15" \
    --param "slow=20,25,30,40" \
    --train-days 60 \
    --test-days 14 \
    --output reports/walkforward
```

What this does:

1. Optimizes params on days 1–60 (training window) using the sweep harness
2. Forward-tests the winner on days 61–74 (test window) — out-of-sample
3. Rolls the windows forward by `test-days` and repeats
4. Aggregates the test-window results into a "true" performance estimate

The honest performance is whatever the test windows show. Anything that only looks good in training is overfit and will likely lose money live.

## 5. Pick a winner

A robust winner has:

- **Trade count ≥ 100** across the full period (statistical confidence)
- **Walk-forward Sharpe ≥ 1.0** on out-of-sample windows
- **Walk-forward Sharpe / in-sample Sharpe ≥ 0.5** (degradation is normal, but >50% drop is a red flag)
- **Max drawdown your account can survive** (your risk tolerance, not the strategy's preference)

If multiple combos qualify, pick the one with the **simplest** params (fewer free variables = harder to overfit).

## 6. Lock the winner in

Hard-code the winning params back into the strategy file:

```qkt
LET fast = 12
LET slow = 26
LET stopPct = 1.5
```

Re-run a full backtest with the locked params to confirm. Then [deploy paper](deploy-exness.md) and watch live behaviour for at least 2 weeks before going anywhere near a funded account.

## Performance tips

- **Date range matters more than param count.** 5 years of data beats 5 params.
- **Use `EVERY 5m` or `EVERY 15m`** for sweeps; 1-minute candles produce 5–15× more data without much added signal.
- **Sweep the right things.** Indicator periods, stop/target ratios, regime filters. Don't sweep things like `LET symbol = "BTCUSDT"` — you're not deciding which market to trade, you're decision-tree-fitting.

## See also

- [Phase 10b — Parameter sweep](../phases/phase-10b-parameter-sweep.md) — sweep internals
- [Phase 10c — Walk-forward](../phases/phase-10c-walk-forward.md) — the proper validation protocol
- [Backtest model](../concepts/backtest-model.md) — what the report numbers actually mean
