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
when the stream's bar closes, like every `WHEN` rule, and measures the hold from the open of the bar
the position opened in. On a 5-minute stream a 4-minute hold closes at the first close after entry,
5 minutes in; a pending entry filled late in a bar can close at the next close, moments after its
fill. Use `EXIT AFTER` when the exit time itself matters: it is timed from the fill.

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

The record is removed when the timer's close fills, when the leg exits first, or when the entry
never fills. `OrderManagerTimeExitRestartTest` covers each case against the on-disk state store.

## A close the venue does not fill

The close can end at the venue with nothing or only part filled: a thin book, a price band (Deribit
rests a market order at its band price and the gateway cancels it), a transient refusal. The timer
therefore stays armed until its close fills (#1360):

- A minute after each close is sent, a close the venue cancelled or rejected is sent again
  (`<exit>-close-r1`, `-r2`, ...), for only what is still open of the leg. A close still working is
  waited for; a filled one ends the timer.
- After 3 failures in a row, and again at 6, 12, ..., qkt raises the operator alert (as for rule exits,
  see [conditions](conditions.md)) naming the strategy, symbol, position still open and the venue's last
  refusal. Retrying goes on.
- A refusal no resend can change ends the timer at once with an `EXIT FAILED` alert instead: a gateway's
  `invalid_request` or `unsupported`, or a close qkt cannot express for the venue. The position stays open
  and must be closed by hand (#1643).
- On a gateway venue every order id fits 45 characters (Bybit's limit, the strictest behind VGP): an id
  that would not, such as a long strategy name's `-exit-close-r12`, is sent as its first characters and a
  hash, so a timed close is never refused for its id.
- The retry state is persisted with the timer, so a restart resumes it.

Backtests are unchanged: the simulators fill every market close.

`STACK_AT` tiers that have not fired yet keep the hold too: it is saved with the tier state, so a
leg that fires after a restart still gets `EXIT AFTER`, timed from its own fill
(`StackExitAfterRestoreTest`).

A resting entry (`LIMIT`/`STOP`, `DAY`/`GTC`) keeps its hold across a restart taken while it is still
working: the waiting timer is saved in `timed-exits.json` with the armed ones and re-attaches to the
restored entry, so its fill arms the exit as usual (#1660). The hold runs from the venue's fill time
when the venue reports it, so a fill qkt hears late (gateway down, daemon restarted) closes at once if
its hold has already passed. A saved timer whose entry was not restored is logged at error level at
start, naming the strategy and symbol. Gateway venues report the fill time; MT5 fills do not carry it
yet, so there the hold runs from when qkt hears the fill.

## Known limitations

- The close is a market order sent on the first tick after the deadline. In live trading it
  fills at the venue's price at that moment; in backtest it fills at the tick's executable price.

## Parity

Backtest and live run the same engine component for this exit, checked per tick in both modes.
`DslExitAfterParityTest` runs each case through both and asserts identical trades, including the
close timestamps.
