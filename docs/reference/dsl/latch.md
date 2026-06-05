# LATCH — directional-trigger entry

Arm a pair of price trip-wires (`ref ± offset`) and wait. The first wire the market crosses sets a direction (BUY on the up-wire, SELL on the down-wire) and locks in an anchor price `O`. All entries in the `LATCH` block then resolve relative to `O` and that direction. If no wire is crossed before the arm window elapses, the latch is silently dropped.

The classic use case: a session level sits at $2,000. You don't want to trade the level itself — you want to trade the first confirmed break above or below. `LATCH` arms two wires, waits for the break, then places a retrace limit at a defined distance so you enter after the breakout pullback, not at the spike.

## Shape

```qkt
WHEN <condition>
THEN LATCH <stream> OFFSET <d> [ FROM <ref_expr> ] [ ARM <duration> ] [ AS <name> ] {
    ENTER MARKET | LIMIT <dir_rel> | STOP <dir_rel>
        [ BRACKET { [ STOP LOSS <dir_rel>, ] [ TAKE PROFIT <dir_rel> ] } ]
        [ SIZING <spec> ]
        [ EXPIRE <duration> ]
    [ ; ENTER ... ]
}
```

- `<stream>` — the stream alias whose ticks the latch watches.
- `OFFSET <d>` — distance from the reference to each trip-wire. e.g. `OFFSET 0.50` → up-wire at `ref + 0.50`, down-wire at `ref - 0.50`.
- `FROM <ref_expr>` — optional: overrides the reference. Omit to use `<stream>.close` (the most recent bar's close). e.g. `FROM gold.high`.
- `ARM <duration>` — how long the wires stay active after arming. Defaults to `5m`. Supported suffixes: `s`, `m`, `h`, `d`.
- `AS <name>` — optional name, useful for logging and future addressing.
- `{ ENTER ... }` — one or more entry clauses separated by `;`.

A `WHEN` condition fires the latch arm on each matching candle close. If the condition fires while an arm is already active for this stream, a second arm is queued independently — each arm is tracked separately and fires at most once.

## Direction-relative price notation (`WITH`/`AGAINST`/`RETRACE`)

Entry prices, brackets, and stop distances are written relative to the break direction and the anchor `O`. The table below shows how the keywords resolve for both directions.

| Keyword | Long break (BUY, dir=+1) | Short break (SELL, dir=-1) |
|---------|--------------------------|----------------------------|
| `WITH d` | `O + d` (extends in break direction) | `O - d` (extends in break direction) |
| `AGAINST d` | `O - d` (retraces from break) | `O + d` (retraces from break) |
| `RETRACE d` | same as `AGAINST d` | same as `AGAINST d` |

`WITH` means "move with the break". `AGAINST`/`RETRACE` mean "move against the break" — used to place retrace entries and take profits that give back distance from `O`.

### Worked example — $2,000 level

```
OFFSET 0.50 → up-wire = 2000.50, down-wire = 1999.50
```

A tick at $2000.60 crosses the up-wire → direction = BUY, anchor `O` = 2000.50.

```
ENTER LIMIT RETRACE 4       → entry = 2000.50 - 4 = 1996.50   (BUY LIMIT at the pullback)
BRACKET {
  STOP LOSS AGAINST 12      → SL    = 2000.50 - 12 = 1988.50
  TAKE PROFIT WITH 5        → TP    = 2000.50 + 5  = 2005.50
}
```

If the down-wire had been crossed instead (tick at $1999.40 → SELL, `O` = 1999.50):

```
ENTER LIMIT RETRACE 4       → entry = 1999.50 + 4 = 2003.50   (SELL LIMIT at the pullback)
BRACKET {
  STOP LOSS AGAINST 12      → SL    = 1999.50 + 12 = 2011.50
  TAKE PROFIT WITH 5        → TP    = 1999.50 - 5  = 1994.50
}
```

The same source text adapts automatically to whichever direction breaks first.

## Entry order types

| Syntax | Meaning |
|--------|---------|
| `ENTER MARKET` | Market order at anchor `O` |
| `ENTER LIMIT <dir_rel>` | Limit at `resolve(dir_rel)` — use `RETRACE d` for below-the-break buy |
| `ENTER STOP <dir_rel>` | Stop at `resolve(dir_rel)` — use `WITH d` for above-the-break buy |

Inverted geometry (a BUY LIMIT above the anchor, or a BUY LIMIT above the SL) is skipped at runtime with a WARN log. The remaining entries in the block still fire.

## Modifiers

### `OFFSET <d>` and `FROM <ref_expr>`

```qkt
-- Default: reference is gold.close of the closing bar
LATCH gold OFFSET 2 ARM 10m { ... }

-- Override reference to today's high
LATCH gold OFFSET 2 FROM gold.high ARM 10m { ... }
```

### `ARM <duration>`

How long the wires stay armed after the signal fires. Starts at the clock time of the `WHEN` rule fire (candle close). If no wire is crossed before the arm expires, the latch is dropped silently.

```qkt
LATCH gold OFFSET 0.50 ARM 5m { ... }   -- 5-minute arm window
LATCH gold OFFSET 0.50 ARM 1h { ... }   -- 1-hour arm window
```

### `EXPIRE <duration>` on entries

Limits how long a placed order (LIMIT or STOP entry) stays working before auto-cancelling. Measured from the time the wire is crossed (when the order is placed).

```qkt
ENTER LIMIT RETRACE 4 EXPIRE 2h { ... }  -- cancel limit if not filled within 2h
```

### `AS <name>`

Optional label for the latch. Currently used in log output.

```qkt
LATCH gold OFFSET 0.50 ARM 5m AS brk { ... }
```

## Multiple entries (laddering)

Separate entries with `;` inside the `{ }` block. All entries resolve independently when the wire is crossed. This is the retrace-ladder pattern: three limit orders at increasing depths, all anchored to the same break.

```qkt
LATCH gold OFFSET 0.50 ARM 5m {
    ENTER LIMIT RETRACE 2 BRACKET { STOP LOSS AGAINST 12, TAKE PROFIT WITH 5 } SIZING 1 ;
    ENTER LIMIT RETRACE 4 BRACKET { STOP LOSS AGAINST 12, TAKE PROFIT WITH 5 } SIZING 1 ;
    ENTER LIMIT RETRACE 6 BRACKET { STOP LOSS AGAINST 12, TAKE PROFIT WITH 5 } SIZING 1
}
```

## Tiebreak rule

If a single tick straddles both wires (i.e., crosses both up and down simultaneously — unusual but possible with a wide spread or gap), the **up-wire wins**: direction is BUY, anchor is the up-wire price.

## Constraints

- **Distances must be numeric literals.** `OFFSET`, `RETRACE`, `WITH`, and `AGAINST` distances must be numeric literals. `LET` references, indicator calls, `NOW.<field>`, and runtime expressions are not resolved inside latch distance expressions and will cause a compile error.
- **Geometry validation.** A BUY LIMIT above the entry anchor, or a SL above a BUY entry, is skipped at runtime with an WARN log. The compiler cannot detect this because the direction is only known at wire-cross time.
- **Transient arm — no persistence.** Armed latches live in memory only. A restart mid-arm drops them silently. The strategy re-arms on the next qualifying candle close.
- **At most one fire per arm.** A latch fires on the first wire cross, then removes itself. It does not re-arm automatically.

## Full example

```qkt
STRATEGY latch_demo VERSION 1

SYMBOLS
    gold = BACKTEST:XAUUSD EVERY 5m

RULES
    WHEN NOW.minute_utc = 0 AND POSITION.gold = 0
    THEN LATCH gold OFFSET 0.50 ARM 5m {
        ENTER LIMIT RETRACE 4
            BRACKET { STOP LOSS AGAINST 12, TAKE PROFIT WITH 5 }
            SIZING RISK $ 250
            EXPIRE 2h
    }
```

At the top of every hour, if flat, the strategy arms wires 0.50 points either side of the current bar's close. The first break within 5 minutes places a retrace LIMIT buy (or sell) with a shared bracket. The limit auto-cancels after 2 hours if unfilled.

## What this composes with

- [SIZING](sizing.md) — all sizing forms supported; stop distance for risk sizing is computed statically as `|SL_dist - entry_dist|` so distances must be constants
- [BRACKET](bracket.md) — per-entry bracket; each entry in the `{ }` block has its own
- [LET and DEFAULTS](let-defaults.md) — `LET` bindings can supply the constant distances
- [Actions](actions.md) — `LATCH` is an action; it can appear alongside `LOG` in a `BLOCK`
- [Conditions (WHEN)](conditions.md) — the `WHEN` condition gates arm frequency
