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

RULES
    [ WHEN <condition> ] RUN <alias>
    [ ... more RUN rules ... ]
```

The shape is similar to `STRATEGY`, but with `IMPORT` declaring child strategies and `RUN <alias>` actions.

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
    - type: max-trades-per-day
      count: 5
    - type: cooloff-after-loss
      duration: 1h
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

## What this composes with

- [STRATEGY block](strategy-block.md) — child files are regular strategies
- [Conditions](conditions.md) — `RUN` rules use the same condition grammar
- [Indicators](indicators.md) — regime detectors are usually indicator-based
- [Portfolio example](../../examples/portfolio.md) — full deployment with three regimes
- [Phase 13b](../../phases/phase-13b.md), [Phase 14](../../phases/phase-14.md) — design notes on portfolio fan-out
