# Risk-managed strategy

Trading with discipline isn't optional. This example combines per-trade risk sizing (so every trade risks the same share of equity) with account-level safeguards (daily-loss halt, drawdown cap). It's the kind of risk infrastructure that turns a backtest into something you'd trust live.

## What it does

- **Trades** gold (`XAUUSD`) on 15-minute candles
- **Entries** on a simple trend filter: long when the 20-EMA is above the 50-EMA and price pulls back to the 20-EMA
- **Per-trade risk**: each entry risks 1% of equity at its stop (`SIZING 1.0 PCT RISK`)
- **Account-level halts** (configured in `qkt.config.yaml`):
  - Stop trading for the day after a 300 USD realized loss, or a 3% drawdown from the day's start
  - Stop trading at a 10% drawdown from the starting balance
  - Never hold more than 1 lot, or more than one open position, in this strategy

The trading logic is intentionally simple — the **infrastructure around it** is the point.

## The strategy file

```qkt title="strategies/risk-managed.qkt"
STRATEGY risk_managed VERSION 1

SYMBOLS
    gold = BACKTEST:XAUUSD EVERY 15m

PARAM fastPeriod = 20
PARAM slowPeriod = 50

LET fastMa   = ema(gold.close, fastPeriod)
LET slowMa   = ema(gold.close, slowPeriod)
LET stopDist = atr(gold, 14) * 2

RULES
    -- only enter in an uptrend, on a pullback that touches the fast MA
    WHEN fastMa > slowMa
     AND gold.close > fastMa
     AND gold.low <= fastMa             -- touched the MA this bar
     AND POSITION.gold = 0
    THEN BUY gold SIZING 1.0 PCT RISK
         BRACKET {
           STOP_LOSS AT gold.close - stopDist,
           TAKE_PROFIT AT gold.close + stopDist * 3   -- 3R target
         }
       ; LOG "pullback long" atr=atr(gold, 14)

    -- exit if the trend breaks
    WHEN fastMa < slowMa
     AND POSITION.gold > 0
    THEN CLOSE gold
       ; LOG "trend broken, exit"
```

Key points:

