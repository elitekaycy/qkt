# NOW — clock accessors

`NOW` is a DSL-level reference to the strategy's clock. It returns time-of-day and calendar fields (hour, minute, weekday, month, day, date) plus the raw epoch-ms timestamp. Use it for session-window gating, time-of-day filters, seasonal/calendar gating, and relative deadlines on pending orders.

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
| `NOW.month` | Integer 1–12 | UTC calendar month, January = 1 |
| `NOW.day` | Integer 1–31 | UTC day of month |
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

For a minute-precise window — especially one that crosses an hour or midnight — use `SESSION_WINDOW(startHour, startMinute, endHour, endMinute)`. It is true while the current UTC time-of-day is inside the window, inclusive of both ends, and repeats every day:

```qkt
RULES
    -- Asian-open burst window, 00:30-01:30 UTC
    WHEN SESSION_WINDOW(0, 30, 1, 30)
     AND POSITION.gold = 0
    THEN BUY gold SIZING 0.10
```

A window may wrap midnight — when the start is later in the day than the end, it runs from the start to end-of-day and on into the next day up to the end:

```qkt
    -- 23:00-01:00 UTC
    WHEN SESSION_WINDOW(23, 0, 1, 0) AND ...
```

Exit at the window close by negating it (`SESSION_WINDOW` is a boolean):

```qkt
    -- hard time-bound exit: no carry past 01:30 UTC
    WHEN NOT SESSION_WINDOW(0, 30, 1, 30) AND POSITION.gold > 0
    THEN CLOSE gold
```

All four arguments must be integer literals; hour is 0-23 and minute 0-59, validated at compile time. It reads the same `StrategyContext.clock` as `NOW`, so it is deterministic and identical in backtest and live.

## Weekday filters

```qkt
WHEN NOW.weekday < 5      -- Monday through Friday only (Mon=0, Fri=4)
 AND ...
THEN ...
```

Useful for FX strategies that should skip Saturday/Sunday gaps.

## Calendar windows (seasonal gating)

`CALENDAR_WINDOW(startMonth, startDay, endMonth, endDay)` is true while the current UTC date falls inside an annual date range, inclusive of both ends. It repeats every year, so a seasonal strategy can gate entries and exits to a recurring window without hard-coding a year.

```qkt
RULES
    -- Indian wedding/festival season into Diwali: Aug 15 - Oct 31
    WHEN CALENDAR_WINDOW(8, 15, 10, 31)
     AND POSITION.gold = 0
    THEN BUY gold SIZING 0.10
```

A window may wrap the year boundary. When the start is later in the calendar than the end, the window runs from the start through year-end and into the next year up to the end:

```qkt
    -- Chinese New Year restocking: Dec 1 - Jan 31
    WHEN CALENDAR_WINDOW(12, 1, 1, 31)
     AND POSITION.gold = 0
    THEN BUY gold SIZING 0.10
```

Exit at the window close by negating it — `CALENDAR_WINDOW` is a boolean, so `NOT` works:

```qkt
    WHEN NOT CALENDAR_WINDOW(8, 15, 10, 31)
     AND POSITION.gold > 0
    THEN CLOSE gold
```

All four arguments must be integer literals; month is 1–12 and day is 1–31, validated at compile time. The window reads the same `StrategyContext.clock` as `NOW`, so it is deterministic and identical in backtest and live.

For finer control, compose the raw fields instead: `NOW.month = 12 AND NOW.day >= 20` selects the back half of December.

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
