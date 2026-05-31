# Phase 40 — Clock-driven `SCHEDULE` DSL action

## Summary

Strategies can now declare clock-driven actions via a top-level `SCHEDULE` block alongside `RULES`. Triggers fire at exact times of day regardless of tick cadence — solving the "quiet markets miss the bar" and "high-tick markets fire bursts" failure modes of using `WHEN NOW.minute_utc = N`. Closes #77.

Five timezone tags ship in this phase (`UTC`, `NY`, `LONDON`, `TOKYO`, `SYDNEY`, `CHICAGO`) with `BROKER` reserved as a keyword for a follow-up.

## The problem

Hedge-straddle's `:55` UTC placement window is the canonical example. The strategy expressed it as:

```qkt
WHEN NOW.minute_utc = 55 AND NOW.second_utc = 0
THEN BUY gold ...
```

This is tick-driven, which causes two failure modes:

1. **Quiet markets miss the bar.** If no tick arrives during the second `NOW.second_utc = 0` is true, the action never runs. Hedge-straddle has hit this in prod logs — the next tick was at `:55:01.480`.
2. **High-tick markets fire bursts.** 50 ticks in the matching second → 50 fires. Strategies guard with `POSITION.x = 0` checks, but the wrong primitive is being abused.

The fix is a dedicated `SCHEDULE` block that fires on the clock, not on ticks.

## What's new

### DSL surface

New top-level block:

```qkt
SCHEDULE
    AT 09:00 UTC               THEN <action>
    AT 12:00, 14:00 UTC        THEN <action>
    EVERY HOUR AT :00          THEN <action>
    EVERY DAY AT 21:00 UTC     THEN <action>
    EVERY WEEKDAY AT 14:30 NY  THEN <action>
```

Five trigger forms, multi-time `AT` list as first-class shape for session strategies that hit 3-5 fire times per day.

### Engine

- **`ScheduleRunner`** registry — per-trigger watermark, tick-driven heartbeat from `TradingPipeline.ingest(tick)`.
- **Skip-on-miss** — if multiple fire times elapse between calls (engine paused, restarted, quiet market longer than the trigger interval), the runner advances the watermark past all of them, logs a WARN per skipped fire, and emits the action exactly once.
- **DST-correct fire-time math** — `Timezone` resolves to `java.time.ZoneId`, so `AT 09:30 NY` fires at `14:30` UTC in summer and `15:30` UTC in winter.
- **Backtest = live parity** — the heartbeat reads from the engine clock; tick replay drives schedules in lockstep with bars.

### Tokens, AST, parser

- New tokens: `SCHEDULE`, `HOUR`, `WEEKDAY`, `UTC`, `NY`, `LONDON`, `TOKYO`, `SYDNEY`, `CHICAGO`, `BROKER`.
- New AST: `TimeOfDay`, `Timezone` (sealed, 7 variants), `ScheduleTrigger` (4 variants), `ScheduleDecl`.
- `StrategyAst.schedules: List<ScheduleDecl>` defaults to empty — pure addition.
- Parser dispatches on token kind for trigger and timezone, with explicit-timezone-required validation.

### `AstCompiler.bindSchedules`

New method on `DslCompiledStrategy`. Default no-op; `CompiledStrategy` overrides to pre-compile each clause's action and register triggers with the `ScheduleRunner`. `TradingPipeline.start` calls it during the per-strategy bind loop.

### `EvalContext` for schedule fires

Schedule fires don't have a "current bar." The runner builds an `EvalContext` with `candle` synthesized from the latest closed bar across the strategy's declared streams. Cross-stream reads in the action body (`gold.close`, `silver.ask`) work normally via the hub's per-stream rings. When no bar has closed yet (warmup-cold), the fire is **skipped with a WARN** rather than crashing.

## Migration

Pure addition. Every existing strategy parses and runs unchanged.

To opt a hedge-straddle-style placement strategy in, replace the abused `WHEN NOW.*` rule with a `SCHEDULE` clause:

```diff
-RULES
-    WHEN NOW.minute_utc = 55 AND NOW.second_utc = 0
-    THEN BUY gold SIZING 0.05 BRACKET { ... }
+SCHEDULE
+    EVERY DAY AT 19:55 UTC THEN BUY gold SIZING 0.05 BRACKET { ... }
```

Same action, same place, but now the fire is clock-driven — no more quiet-market misses or high-tick bursts.

## Known limitations and deferred work

- **`BROKER` timezone tag is reserved but not wired.** Using it triggers a clear error at registration. Implementation needs a `serverTzOffset` field on broker profiles in `qkt.config.yaml`; follow-up issue.
- **`LOCAL` timezone tag** — not in this phase. Use an explicit IANA tag (`NY`, `LONDON`, etc.).
- **State between fires** — the pattern "observe an EMA at 09:00, act on it at 21:00" is not directly expressible. The closest workaround is to re-evaluate the condition at action time inside a `CASE`. A mutable-`LET` `SET` action plus a query primitive would unlock the "observe at A, act at B" pattern; deferred until concrete demand surfaces.
- **Sub-minute granularity** — no `EVERY MINUTE` or seconds-level recurrence. Deferred.
- **Cron syntax** — explicitly rejected. English forms keep the DSL readable.
- **1Hz live-mode quiet-market timer** — backtest is fine on the per-tick heartbeat. Live runs may miss scheduled fires during quiet seconds because the heartbeat depends on `ingest(tick)`. `TradingPipeline.scheduleHeartbeat(nowMs)` exists as a hook for `LiveSession` to call from a 1Hz timer, but the timer itself hasn't shipped.
- **Persistence across daemon restart** — last-fire-time is in-memory only. Restart at 09:00:30 → the 09:00 fire is detected as missed (correctly), WARN logged, skipped.
- **Synthesized-candle EvalContext** — schedule actions read whatever the latest closed bar on any declared stream happens to be. For most actions that only emit signals by alias (`BUY gold SIZING …`) this is invisible; for actions that implicitly use the current candle's price (some BRACKET legs), the synthesized bar may not be the most semantically correct choice. A future task could refactor `EvalContext.candle` to nullable and audit the implicit-bar reads.

## Files touched

- Tokens: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- AST: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`
- Parser: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Engine: `src/main/kotlin/com/qkt/dsl/compile/ScheduleRunner.kt` (new), `CompiledSchedule.kt` (new), `DslCompiledStrategy.kt`, `AstCompiler.kt`
- Pipeline: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Tests: `LexerTest.kt`, `ScheduleAstTest.kt` (new), `ParserScheduleTest.kt` (new), `ScheduleRunnerTest.kt` (new), `AstCompilerScheduleBindTest.kt` (new), `ScheduleEndToEndTest.kt` (new)
- Docs: `docs/reference/dsl/schedule.md` (new), this file
- Spec / plan: `docs/superpowers/specs/2026-05-31-phase40-schedule-design.md`, `docs/superpowers/plans/2026-05-31-phase40-schedule.md`
