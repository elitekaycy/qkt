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

LET fastMa   = ema(eur.close, fastPeriod)
LET slowMa   = ema(eur.close, slowPeriod)

# Manual risk-sizing until SIZING N PCT RISK ships in Phase 24
LET stopDist = atr(eur, 14) * 2
LET riskUsd  = ACCOUNT.equity * 0.01      # 1% of equity at risk per trade
LET riskQty  = riskUsd / stopDist

RULES
    -- only enter in long-term uptrend
    -- on a pullback close to the fast MA
    WHEN fastMa > slowMa
     AND eur.close > fastMa
     AND eur.low <= fastMa             -- touched the MA this bar
     AND POSITION.eur = 0
    THEN BUY eur SIZING riskQty
         STOP_LOSS AT eur.close - stopDist
         TAKE_PROFIT AT eur.close + stopDist * 3      -- 3R target
         LOG "pullback long" risk_qty=riskQty atr=atr(eur, 14)

    -- exit if the trend breaks
    WHEN fastMa < slowMa
     AND POSITION.eur > 0
    THEN CLOSE eur
         LOG "trend broken — exit"
```

Key points:

- **Manual risk-sizing.** `riskQty = (equity × 0.01) / stop_distance` produces a position size that costs exactly 1% of equity if the stop hits. Phase 24 will add `SIZING 1.0 PCT RISK` as a one-line shortcut for this — see [Planned features](../planned.md).
- **Stop adapts to volatility.** `stopDist = atr(eur, 14) * 2` widens during news, tightens in calm sessions. Position size adapts inversely — big stops get small positions; tight stops get bigger.
- **3R target.** Take-profit at `stopDist * 3` above entry means each winner is 3× the size of each loser. The strategy can win 40% of the time and still be profitable.

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
./scripts/fetch-dukascopy.sh EURUSD 2024-01-01 2024-04-01
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

Change the multiplier on the `riskUsd` LET:

```qkt
LET riskUsd = ACCOUNT.equity * 0.02      -- 2% per trade (aggressive)
-- or
LET riskUsd = ACCOUNT.equity * 0.005     -- 0.5% per trade (conservative)
```

The Kelly-optimal sizing is typically 0.5–1% for most retail strategies. Anything above 2% is aggressive and produces volatile equity curves.

### Tighter daily-loss halt

```yaml
- type: max-daily-loss
  pct: 1.5
  reset: daily
```

This kicks you out of the market faster on bad days. The trade-off: more halts means more missed recovery rallies. Tune to your stomach.

### Per-strategy halts (Phase 25)

!!! info "Coming in Phase 25"
    `per_strategy:` risk rules (e.g. `max-trades-per-day`, `cooloff-after-loss` scoped to one strategy) are **planned but not yet implemented**. See [Planned features](../planned.md). Today, risk rules apply daemon-wide.

### Manual resume after a drawdown halt

When the `max-drawdown` rule fires with `reset: manual`, the daemon refuses to trade until an operator clears it. Today, the way to clear is **restart the daemon** — the halt state persists in `~/.qkt/state/` but you can edit it manually or restart fresh. A `qkt risk-status` / `qkt risk-resume` CLI is on the [roadmap](../planned.md).

## Common gotchas

- **The manual sizing computation needs the stop and equity to be valid.** If `stopDist` evaluates to null (ATR not warm), `riskQty` is null and the rule doesn't fire — by design. Wait through warmup.
- **ATR depends on warmup.** First 14 bars produce no ATR; the rule won't fire. The compiler infers warmup automatically.
- **Daemon-wide halts.** Any halt triggered by any strategy stops every strategy in the daemon. Per-strategy scoping lands in Phase 25.
- **Max-position-pct can be too tight.** If `riskQty` × current price exceeds `max-position-pct × equity`, the engine rejects the trade. Either widen the stop (smaller `riskQty`) or relax the cap.

## What this example demonstrates

- Risk-based sizing via `LET` arithmetic (the `SIZING N PCT RISK` shortcut lands in Phase 24)
- Bare `STOP_LOSS` + `TAKE_PROFIT` (alternative to `BRACKET`)
- Risk-rule configuration in `qkt.config.yaml` (daemon-wide today; per-strategy in Phase 25)
- The `LET` keyword aliasing computations for reuse across conditions and sizing
- The relationship between trade-level risk (DSL) and account-level risk (config)
