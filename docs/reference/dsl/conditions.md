# Conditions — the WHEN clause

A condition is everything between `WHEN` and `THEN` in a rule. It's a boolean expression — if true, the rule's actions fire.

## Shape

```qkt
WHEN <condition>
THEN <action> [ ; <action> ... ]
```

The `<condition>` is a boolean expression. It can be a simple comparison or a complex compound with `AND` / `OR` / `NOT`.

## Edge-triggered vs level-triggered

This is the single most important thing to understand about qkt conditions.

### Edge-triggered (default)

Most operators are **edge-triggered**: the rule fires on the first tick after the condition transitions from `false` to `true`. Subsequent ticks where the condition stays true do not re-fire.

```qkt
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc SIZING 0.1
```

`CROSSES ABOVE` is edge-triggered by definition. The condition is true **only on the bar where the crossing happens** — even if EMA9 stays above EMA21 for 100 more bars.

### Level-triggered (use explicitly)

A bare comparison like `>` or `<` is **level-triggered for evaluation** — it returns true whenever the relation holds. But the rule's **action gating** is still edge-driven: actions don't repeat on every tick where the condition is true.

```qkt
WHEN btc.close > 50000
THEN LOG INFO "above 50k"
```

This fires the **first** tick where `btc.close > 50000`. If `btc.close` stays above 50k for 100 bars, you get **one** log line, not 100.

If you want true level-triggered firing (do something **every** tick the condition holds), gate the action with a position-state check that resets:

```qkt
WHEN btc.close > 50000 AND POSITION.btc = 0
THEN BUY btc SIZING 0.1     -- fires once per bar where we're flat AND above 50k
                            -- (after a fill, POSITION.btc != 0 so it doesn't re-fire)
```

## Comparison operators

| Operator | Meaning |
| --- | --- |
| `=` or `==` | Equal |
| `!=` or `<>` | Not equal |
| `<` | Less than |
| `<=` | Less than or equal |
| `>` | Greater than |
| `>=` | Greater than or equal |

Both `=` and `==` work for equality; `!=` and `<>` both work for inequality. The DSL is liberal about syntax conventions you might be used to from SQL or C-family languages.

```qkt
WHEN rsi(btc.close, 14) < 30 THEN LOG INFO "oversold"
WHEN account.equity >= 10000 THEN BUY btc SIZING 0.5 PCT
WHEN POSITION.btc = 0 THEN ...
```

## Boolean combinators

```qkt
WHEN <cond1> AND <cond2>            -- both must hold
WHEN <cond1> OR  <cond2>            -- either holds
WHEN NOT <cond>                     -- negation
```

Combine freely:

```qkt
WHEN ema(btc.close, 9) > ema(btc.close, 21)
 AND rsi(btc.close, 14) > 50
 AND NOT (POSITION.btc > 0)
THEN BUY btc SIZING 0.1
```

Whitespace and line breaks are insignificant — break across lines for readability.

Precedence: `NOT` > `AND` > `OR`. Use parentheses if you want explicit grouping:

```qkt
WHEN (a > 0 AND b > 0) OR c > 0     -- (a>0 AND b>0), or c>0
WHEN a > 0 AND (b > 0 OR c > 0)     -- a>0, AND (b>0 OR c>0)
```

## Crosses operators

Edge-triggered, the workhorse of moving-average strategies:

```qkt
<expr_a> CROSSES ABOVE <expr_b>
<expr_a> CROSSES BELOW <expr_b>
```

True only on the bar where `expr_a` crosses the boundary. Examples:

```qkt
ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
rsi(btc.close, 14) CROSSES BELOW 70
btc.close CROSSES ABOVE highest(btc.close, 20)     -- Donchian breakout
```

The second argument can be a constant; `rsi CROSSES BELOW 70` fires on the bar where RSI drops from ≥70 to <70.

## Range checks

### `BETWEEN`

```qkt
<expr> BETWEEN <low> AND <high>
```

True when `low <= expr <= high`. Inclusive on both ends.

```qkt
WHEN rsi(btc.close, 14) BETWEEN 30 AND 70
THEN LOG INFO "in neutral RSI range"
```

### Membership (`IN`)

