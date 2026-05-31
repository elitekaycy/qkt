# SCHEDULE

The `SCHEDULE` block declares **clock-driven** actions — things that should run at a specific time of day, regardless of whether a tick happens to land in that second. Use it for session-anchored placements (the `19:55` window in `hedge-straddle.qkt`), end-of-day position cleanup, scheduled rebalances, and any rule whose trigger is "the clock said so" rather than "a bar just printed."

## Why this exists

Without `SCHEDULE`, the only way to express "act at 09:00" is to write a tick-driven rule:

```qkt
RULES
    WHEN NOW.minute_utc = 0 AND NOW.hour_utc = 9
    THEN BUY gold ...
```

Two ways that goes wrong:

1. **Quiet markets miss the bar.** If no tick arrives during the second `NOW.minute_utc = 0` is true, the rule never fires that day.
2. **High-tick markets fire bursts.** 50 ticks within that second → 50 fires. Strategies guard with `POSITION.x = 0`, but it's a workaround.

`SCHEDULE` is the right primitive: the engine fires the action exactly when the clock crosses the scheduled time, once, no matter how the tick stream behaves.

## Shape

```qkt
SCHEDULE
    AT 09:00 UTC                  THEN <action>
    AT 12:00, 14:00, 16:00 UTC    THEN <action>
    EVERY HOUR AT :00             THEN <action>
    EVERY DAY AT 21:00 UTC        THEN <action>
    EVERY WEEKDAY AT 14:30 NY     THEN <action>
```

`SCHEDULE` is a **top-level block**, alongside `RULES`. A strategy can have both — they don't interfere. `WHEN` rules fire on bar close; `SCHEDULE` clauses fire on the clock.

Clauses inside `SCHEDULE` are independent. Each clause is one or more triggers followed by `THEN <action>`. The action grammar is the same as inside `RULES` — `BUY`, `SELL`, `CLOSE_ALL`, `LOG`, `CANCEL_ALL`, `OCO_ENTRY`, brackets, `CASE … END`, etc. all work.

## Trigger variants

| Form | Means | Example |
| --- | --- | --- |
| `AT <time> UTC` | Once per day at the given local time | `AT 09:00 UTC` |
| `AT <t1>, <t2>, … UTC` | Same action at every listed time | `AT 09:00, 12:00, 14:00 UTC` |
| `EVERY HOUR AT :<min>` | Every hour at the given minute past | `EVERY HOUR AT :30` |
| `EVERY DAY AT <time> UTC` | Once per day (same as `AT` form) | `EVERY DAY AT 09:00 UTC` |
| `EVERY WEEKDAY AT <time> UTC` | Mon-Fri only (calendar-aware) | `EVERY WEEKDAY AT 14:30 NY` |

`HH:MM` and `HH:MM:SS` are both valid time-of-day literals.

The multi-time `AT` list is first-class — session strategies routinely need 3-5 fire times per day (London open, NY open, NY close, etc.). Two ways to express that:

```qkt
-- Multi-clause: different action per time
SCHEDULE
    AT 08:00 LONDON THEN LOG "ldn-open"
    AT 13:30 NY     THEN BUY gold SIZING 0.1
    AT 21:00 UTC    THEN CLOSE_ALL gold

-- List in one clause: same action at multiple times
SCHEDULE
    AT 09:00, 12:00, 14:00 UTC THEN LOG "midday checkpoint"
```

## Timezones

Every `AT`/`EVERY DAY`/`EVERY WEEKDAY` trigger requires an **explicit timezone tag**. Omitting it is a parse error — silent timezone defaults have caused enough live-trading bugs that the language forces strategy authors to write it.

`EVERY HOUR AT :NN` doesn't take a tag because "every hour" is the same in any timezone.

| Tag | IANA zone | DST | Typical use |
| --- | --- | --- | --- |
| `UTC` | UTC | no | system-default, crypto, instrument-agnostic |
| `NY` | `America/New_York` | yes | NY equity / FX session |
| `LONDON` | `Europe/London` | yes | LDN FX session (typical volume peak) |
| `TOKYO` | `Asia/Tokyo` | no | Asia session, JPY pairs |
| `SYDNEY` | `Australia/Sydney` | yes (southern hemisphere — opposite phase from NY/LDN) | AUD pairs, opens before Tokyo |
| `CHICAGO` | `America/Chicago` | yes | CME futures (energy, ag) |
| `BROKER` | broker profile's `serverTzOffset` | depends | **reserved — not yet wired**. Triggers a clear error at registration time. Use UTC or a named IANA zone until broker-profile timezone config ships. |

