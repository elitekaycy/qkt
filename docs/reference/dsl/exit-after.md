# EXIT AFTER

`EXIT AFTER <duration>` closes an entry a fixed time after it fills:

```qkt
BUY gold SIZING 0.01 EXIT AFTER 4m
```

The clock starts when the entry **fills** and runs on the engine clock. It is checked on every
tick, so the close lands on the first tick at or after `fill time + duration`. It does not wait
for a bar close: a 90-second exit on a 5-minute stream closes about 90 seconds after the fill,
not at the next 5-minute boundary.

## Grammar

<!-- qkt-doc: grammar -->
```qkt
BUY|SELL <stream> ... EXIT AFTER <duration>
```

- `<duration>` is a literal: `90s`, `4m`, `1h`, `2d`. It must be positive. Expressions are not
  accepted.
- The clause is order-independent like the other action clauses, and may appear once per action.

## What it closes

The timed exit closes **this entry's own leg** at market, for whatever quantity of it is still
open. Other positions on the same symbol are not touched, which is what separates it from a
`CLOSE <stream>` rule (that flattens the stream).

- **No bracket.** The leg is closed at market when the time is up.
- **With a `BRACKET`.** The bracket's stop and target are cancelled first, then the leg is closed
  at market, so a resting exit cannot fire later against a position that is already gone. If the
  stop or target fills first, the timer is dropped and nothing else is sent.
- **Pending entries** (`ORDER_TYPE = LIMIT/STOP`). The timer starts only if the entry fills. If
  it is cancelled, rejected or expires unfilled, the timer is dropped.
- **`STACK_AT` legs.** Each stack leg gets the same hold, timed from **its own** fill. A leg that
  fills 30 seconds after the primary closes 30 seconds after the primary does.

## Worked example

A bracket-less seed that scales in with a burst, where every trade lives at most four minutes:

```qkt
STRATEGY gold_burst VERSION 1

SYMBOLS
  gold = EXNESS:XAUUSD EVERY 5m WARMUP 60 BARS

RULES
  WHEN ema(gold.close, 5) CROSSES ABOVE ema(gold.close, 13) AND POSITION.gold = 0
  THEN BUY gold SIZING 0.01
       STACK_AT MFE >= 0.30 WITHIN 4m SIZING 0.05 BRACKET { STOP LOSS BY 15.00, TAKE PROFIT BY 1.00 }
       STACK_AT MFE >= 0.30 WITHIN 4m SIZING 0.05 BRACKET { STOP LOSS BY 15.00, TAKE PROFIT BY 1.00 }
       EXIT AFTER 4m
```

The seed closes four minutes after it fills. Each burst leg closes four minutes after its own
fill, unless its take-profit fills first.

## EXIT AFTER versus `holding_duration`

A rule such as `WHEN POSITION.gold.holding_duration >= 240 THEN CLOSE gold` is evaluated only
when the stream's bar closes, like every `WHEN` rule. On a 5-minute stream it can close up to one
bar late, and because entries also fire at bar close, a 4-minute hold on 5-minute bars closes at
about 5 minutes. Use `EXIT AFTER` when the exit time itself matters.

## Not supported

These combinations are rejected at compile time:

- `STACK` pyramiding (use `STACK_AT`)
- an exit `OCO` on the same action
- `ON_FILL`, `ON_STOP`, `ON_TP` and `ON_CLOSE`
- legs of an `OCO_ENTRY`
- `BASKET` orders

## Restarts

An armed timer is persisted with the daemon's strategy state: the leg it closes, the leg's venue
ticket, and the absolute deadline. After a restart it is re-armed for the restored leg:

- A deadline still ahead fires at the **original** time, not a fresh hold from the restart.
- A deadline that passed while the daemon was down closes the leg on the first tick after
  startup, and the log says so (`passed its deadline during downtime`).
- A leg that no longer exists at the venue (closed by its stop or target, or by hand, while the
  daemon was down) drops its timer without sending anything.

The record is removed when the timer fires, when the leg exits first, or when the entry never
fills. `OrderManagerTimeExitRestartTest` covers each case against the on-disk state store.

## Known limitations

- The close is a market order sent on the first tick after the deadline. In live trading it
  fills at the venue's price at that moment; in backtest it fills at the tick's executable price.

## Parity

Backtest and live run the same engine component for this exit, checked per tick in both modes.
`DslExitAfterParityTest` runs each case through both and asserts identical trades, including the
close timestamps.
