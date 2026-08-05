# PORTFOLIO files

A `PORTFOLIO` file composes N strategy files into one deployable unit. Each child runs independently, gated by a `RUN <child>` rule at the portfolio level. The daemon fans out into one observability port + log file per child.

Use a portfolio when:

- You want **regime-gated strategy switching** — different strategies for different market conditions
- You want **multi-asset / multi-timeframe** strategies running concurrently as one unit
- You need **per-strategy operator control** while keeping deployment as one artifact

## Shape

```qkt
PORTFOLIO <name> VERSION <int>

[ SYMBOLS
    <alias> = <BROKER>:<symbol> EVERY <timeframe>
    [ ... ]
]

IMPORT '<path>' AS <alias> [ HOLD ]
[ ... more imports ... ]

[ LET <name> = <expression> ]

[ REGIMES
    NAME <regime-set-name>
    STATE <name> WHEN <condition>
    [ ... more states ... ]
    STATE <name> DEFAULT
]

[ ALLOCATE
    METHOD regime_weighted
    <state-name> -> <alias> <weight>[, <alias> <weight> ...]
    [ ... more states ... ]
]

RULES
    [ WHEN <condition> ] RUN <alias>
    [ ... more RUN rules ... ]
```

The shape is similar to `STRATEGY`, but with `IMPORT` declaring child strategies and `RUN <alias>` actions. `REGIMES` + `ALLOCATE` add adaptive capital allocation on top of the on/off gating.

## Basic — regime-gated switching

```qkt title="strategies/btc_regimes.qkt"
PORTFOLIO btc_regimes VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

IMPORT 'trend.qkt'     AS trend
IMPORT 'meanrev.qkt'   AS meanrev
IMPORT 'breakout.qkt'  AS breakout

LET adxValue = adx(btc, 14)

RULES
    WHEN adxValue > 30  RUN trend          -- strong trend
    WHEN adxValue < 20  RUN meanrev        -- ranging
    WHEN adxValue BETWEEN 20 AND 30  RUN breakout    -- transitional
```

Only one child runs at a time. When ADX moves from ≥30 to <20, `trend` deactivates (positions closed unless `HOLD`) and `meanrev` activates.

## Regime-weighted allocation

Instead of fully switching children on or off, you can assign each active child a regime-dependent weight. The weights scale the child's new risk-increasing orders, so the portfolio tilts capital toward the child that fits the current regime while keeping others alive at a smaller size.

```qkt title="strategies/btc_regime_weighted.qkt"
PORTFOLIO btc_regime_weighted VERSION 1 CAPITAL 10000

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

IMPORT 'trend.qkt'   AS trend
IMPORT 'meanrev.qkt' AS meanrev

REGIMES
    NAME market_regime
    STATE trend WHEN adx(btc, 14) > 25
    STATE range DEFAULT

ALLOCATE
    METHOD regime_weighted
    trend -> trend 0.8, meanrev 0.2
    range -> trend 0.2, meanrev 0.8

RULES
    RUN trend
    RUN meanrev
```

- `REGIMES` defines named states. The first `WHEN` state that matches wins; `DEFAULT` catches everything else.
- `ALLOCATE METHOD regime_weighted` maps each state to per-alias weights. Weights are fractions of normal risk (1.0 = full size, 0.0 = suppressed).
- `cash` is reserved and ignored as an alias; use it to park risk-off capital.
- All imported aliases receive a weight in every state. Missing aliases default to `0.0`.

The weights are evaluated on every closed candle and applied to new orders through the same book-risk seam used by drawdown de-risking, so backtest and live use identical scaling.

## `IMPORT` syntax

```qkt
IMPORT '<relative_path>' AS <alias> [ HOLD ]
```

- **Path** is relative to the portfolio file. Use forward slashes.
- **Alias** is how the portfolio's `RUN` rules reference the child.
- **`HOLD`** (optional): keep the child's positions when it deactivates. Without HOLD, deactivation flattens.

```qkt
IMPORT 'trend.qkt'    AS trend         -- closes positions on deactivate
IMPORT 'longterm.qkt' AS longterm HOLD  -- keeps positions when deactivated
```

`HOLD` mode is useful for long-horizon children whose entries take days to play out. You don't want a regime flip to flush a 3-day position.

## `RUN <alias> OVERRIDE { ... }` — per-child param tuning

Retune a child's `PARAM` values for this portfolio without editing the child file:

```qkt
RUN aggressive OVERRIDE { riskPct = 0.008 }
RUN conservative OVERRIDE { riskPct = 0.003 }
```

- Each `key = value` maps to a `PARAM` declared in the child strategy. An unknown key or a type mismatch (number → string) is a compile-time error.
- The result is identical to hand-editing the child's `PARAM riskPct = 0.008` line — the engine sees no difference.
- Two `RUN` rules for the same alias with different `OVERRIDE` values are a compile-time error (use the same value or consolidate into one rule).
- `OVERRIDE` is optional. `RUN a` with no override uses the child's declared defaults.

## `RUN <alias>` action

The portfolio's only action verb. Activates the named child.

```qkt
WHEN <condition>  RUN <alias>
```

- The child becomes "active" — it processes ticks, evaluates its own rules, emits signals.
- An "active" child whose condition transitions to false becomes "inactive" — pauses processing, flattens (or holds) positions.
- Multiple children can be active simultaneously if multiple conditions hold.

### Always-on (no gating)

```qkt
WHEN TRUE  RUN someChild
```

Activates `someChild` always — used for the "always running" parts of a portfolio.

