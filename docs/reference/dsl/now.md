# NOW — clock accessors

`NOW` is a DSL-level reference to the strategy's clock. It returns time-of-day fields (hour, minute, weekday, date) plus the raw epoch-ms timestamp. Use it for session-window gating, time-of-day filters, and relative deadlines on pending orders.

## Shape

```qkt
NOW.<field>
NOW                      -- bare form, equivalent to NOW.epoch_ms
NOW + <duration>         -- relative deadline (epoch_ms)
```

## Fields

| Field | Returns | Range |
| --- | --- | --- |
| `NOW.hour_utc` | Integer 0–23 | UTC hour |
| `NOW.minute_utc` | Integer 0–59 | UTC minute |
| `NOW.weekday` | Integer 0–6 | ISO weekday, Monday = 0 |
| `NOW.date_utc` | Integer | Days since 1970-01-01 (epoch day) |
| `NOW.epoch_ms` | Long | Milliseconds since 1970-01-01T00:00:00Z |

All values are derived from `StrategyContext.clock`. **In backtest, this is the simulated clock**, advancing as candles close. Live deployments read wall-clock UTC time.

## Session-window gating

```qkt
RULES
    -- London open and NY open windows on XAUUSD
    WHEN NOW.hour_utc IN [7, 8, 13, 14, 15, 16]
     AND POSITION.gold = 0
    THEN BUY gold SIZING 0.10
```

`IN [...]` is the existing membership operator. Combine with `POSITION.<stream> = 0` to gate entries to specific session hours.

For sub-hour precision, add `NOW.minute_utc`:

```qkt
WHEN NOW.hour_utc = 14
 AND NOW.minute_utc < 5
 AND POSITION.gold = 0
THEN ...
```

(Strategy fires only on candles whose closing minute is 0, 1, 2, 3, or 4 of hour 14.)

## Weekday filters

```qkt
WHEN NOW.weekday < 5      -- Monday through Friday only (Mon=0, Fri=4)
 AND ...
THEN ...
```

Useful for FX strategies that should skip Saturday/Sunday gaps.

## Relative deadlines on pending orders

`NOW + <duration>` evaluates to the epoch-ms timestamp `<duration>` from now. Pair it with `TIF GTD UNTIL` to auto-expire pending orders:

```qkt
BUY gold SIZING 0.10
    ORDER_TYPE = STOP AT gold.close + 50
    TIF GTD UNTIL NOW + 10m
```

After 10 minutes, if the stop hasn't triggered, the broker auto-cancels the pending order.

Duration suffixes: `s` (seconds), `m` (minutes), `h` (hours), `d` (days).

## Determinism

The clock injected into `StrategyContext` is the engine's clock. In backtest, this is the simulated time that advances with each candle. In paper / live, it's the system clock. Either way, the value of `NOW.<field>` is determined by the engine's clock — not by `System.currentTimeMillis()` — so backtests are reproducible.

## Common gotchas

- **UTC only.** `NOW.hour_utc` reads UTC. There's no `NOW.hour_local` or `NOW.hour_<broker>`. If your strategy reasons about a session in local time (e.g. "8am New York"), translate to UTC at strategy-author time (NY = UTC-4 or UTC-5 depending on DST).
- **`NOW.weekday` is ISO**, not US convention. Monday = 0, Sunday = 6. (Java's `DayOfWeek` is 1-indexed; the DSL subtracts 1.)
- **Fields are integers**, not strings. `NOW.weekday = 0` works; `NOW.weekday = "Mon"` does not.
- **`NOW + 10m` is in milliseconds**, not seconds. The duration literal is canonicalized to ms at parse time.
- **Backtest determinism only.** When `qkt run` mode samples a different clock-now between rule-evaluation moments (e.g. a slow indicator-compute path), `NOW.minute_utc` is read at the instant of rule evaluation. Don't assume it's monotonic with respect to your candle close.

## What this composes with

- [Actions](actions.md) — `OCO_ENTRY` and `TIF GTD UNTIL` pair with `NOW + <duration>`
- [Conditions](conditions.md) — `NOW.<field>` is a primary expression usable in any WHEN clause