- **Risk sizing.** `SIZING 1.0 PCT RISK` sizes the order so a stop-out loses 1% of equity: qkt
  divides the budget by the distance from the fill to the bracket's stop, using the instrument's
  contract size. It requires a `BRACKET` with a stop. See [SIZING](../reference/dsl/sizing.md#risk-percent-sizing).
- **Stop adapts to volatility.** `stopDist = atr(gold, 14) * 2` widens in fast markets and tightens in calm ones. Position size moves inversely — wide stops get small positions, tight stops get bigger ones.
- **3R target.** Take-profit at `stopDist * 3` above entry means each full winner is three times each full loser. The strategy can win 30% of the time and still break even before costs.

## The risk config

```yaml title="qkt.config.yaml"
starting_balance: 10000

risk:
  max_daily_loss: "300"          # halt for the rest of the UTC day after -300 USD realized
  max_daily_drawdown_pct: "3"    # halt for the day at -3% from the day's starting balance
  max_drawdown_pct: "10"         # halt at -10% from the starting balance
  per_strategy:
    risk_managed:
      max_position_size: "1.0"   # never hold more than 1 lot
      max_open_positions: "1"
```

The global keys apply to **every strategy the daemon hosts**: if one of them breaches the drawdown
cap, all of them stop trading. The `per_strategy.<name>` block layers on top and halts only that
strategy; in a backtest it is keyed by the `STRATEGY` name, here `risk_managed`. Every key is in the [config schema](../reference/config-schema.md#risk). Without a
`risk` block a backtest still applies the default `max_daily_loss` of 1000 USD.

## How to run it

Put both files in one directory and run from it; `qkt backtest` reads `./qkt.config.yaml` by
default (or pass `--config`):

```bash
qkt parse strategies/risk-managed.qkt
qkt backtest strategies/risk-managed.qkt --config qkt.config.yaml \
    --from 2026-07-06 --to 2026-08-29 --no-fetch
```

## What to expect

qkt 0.56.2, eight weeks of `XAUUSD` ticks (6 July to 28 August 2026), the default paper broker.
First without the config above, so only the default daily-loss rule applies:

```text
Result
  Profit             -471.24 USD   (-4.7%)
  Round trips        57 closed, 12 won (21.1%)
  Open at the end    none
  Worst drop         11.4%    biggest fall from a peak
  Worst day          2.1%
  Sharpe ratio       -1.05     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

Then with it:

```text
Result
  Profit             -999.22 USD   (-10.0%)
  Round trips        26 closed, 3 won (11.5%)
  Open at the end    none
  Worst drop         10.6%    biggest fall from a peak
  Worst day          1.8%
  Sharpe ratio       -4.12     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
  Halts              1 halt(s); trading stopped partway
```

The two runs take the same first 26 trades. On 3 August at 02:36 UTC the account reached the 10%
drawdown and the halt stopped it; `result.json` records the reason as
`global drawdown 0.1000 exceeds max 0.1`. Without the cap the strategy kept trading, fell 11.4%
from its peak and recovered to -4.7% by the end of the window. The cap did its job: it kept the
loss at the line the config drew. It also locked in that loss, which is the trade-off of any hard
stop on an account.

The report's `tradeSummary.avgRiskUsd` is 86.60 USD per trade, not 100: equity is below 10,000 USD
for most of the run, and quantities round down to the 0.01-lot step. Neither run makes money; the
strategy is here for its risk plumbing, not its edge.

## How to adapt it

### Larger / smaller per-trade risk

Change the percentage. It must be a number written in the file, not a `PARAM`:

```qkt
THEN BUY gold SIZING 2.0 PCT RISK      -- 2% per trade (aggressive)
     BRACKET { STOP_LOSS AT gold.close - stopDist, TAKE_PROFIT AT gold.close + stopDist * 3 }
```

To compute the budget instead, use `SIZING RISK $ <expression>` (account currency) or
`SIZING RISK <fraction-expression>`.

The Kelly-optimal sizing is typically 0.5–1% for most retail strategies. Anything above 2% is aggressive and produces volatile equity curves.

### Tighter daily-loss halt

```yaml
risk:
  max_daily_drawdown_pct: "1.5"
```

This kicks you out of the market faster on bad days. The trade-off: more halts means more missed recovery rallies. Tune to your stomach.

### Per-strategy halts

Per-strategy caps live under `risk.per_strategy.<name>`: `max_daily_loss`, `max_position_size`,
`max_open_positions`, `max_drawdown_pct`, `max_daily_drawdown_pct`, `max_trades_per_day`,
`cooldown_after_loss`, `loss_streak_halt`. See the [config schema](../reference/config-schema.md#risk).
The global rules still apply daemon-wide; per-strategy caps layer on top.

### Manual resume after a drawdown halt

A drawdown halt is persistent: the daemon refuses new orders for the halted scope until an operator
clears it with `qkt resume [name]` (`qkt halt [name]` pauses by hand). See the
[CLI reference](../reference/cli-commands.md#operations).

## Common gotchas

- **The stop must be computable.** If `stopDist` is still undefined (ATR warming up), the order is skipped. qkt infers the warmup and loads it from before `--from`.
- **Daemon-wide vs per-strategy halts.** A global halt rule stops every strategy. Use `risk.per_strategy.<name>` to scope a halt to just one strategy.
- **A tight stop means a big size.** 1% risk over a very small `stopDist` is a large position. `max_position_size` is the cap that rejects it.
- **A backtest has a daily-loss rule by default.** `max_daily_loss` is 1000 USD unless the config sets it; `"0"` disables it.

## What this example demonstrates

- Risk-based sizing with `SIZING 1.0 PCT RISK` and a bracket stop
- Risk-rule configuration in `qkt.config.yaml`, global and per strategy
- A drawdown halt firing in a backtest, and what it costs and saves
- The `LET` keyword aliasing computations for reuse across conditions and prices
- The relationship between trade-level risk (DSL) and account-level risk (config)
