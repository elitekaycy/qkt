# Phase 40 — Clock-driven `SCHEDULE` action design

> Status: design proposal. Issue: [#77](https://github.com/elitekaycy/qkt/issues/77).
> Confirmed defaults: UTC required explicit, English recurrence syntax, skip-on-miss with WARN, top-level block alongside `RULES`.

## Problem

Strategies that need clock-driven behavior — "place a bracket at 19:55 UTC", "rebalance every 4h on the hour", "reset state at 00:00 UTC" — currently abuse a `WHEN` rule with a clock predicate:

```qkt
RULES
    WHEN NOW.minute_utc = 55 AND NOW.second_utc = 0
    THEN BUY gold ...
```

Two failure modes follow because `WHEN` is tick-driven:

1. **Quiet markets miss the bar.** If no tick lands during the 1-second window where the predicate is true, the action never fires that day. Hedge-straddle has hit this — log shows the next tick was at `:55:01.480`, predicate false, never recovers.
2. **High-tick markets fire bursts.** 50 ticks within the matching second → 50 fires. Strategies guard with `POSITION.x = 0` checks, but it's a workaround that masks the wrong primitive.

Clock-driven actions are conceptually different from event-driven rules. The fix is a `SCHEDULE` primitive that runs the action body at the scheduled time *exactly once*, regardless of tick cadence, with the same composability as `WHEN` (any action — `BUY`, `OCO`, `BRACKET`, `CLOSE_ALL`, `LOG`, etc.).

## Surface

A new top-level block alongside `RULES`. Multi-instance per-day is a first-class shape — session strategies routinely need 3-5 fire times per day.

```qkt
STRATEGY example VERSION 1
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 1m

SCHEDULE
    AT 09:00 UTC            -- single time
        THEN BUY gold SIZING 0.1
    AT 12:00, 14:00, 16:00 UTC   -- list — same action three times
        THEN LOG "midday checkpoint"
    EVERY HOUR AT :00        -- every hour on the hour
        THEN CANCEL_ALL gold
    EVERY DAY AT 21:00 UTC   -- once a day
        THEN CLOSE_ALL gold
    EVERY WEEKDAY AT 14:30 UTC   -- Mon-Fri per calendar
        THEN BUY gold ...

RULES
    WHEN gold.close > 0 THEN LOG "tick"
```

`SCHEDULE` is its own block; clauses inside live alongside each other; no nesting inside `RULES`.

### Trigger variants

| Form | Means | Example |
| --- | --- | --- |
| `AT <time> UTC` | One-off at exact time of day, every day | `AT 09:00 UTC` |
| `AT <time>, <time>, … UTC` | Same action at each listed time | `AT 09:00, 12:00, 14:00 UTC` |
| `EVERY HOUR AT :<min>` | Every hour at the given minute past | `EVERY HOUR AT :30` |
| `EVERY DAY AT <time> UTC` | Once per day at the given time | `EVERY DAY AT 09:00 UTC` |
| `EVERY WEEKDAY AT <time> UTC` | Mon–Fri only (per strategy's `calendar`) | `EVERY WEEKDAY AT 14:30 UTC` |

### Timezone

`UTC` is **required** on every time literal. Omitting it is a parse error. Rationale: silent timezone defaults have caused enough live-trading bugs that explicit beats convenient. Strategy-local (`LOCAL`) and IANA names (`NY`, `LONDON`, `TOKYO`) are deferred for a follow-up.

`EVERY HOUR AT :30` doesn't carry a tz suffix because hourly intervals don't need one — `:30` is per-hour wall-clock minute, equivalent in any timezone.

### Time-of-day literals

`HH:MM` or `HH:MM:SS`. Examples:
- `09:00` → 09:00:00.000
- `14:30` → 14:30:00.000
- `19:55:30` → 19:55:30.000

Range checks at construction: hour 0–23, minute 0–59, second 0–59. Negative or out-of-range = parse error.

## AST

```kotlin
data class ScheduleDecl(
    val triggers: List<ScheduleTrigger>,  // length ≥ 1; AT list parses to >1
    val action: ActionAst,
)

sealed interface ScheduleTrigger {
    /** One-off at the given time of day, fires daily. */
    data class At(val time: TimeOfDay, val tz: Timezone) : ScheduleTrigger

    /** Every hour at the given minute past the hour (0–59). */
    data class EveryHour(val minuteOffset: Int) : ScheduleTrigger

    /** Once per day at the given time. */
    data class EveryDay(val time: TimeOfDay, val tz: Timezone) : ScheduleTrigger

    /** Mon-Fri only per the strategy's `calendar`, at the given time. */
    data class EveryWeekday(val time: TimeOfDay, val tz: Timezone) : ScheduleTrigger
}

data class TimeOfDay(val hour: Int, val minute: Int, val second: Int = 0) {
    init {
        require(hour in 0..23) { "hour must be 0-23: $hour" }
        require(minute in 0..59) { "minute must be 0-59: $minute" }
        require(second in 0..59) { "second must be 0-59: $second" }
    }
}

sealed interface Timezone {
    object UTC : Timezone
    // LOCAL, NY, LONDON, TOKYO deferred — see future-work below
}
```

`StrategyAst` gains:

```kotlin
val schedules: List<ScheduleDecl> = emptyList(),
```

Default `emptyList()` keeps every existing strategy compiling unchanged.

## Engine wiring

### Component

A new `ScheduleRunner` lives in `TradingPipeline`. It owns the per-strategy schedule registry and is the only thing that mutates it.

```kotlin
internal class ScheduleRunner(
    private val clock: Clock,
    private val calendar: TradingCalendar,
    private val log: Logger,
) {
    fun register(strategyId: String, schedule: ScheduleDecl, emit: (ActionExecution) -> Unit)
    fun unregister(strategyId: String)
    fun tick(nowMs: Long)  // called once per ingest, advances the heartbeat
}
```

### Heartbeat

`ScheduleRunner.tick(nowMs)` is invoked from `TradingPipeline.ingest(tick)` after the engine and hub feed. Same heartbeat pattern as Phase 35's `CandleHub.sweepSyncTimeouts`. For each registered trigger, the runner:

1. Computes the next-fire-time after `lastCheckTime`.
2. If `nowMs ≥ nextFireTime`, fires the action and advances `lastCheckTime = nextFireTime`.
3. If multiple fire times have elapsed since the last check (the engine was paused, restarted, or a tick gap > the trigger interval), the runner advances past them and **fires only once** at the most recent eligible time, emitting a WARN per skipped fire. This matches the confirmed "skip on miss" semantics.

`lastCheckTime` initializes to the strategy's `bindToHub` clock time. The first call after registration uses that as the watermark.

### Backtest determinism

The scheduler uses the engine clock (`Clock.now()` → `tick.timestamp` in backtest, `System.currentTimeMillis()` in live). Same code path. Backtest replay drives the simulated clock through every `feed(tick)`; the heartbeat fires schedules in lockstep with the tick stream, so a backtest of a strategy with `SCHEDULE AT 09:00 UTC` produces the exact same fires it would live.

### Calendar awareness

`EVERY WEEKDAY` is gated on `calendar.isOpen(date)` — the strategy's already-injected `TradingCalendar`. For crypto strategies (24/7 calendar), `EVERY WEEKDAY` behaves identically to `EVERY DAY`. For FX strategies on the standard calendar, weekends are skipped without firing the action.

Bank holidays follow whatever `TradingCalendar.isHoliday(date)` returns for the strategy. Default calendar handling per the existing wiring.

### Action evaluation context

`SCHEDULE`-driven actions don't have a "current candle" — they're not tick-driven and not anchored to any particular alias. Three options for `EvalContext`:

1. **Synthetic candle** = the latest closed bar from the strategy's first stream. Tractable but conflates "scheduled at 09:00" with "this stream just printed."
2. **Null candle** + null-safe expression evaluation. Existing expressions that read `<alias>.close` resolve via the hub ring already; they don't need the current-context candle for cross-stream reads. The "current alias" indirection in EvalContext can be left null for schedule fires.
3. **Per-stream candle map** in EvalContext — what Phase 35's sync callback nearly did. Could converge here.

This spec chooses **option 2**: a `null candle` path in EvalContext, with stream-field expressions falling through to the hub-ring lookup. Action evaluators that genuinely need a current-bar concept (e.g. `STACK_AT` expressions referencing the current candle's price) get an explicit error at compile time when used inside a SCHEDULE body. Cleanest separation; lowest blast radius on the existing expression evaluator.

The exact set of "needs current bar" actions to gate is enumerated in the plan. Conservatively starts with: all entry orders (`BUY`, `SELL`) that don't carry an explicit price work fine (they use the broker's market price at submit time, not the strategy's expression context); only expressions inside SIZING/BRACKET that reference the current-alias bar directly need the gate.

### Position with respect to `bindToHub`

`AstCompiler.bindToHub` extends to register each `ScheduleDecl` with the `ScheduleRunner`. Existing `RULES` wiring is unchanged.

## Parser

Grammar additions (extending the existing parser shape):

```
schedule-block := "SCHEDULE" schedule-clause+
schedule-clause := schedule-triggers "THEN" action

schedule-triggers := schedule-trigger
                   | "AT" time-of-day-list timezone     -- list form for "same action multiple times"

schedule-trigger := "AT" time-of-day timezone
                  | "EVERY" "HOUR" "AT" ":" NUMBER
                  | "EVERY" "DAY" "AT" time-of-day timezone
                  | "EVERY" "WEEKDAY" "AT" time-of-day timezone

time-of-day-list := time-of-day ("," time-of-day)+
time-of-day := NUMBER ":" NUMBER (":" NUMBER)?
timezone := "UTC"
```

`SCHEDULE` block sits after `SYMBOLS`/`LET`/`DEFAULTS` and before or after `RULES` — the parser handles either order to match existing flexibility on block positioning.

### New tokens

| Token | Lexed from |
| --- | --- |
| `SCHEDULE` | keyword |
| `HOUR` | keyword |
| `DAY` | keyword |
| `WEEKDAY` | keyword |
| `UTC` | keyword |

`AT`, `EVERY`, `THEN`, `COMMA`, `COLON`, `NUMBER` already exist.

### Time-of-day parsing

`09:00` lexes as `NUMBER(09) COLON NUMBER(00)`. The parser-side `parseTimeOfDay()` consumes a `NUMBER COLON NUMBER` pair, optionally followed by another `COLON NUMBER` for seconds. Range-checks at AST construction (`TimeOfDay.init`).

`EVERY HOUR AT :30` — the lone `:30` lexes as `COLON NUMBER(30)`. Parser consumes a `COLON NUMBER` after `EVERY HOUR AT`, builds `EveryHour(minuteOffset = 30)`.

### Parse-time validation

- At least one trigger per clause.
- For `AT <list>`, every element parses to a valid TimeOfDay.
- For `EVERY HOUR AT :<n>`, minute must be 0–59.
- For all `EVERY DAY` / `EVERY WEEKDAY` / `AT`, time must include UTC suffix (UTC required).

## Backtest semantics

`Backtest` already drives `TradingPipeline.ingest(tick)` per tick. The scheduler heartbeat runs inside that path. A backtest of a strategy with `SCHEDULE AT 09:00 UTC` fires the action at the first tick whose `timestamp.utcTimeOfDay >= 09:00:00` each replayed day. Backtest = live parity for schedule fires given the same tick stream.

`Backtest` with `candleWindow` non-null fires the heartbeat at every tick that closes a candle — same as live. Backtest with no `candleWindow` fires the heartbeat at every tick, which is still tractable (cost is O(triggers) per tick).

## Live behavior

`LiveSession.start` calls `bindToHub` which now also registers the schedules. The same heartbeat in `ingest(tick)` checks them. Quiet markets (no ticks for an hour) won't fire the heartbeat — this is a real concern. Mitigation: a periodic timer in `LiveSession` calls `pipeline.scheduleHeartbeat(System.currentTimeMillis())` every N seconds (probably 1s) during live runs only.

Quiet-market exposure in backtest is moot because backtest replay always produces a tick for every bar.

## Missed-fire semantics

A schedule "misses" when `lastCheckTime + interval < nowMs`. Causes:

- Engine was down (restart).
- Engine was sleeping (paused live session).
- Quiet market and the periodic timer hasn't ticked yet.

On detection, the runner:
1. Logs `WARN` per skipped fire: `schedule '...' missed at HH:MM:SS UTC (heartbeat was X seconds late)`.
2. Advances `lastCheckTime` past every missed fire.
3. Fires the action once, at the current `nowMs` (not the missed time) — so any expression in the action body reads the *current* state, not stale state from the missed time.

This is the confirmed "skip on miss" behavior. Strategies that genuinely need catch-up should detect via `NOW` and decide themselves.

## Failure modes covered

- **Quiet market, no ticks** — periodic timer in live mode triggers heartbeat at 1Hz regardless of market activity.
- **Engine restart at 09:00:30** — schedule for 09:00:00 is detected as missed on first post-restart tick; WARN logged, no late fire.
- **Multi-strategy contention** — each strategy has its own registered triggers; firing order is by registration order.
- **Time-zone confusion** — required UTC tag makes silent assumption impossible.
- **DST** — UTC doesn't have DST, so `SCHEDULE AT 09:00 UTC` is unambiguous year-round. When `LOCAL`/`NY`/`LONDON` arrive in a future phase, DST semantics belong in that phase's spec.

## What this PR does NOT introduce

- `LOCAL` timezone or IANA names — follow-up phase if/when needed.
- Cron-style syntax (`0 0 * * *`) — explicitly rejected per design decision.
- `EVERY MINUTE`, `EVERY 5 MINUTES`, sub-second granularity — deferred until concrete strategy use case surfaces.
- Conditional schedules (`SCHEDULE … IF gold.spread < 50 THEN …`) — not in this phase. Same composition can be expressed with `WHEN` inside the action body if needed.
- Backwards-compat catch-up firing — confirmed `skip on miss`.
- Schedule persistence across engine restarts — last-fire-time is in-memory only this phase.

## Open questions deferred for the plan

- Exact heartbeat cadence in live mode (1Hz default, configurable? Probably 1Hz is fine).
- Synthetic-EvalContext field naming and the precise list of action variants that gate at compile time on "needs current bar."
- Logging shape for the WARN — exact MDC keys, format string.
- Whether `SCHEDULE` block ordering relative to `RULES` is fixed by the grammar or fully optional.

These resolve during plan writing.

## Files this phase will touch

- `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` — new tokens
- `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt` — no change (KEYWORDS auto-derives from TokenKind)
- `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt` — `ScheduleDecl`, `ScheduleTrigger`, `TimeOfDay`, `Timezone`; `StrategyAst.schedules`
- `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` — `parseSchedules`, `parseScheduleTrigger`, `parseTimeOfDay`, integration into `parseStrategy`
- `src/main/kotlin/com/qkt/dsl/compile/ScheduleRunner.kt` (new) — registry + heartbeat
- `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` — wire schedules through `bindToHub`
- `src/main/kotlin/com/qkt/dsl/compile/EvalContext.kt` — nullable-candle path
- `src/main/kotlin/com/qkt/app/TradingPipeline.kt` — `scheduleRunner` member, heartbeat call in `ingest`
- `src/main/kotlin/com/qkt/app/LiveSession.kt` — 1Hz periodic-timer heartbeat for quiet markets
- `src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt` (new)
- `src/test/kotlin/com/qkt/dsl/compile/ScheduleRunnerTest.kt` (new)
- `src/test/kotlin/com/qkt/dsl/compile/ScheduleEndToEndTest.kt` (new)
- `docs/reference/dsl/schedule.md` (new) — DSL reference
- `docs/phases/phase-40-schedule.md` (new) — phase changelog

## Phase summary

- **DSL**: new `SCHEDULE` block with `AT` / `EVERY HOUR AT :NN` / `EVERY DAY AT … UTC` / `EVERY WEEKDAY AT … UTC` triggers; multi-time list syntax for "same action at multiple times."
- **AST**: `ScheduleDecl(triggers: List, action: ActionAst)`; `StrategyAst.schedules` default empty.
- **Engine**: `ScheduleRunner` registers triggers from `bindToHub`; tick-driven heartbeat in `TradingPipeline.ingest`; 1Hz periodic timer in `LiveSession` for quiet-market coverage.
- **Backtest**: deterministic on simulated clock; parity with live.
- **Missed-fire**: skip + WARN, advance watermark, single fire at current time.
- **Timezone**: UTC required explicit; `LOCAL`/IANA deferred.
- **Backwards compat**: pure addition; `schedules` defaults `emptyList`.