### Mutually exclusive (regime gates)

Above example with ADX. Make sure your conditions are mutually exclusive (BETWEEN/<>) so exactly one child is active at any time.

### Concurrent (multi-asset)

```qkt
RULES
    WHEN TRUE  RUN btcChild
    WHEN TRUE  RUN eurChild
    WHEN TRUE  RUN goldChild
```

Three children running in parallel, each trading its own asset. The portfolio is just a deployment wrapper here — no regime logic.

## Operator control

Children expose their own ports + logs. The daemon's `qkt list` shows them indented under the portfolio:

```text
NAME              KIND        PORT     TRADES   STATE
btc-regimes       portfolio   47291    -        running
  trend           child       47292    8        active
  meanrev         child       47293    23       inactive
  breakout        child       47294    5        inactive
```

Operators can override gating:

```bash
qkt start  btc-regimes/meanrev    -- force meanrev active regardless of regime
qkt stop   btc-regimes/trend       -- force trend inactive
```

Manual overrides clear when you `qkt resume <portfolio>`.

## Shared symbol declarations

Portfolio-level `SYMBOLS` is inherited by every child that doesn't redeclare. Useful when all children trade the same underlying:

```qkt
PORTFOLIO multi_strat VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h   -- all children see this

IMPORT 'a.qkt' AS a
IMPORT 'b.qkt' AS b
-- if a.qkt references 'btc', it picks up the portfolio's declaration
-- if a.qkt has its own SYMBOLS block, that wins
```

The portfolio's symbols are also the only ones whose data the portfolio rules can read (for regime detection).

## Risk and the portfolio

Daemon-level risk rules apply to **the whole portfolio**, not per-child. If one child triggers `max-daily-loss`, the entire daemon halts — every other strategy too.

To express per-child risk limits, use `qkt.config.yaml`'s `risk.per_strategy` block:

```yaml
risk:
  per_strategy:
    btc_regimes:trend:
      max_trades_per_day: 5
      cooldown_after_loss: 1h
      cooldown_after_loss_after_consecutive: 1
      loss_streak_halt: 3
      loss_streak_halt_scope: persistent
```

These apply independently to each strategy hosted in the daemon — including portfolio children.

## LET in portfolios

`LET` works the same as in `STRATEGY` files — name an expression for reuse in `RUN` conditions:

```qkt
PORTFOLIO mybook VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

IMPORT 'trend.qkt'   AS trend
IMPORT 'meanrev.qkt' AS meanrev

LET adxStrong = adx(btc, 14) > 30
LET adxWeak   = adx(btc, 14) < 20

RULES
    WHEN adxStrong  RUN trend
    WHEN adxWeak    RUN meanrev
```

LET names make portfolio rules read like English.

## What children inherit

| Inherited | Notes |
| --- | --- |
| `SYMBOLS` declarations | Children can override |
| Daemon-level risk rules | Apply across all children |
| Broker configurations from `qkt.config.yaml` | Children resolve broker prefixes the same way |
| `--param` overrides | Apply to all children unless qualified |

| Not inherited | |
| --- | --- |
| `DEFAULTS` from portfolio | Children have their own |
| `LET` from portfolio | Children have their own |

## Common gotchas

- **Non-mutually-exclusive regime gates leave gaps.** If your rules cover only `> 30` and `< 20`, the range `[20, 30]` has no child active. Use `BETWEEN ... AND ...` or `ELSE` patterns to cover everything.
- **Children with their own `SYMBOLS` blocks override.** A child that redeclares `btc` with a different timeframe than the portfolio works fine, but it costs an extra aggregator.
- **`HOLD` doesn't mean "keep entering."** A held child has its positions preserved but stops generating new signals when inactive. It only manages existing positions.
- **Cascade stop.** `qkt stop <portfolio>` cascades to every child. Use `qkt stop <portfolio>/<child>` to stop a specific child.
- **Portfolio file is itself a strategy.** It has `VERSION`, `RULES`, can have `LET`. It just uses `RUN` actions instead of `BUY`/`SELL`.

## Backtesting portfolios

`qkt backtest <portfolio.qkt>` runs the children as N attributed strategies on one engine, sharing one account and the book-risk layer. The same backtest tiers that work for single strategies also work for portfolios:

- `--bars` replays built bars instead of ticks.
- `--bar-tf <tf>` pins the bar feed to a specific built timeframe (must divide every child's declared timeframe).
- `--tick-fills` drives signals from bars but resolves fills on real ticks.
- `--report-dir <dir>` writes the standard report bundle (`result.json`, `trades.csv`, per-strategy equity curves, etc.) in the same format as a single-strategy backtest.

Regime-weighted allocation and `WHEN..RUN` gating use the same `PortfolioGate` + `BookRiskController` code in backtest and live, so results are directly comparable. A child imported without `HOLD` is flattened on deactivation in backtest exactly as the live supervisor would, while a `HOLD` child keeps its positions and continues to manage exits.

## What this composes with

- [STRATEGY block](strategy-block.md) — child files are regular strategies
- [Conditions](conditions.md) — `RUN` rules use the same condition grammar
- [Indicators](indicators.md) — regime detectors are usually indicator-based
- [Portfolio example](../../examples/portfolio.md) — full deployment with three regimes
- [Regime-adaptive portfolio](../../examples/regime-adaptive/) — `REGIMES` + `ALLOCATE METHOD regime_weighted`
- [Phase 13b](../../phases/phase-13b.md), [Phase 14](../../phases/phase-14.md) — design notes on portfolio fan-out