DST is handled correctly via `java.time.ZoneId` — `AT 09:30 NY` resolves to a different UTC instant in January vs July, automatically.

## Worked examples

### Session entry

```qkt
STRATEGY ny_open_breakout VERSION 1
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 1m

SCHEDULE
    EVERY WEEKDAY AT 14:30 NY THEN BUY gold SIZING 0.1
```

Buys gold every weekday at NY market open (`09:30` NY = `14:30` summer / `15:30` winter UTC).

### Mixed clock + event in one strategy

```qkt
STRATEGY hedge_with_cleanup VERSION 1
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 1m

SCHEDULE
    EVERY DAY AT 19:55 UTC THEN BUY gold SIZING 0.05 BRACKET { ... }
    EVERY DAY AT 21:00 UTC THEN CLOSE_ALL gold

RULES
    WHEN gold.spread > 50 THEN LOG "wide spread"
    WHEN POSITION.gold > 0 AND gold.close < ema(gold.close, 9) THEN CLOSE gold
```

`SCHEDULE` runs the placement / cleanup loop on the clock; `RULES` runs ongoing position management on every bar close.

### Conditional fire at scheduled time

The action body uses the same grammar as `RULES`, so a `CASE` can filter at fire time:

```qkt
SCHEDULE
    EVERY DAY AT 09:00 UTC THEN
        CASE WHEN ema(gold.close, 9) > ema(gold.close, 21) THEN BUY gold SIZING 0.1
             ELSE LOG "no signal at 09:00"
        END
```

## What's NOT in this phase

- **`LOCAL` timezone tag** — deferred. Use explicit IANA names.
- **`BROKER` timezone wiring** — the keyword parses but throws at registration. Needs `serverTzOffset` config support in `qkt.config.yaml` broker profiles, which lands in a follow-up.
- **State between fires** — the pattern "observe an EMA at 09:00, act on the observation at 21:00" needs a mutable-`LET` `SET` action that hasn't shipped yet. Today the closest is to re-evaluate the condition at the action time inside a `CASE`. File an issue if you have a concrete use case.
- **Sub-minute granularity** — no `EVERY MINUTE` or seconds-level recurrence. Deferred.
- **Cron syntax** — `0 0 * * *` is not supported. English forms keep the DSL readable.
- **1Hz live-mode timer for quiet markets** — backtest works fine on the per-tick heartbeat. Live runs may miss scheduled fires during quiet seconds; the `LiveSession`-side timer is a follow-up.
- **Persistence across daemon restart** — last-fire-time is in-memory only. Restart at 09:00:30 → the 09:00 fire is detected as missed, WARN logged, skipped (intentional).

## Behavior details worth knowing

- **Fire-once-per-window.** Each trigger advances a watermark. A fire that happens to coincide with multiple eligible times within one heartbeat (e.g. engine paused) emits **once**, with a WARN per skipped fire. Strategies that need catch-up should detect via `NOW` and decide themselves.
- **Backtest = live parity.** The heartbeat runs from `TradingPipeline.ingest(tick)` using the same clock the rest of the engine uses, so a backtest of `SCHEDULE AT 09:00 UTC` fires at the simulated 09:00, matching what live would produce.
- **Action context.** Schedule fires synthesize the action's `EvalContext.candle` from the latest closed bar across the strategy's declared streams. If no bar has closed yet (warmup-cold), the fire is **skipped with a WARN** rather than crashing. Once any stream has a closed bar, fires evaluate normally. Cross-stream reads inside the action body (`gold.close`, `silver.ask`) work as in `RULES` — they go through the hub's per-stream rings.
- **Empty `SCHEDULE` block.** Don't write one. The parser doesn't enforce it but adds nothing.

## Validation rules

- Every `AT`/`EVERY DAY`/`EVERY WEEKDAY` trigger requires an explicit timezone tag.
- `EVERY HOUR AT :NN` requires `NN` in `0–59`.
- `HH:MM[:SS]` literals: hour `0–23`, minute `0–59`, second `0–59`. Out-of-range = parse error.
- A `SCHEDULE` clause needs at least one trigger.

## See also

- [Phase 40 — Bar-Level Synchronized Publish](../../phases/phase-40-schedule.md) — design context, known limitations, future work
- [#77](https://github.com/elitekaycy/qkt/issues/77) — original issue
- [conditions.md](conditions.md) — `WHEN` rules (the event-driven counterpart)
- [actions.md](actions.md) — the action grammar shared with `RULES`
