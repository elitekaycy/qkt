# Risk-managed strategy

Trading with discipline isn't optional. This example combines per-trade ATR sizing (so every trade risks the same dollar amount) with account-level safeguards (daily-loss halt, drawdown cap). It's the kind of risk infrastructure that turns a backtest into something you'd trust live.

## What it does

- **Trades** EURUSD on 15-minute candles
- **Entries** on a simple trend filter: long when 20-EMA is above 50-EMA and price pulls back to the 20-EMA
- **Per-trade risk**: each entry risks exactly 1% of equity. Position size is calculated from `riskUsd / stopDistance`.
- **Account-level halts** (configured in `qkt.config.yaml`):
  - Halt all trading if daily loss exceeds 3% of starting equity
  - Halt permanently if drawdown exceeds 10%
  - Block any single trade larger than 5% of equity

This is what professional risk discipline looks like on paper. The trading logic is intentionally simple — the **infrastructure around it** is the point.

## The strategy file

```qkt title="strategies/risk-managed.qkt"
STRATEGY risk_managed VERSION 1

LET fastPeriod = 20
LET slowPeriod = 50

SYMBOLS
    eur = BACKTEST:EURUSD EVERY 15m

LET fastMa = ema(eur.close, fastPeriod)
LET slowMa = ema(eur.close, slowPeriod)

RULES
    -- only enter in long-term uptrend
    -- on a pullback close to the fast MA
    WHEN fastMa > slowMa
     AND eur.close > fastMa
     AND eur.low <= fastMa             -- touched the MA this bar
     AND position(eur) = 0
    THEN BUY eur SIZING 1.0 PCT RISK
         STOP_LOSS AT eur.close - atr(eur, 14) * 2
         TAKE_PROFIT AT eur.close + atr(eur, 14) * 6   -- 3R target
         LOG INFO "pullback long" risk_pct=1.0 atr=atr(eur, 14)

    -- exit if the trend breaks
    WHEN fastMa < slowMa
     AND position(eur) > 0
    THEN CLOSE eur
         LOG INFO "trend broken — exit"
```

Key points:

- `SIZING 1.0 PCT RISK` — risk-based sizing. The engine computes position size from `(equity * 0.01) / stop_distance`. If equity is $10,000 and the stop is $50 away from entry, position size = $10,000 * 0.01 / $50 = $2 (in quote currency for FX).
- `STOP_LOSS AT eur.close - atr(eur, 14) * 2` — stop at 2× ATR below entry. Adapts to volatility.
- `TAKE_PROFIT AT eur.close + atr(eur, 14) * 6` — 6× ATR target = 3× the risk (3R reward/risk).

## The risk config

```yaml title="qkt.config.yaml"
source: backtest
data_root: ~/.qkt/data
starting_balance: 10000

risk:
  rules:
    - type: max-daily-loss
      pct: 3.0                # halt all trading on -3% intraday
      reset: daily            # auto-clears at UTC midnight

    - type: max-drawdown
      pct: 10.0               # permanent halt at -10% peak-to-trough
      reset: manual           # operator must explicitly resume

    - type: max-position-pct
      pct: 5.0                # reject any single trade > 5% of equity

    - type: max-open-positions
      count: 3                # daemon-wide limit across all strategies
```

Risk rules apply across **every strategy hosted by the daemon**. If you deploy three different `.qkt` files and any one of them triggers the daily-loss halt, all three stop trading.

## How to run it

```bash
qkt fetch EURUSD --from 2024-01-01 --to 2024-04-01
qkt backtest strategies/risk-managed.qkt \
    --config qkt.config.yaml \
    --from 2024-01-01 --to 2024-04-01
```

## What to expect

```text
Trades:                42
Final realized:         420.50
Win rate:               0.428
Sharpe (daily):         1.21
Max drawdown:          -185.00
Avg risk per trade:     $99.80     (~1% of starting equity)
Halts triggered:        2          (max-daily-loss, both reset overnight)
```

Notice the **avg risk per trade** is stable at ~$100 even though position sizes vary wildly (FX during high-volatility news vs quiet sessions). That's the ATR-based sizing doing its job — each trade is the same expected $ loss if it stops out.

## How to adapt it

### Larger / smaller per-trade risk

```qkt
BUY eur SIZING 2.0 PCT RISK         -- 2% per trade
-- or
BUY eur SIZING 0.5 PCT RISK         -- 0.5% per trade
```

The Kelly-optimal sizing is typically 0.5–1% for most retail strategies. Anything above 2% is aggressive and produces volatile equity curves.

### Tighter daily-loss halt

```yaml
- type: max-daily-loss
  pct: 1.5
  reset: daily
```

This kicks you out of the market faster on bad days. The trade-off: more halts means more missed recovery rallies. Tune to your stomach.

### Per-strategy halts (instead of daemon-wide)

```yaml
risk:
  per_strategy:
    - type: max-trades-per-day
      count: 5
    - type: cooloff-after-loss
      duration: 1h        # don't enter a new trade for 1h after a loss
```

These rules apply per-strategy, not across the daemon. Useful when one strategy is misbehaving but you want others to keep running.

### Manual resume after a drawdown halt

When the `max-drawdown` rule fires with `reset: manual`, the daemon will refuse to trade until an operator clears it:

```bash
qkt risk-status                     # shows active halts
qkt risk-resume risk_managed        # clear the halt for this strategy
```

This is the kill-switch by design. Don't auto-resume drawdown halts — investigate why before reactivating.

## Common gotchas

- **Risk sizing depends on the stop being set correctly.** If your strategy uses `SIZING X PCT RISK` but doesn't declare a stop, the engine rejects the order (it can't compute size). Always pair risk sizing with an explicit `STOP_LOSS`.
- **ATR depends on warmup.** First 14 bars produce no ATR; the rule won't fire. Add `WARMUP 14 BARS` or let the implicit warmer handle it.
- **Halts persist across daemon restarts.** `reset: manual` halts survive a daemon restart — the state file in `~/.qkt/state/` remembers them. Use `qkt risk-resume` to clear.
- **Max-position-pct can be too tight.** If your strategy uses `SIZING 1.0 PCT RISK` but the stop is very tight (small denominator), the computed size may exceed `max-position-pct`. The engine rejects the trade. Either widen the stop or relax the cap.

## What this example demonstrates

- Risk-based sizing (`SIZING N PCT RISK`)
- Bare `STOP_LOSS` + `TAKE_PROFIT` (alternative to `BRACKET`)
- Risk-rule configuration in `qkt.config.yaml`
- Daemon-wide vs per-strategy halts
- The `LET` keyword used to alias an indicator for reuse in multiple conditions
- The relationship between trade-level risk (DSL) and account-level risk (config)