```qkt
<expr> IN [<a>, <b>, <c>, ...]
```

True when expr equals any of the listed values. Useful for symbol-conditional logic in `FOR EACH` blocks.

## Account / position references

These functions/properties act like read-only stream-field accesses:

| Reference | Returns |
| --- | --- |
| `account.equity` | Current account equity (cash + open P&L) |
| `account.balance` | Cash balance only (excludes unrealized P&L) |
| `POSITION.<stream>` | Net position quantity (positive=long, negative=short, 0=flat) |
| `POSITION.<stream>.pnl` | Open P&L on the current position |
| `POSITION.<stream>.entry_price` | Average entry price |
| `POSITION.<stream>.holding_duration` | How long the position has been open (seconds) |

```qkt
WHEN POSITION.btc = 0
 AND account.equity > 1000
THEN BUY btc SIZING 0.1

WHEN POSITION.btc > 0
 AND POSITION.btc.holding_duration > 4 * 60 * 60     -- 4h (holding_duration is seconds)
 AND POSITION.btc.pnl < 0
THEN CLOSE btc                                                -- time-stop on a losing position
```

## Position-state guards

The most common entry guard pattern:

```qkt
WHEN <signal_condition> AND POSITION.btc = 0
THEN BUY btc ...                  -- enter only when flat
```

The most common exit pattern:

```qkt
WHEN <exit_condition> AND POSITION.btc > 0
THEN CLOSE btc                    -- exit only when long
```

Without the position guard, the entry rule could re-fire on subsequent ticks if the signal stays true (you'd pyramid in). Always guard entries with position state unless you specifically want to add to a position.

## Lookback (`[N]`)

`stream.close[N]` is the close N bars ago. 0 is the current bar, 1 is the previous bar, etc.

```qkt
WHEN btc.close > btc.close[20]              -- BTC up over the past 20 candles
WHEN btc.high[1] > btc.high[2]              -- previous bar's high > one before
```

Out-of-range or negative indices return `null`; comparisons with `null` are `false`. Useful for "early in the run" safety:

```qkt
-- This won't crash on the first tick despite no [20] history yet:
WHEN btc.close > btc.close[20] THEN ...
```

## Combining conditions across streams

Multi-asset strategies often gate one symbol's entry on another symbol's state:

```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
    eth = BACKTEST:ETHUSDT EVERY 1m

RULES
    -- Buy ETH when BTC is in uptrend AND ETH crosses up
    WHEN ema(btc.close, 50) > ema(btc.close, 200)
     AND ema(eth.close, 9) CROSSES ABOVE ema(eth.close, 21)
    THEN BUY eth SIZING 0.1
```

Both streams are evaluated on every candle close (whoever closes first triggers the candle event for that stream). Conditions that mix streams of different timeframes evaluate on the latest closed candle of each.

## Common gotchas

- **Bare comparisons don't repeat-fire.** A rule with `WHEN btc.close > 50000` fires once when the condition first becomes true. To fire repeatedly, gate with position-state (`AND POSITION.btc = 0`) and act on every tick the gate is open.
- **`null` mostly propagates.** If an indicator in a condition isn't warm yet, comparisons against it are undefined and the rule won't fire. The exception is short-circuit logic: `TRUE OR <unwarm>` is `TRUE` and `FALSE AND <unwarm>` is `FALSE` — a side that can't change the outcome doesn't suppress it. e.g. `WHEN in_session OR slow_signal` fires on `in_session` even while `slow_signal`'s indicator is warming.
- **Precedence trap.** `WHEN a AND b OR c` is `(a AND b) OR c`, which is often not what you meant. Use parentheses.
- **`=` vs `==`.** Both work — the parser accepts either. Pick a convention for your project and stick with it.
- **`btc.close[0]` is the current bar.** It's the same as `btc.close` (no `[]`). `btc.close[1]` is the previous bar. Don't off-by-one yourself.

## What this composes with

- [Indicators](indicators.md) — most of what goes in conditions
- [Expressions](expressions.md) — arithmetic, account/position refs, the math helpers
- [Actions](actions.md) — what fires after `THEN`
- [LET](let-defaults.md) — name complex condition fragments for reuse
