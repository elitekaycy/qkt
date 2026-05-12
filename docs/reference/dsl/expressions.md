# Expressions

The values you can compute inside conditions, action parameters, and `LET` bindings. Everything in qkt — numbers, booleans, indicator values, account state — composes the same way.

## Literals

```qkt
100               -- integer
1.5               -- decimal
0.001             -- decimal
1e-3              -- scientific notation
"hello"           -- string (mostly for LOG)
'BUY'             -- single-quoted string also works
TRUE              -- boolean
FALSE
NULL              -- null literal (rare; usually you get it from missing data)
```

Numbers are parsed as exact decimals internally (BigDecimal). No floating-point drift over thousands of trades.

## Arithmetic

```qkt
a + b             -- addition
a - b             -- subtraction
a * b             -- multiplication
a / b             -- division
a % b             -- modulo
-a                -- unary negation
```

Standard precedence: `* / %` before `+ -`. Use parentheses for clarity:

```qkt
WHEN (btc.high - btc.low) / btc.close > 0.02     -- 2% range
THEN LOG INFO "volatile bar"
```

## Comparison and boolean

Covered in [Conditions](conditions.md):

- `=` / `==`, `!=` / `<>`, `<`, `<=`, `>`, `>=`
- `AND`, `OR`, `NOT`
- `BETWEEN ... AND ...`
- `IN [...]`
- `CROSSES ABOVE`, `CROSSES BELOW`

## Stream field access

```qkt
btc.open
btc.high
btc.low
btc.close
btc.volume
btc.timestamp
btc.bid           -- optional (ticks with bid/ask only)
btc.ask
btc.spread        -- ask - bid, computed when both present
btc.mid           -- (bid + ask) / 2
```

Lookback:

```qkt
btc.close         -- current closed candle's close
btc.close[0]      -- same as btc.close
btc.close[1]      -- previous candle
btc.close[N]      -- N bars ago
```

Out-of-range returns `null` (which makes any containing comparison `false`).

## Indicator calls

See [Indicators](indicators.md) for the full catalog.

```qkt
ema(btc.close, 9)
rsi(btc.close, 14)
atr(btc, 14)
highest(btc.close, 20)
```

Treat them as numbers — they slot into any arithmetic context.

## Account references

```qkt
ACCOUNT.equity         -- cash + open P&L
ACCOUNT.balance        -- cash only
ACCOUNT.realized_pnl   -- realized P&L since strategy start
ACCOUNT.unrealized_pnl -- open-position P&L right now
ACCOUNT.total_pnl      -- realized + unrealized
```

```qkt
LET riskUsd = ACCOUNT.equity * 0.01            -- 1% of equity at risk
LET riskQty = riskUsd / (atr(btc, 14) * 2)     -- size that loses riskUsd on a 2-ATR stop
```

## Position references

```qkt
POSITION.<stream>                           -- net quantity (signed) — same as POSITION.<stream>.quantity
POSITION.<stream>.quantity                  -- explicit form
POSITION.<stream>.entry_price               -- average entry price
POSITION.<stream>.pnl                       -- strategy realized + this-symbol unrealized
POSITION.<stream>.realized_pnl              -- strategy-level realized P&L (see note)
POSITION.<stream>.unrealized_pnl            -- open P&L on this position
POSITION.<stream>.holding_duration          -- ms since the position was opened
POSITION.<stream>.mfe                       -- max favorable excursion of the PRIMARY leg (price units)
```

```qkt
WHEN POSITION.btc > 0
 AND POSITION.btc.unrealized_pnl > POSITION.btc.entry_price * 0.05    -- 5% in profit
THEN CLOSE btc
```

`POSITION.<stream>.mfe` reads the high-water mark of `current_price - entry_price` (for BUY) or `entry_price - current_price` (for SELL) on the PRIMARY leg since it opened. Returns `0` if no primary exists. Same value the stack engine uses for `STACK_AT MFE >= ...` threshold checks; see [STACK_AT](stack-at.md).

`POSITION.<stream>` returns a signed quantity. `POSITION.btc > 0` means long; `POSITION.btc < 0` means short; `POSITION.btc = 0` means flat. Most entry rules guard with `POSITION.btc = 0`.

!!! note "realized_pnl is currently strategy-level"
    `POSITION.<stream>.realized_pnl` returns the strategy's total realized P&L, not the per-symbol slice. True per-symbol realized requires lot-level accounting — tracked on the [backlog](../../planned.md#phase-26-exploratory).

## Conditional expressions (`CASE`)

```qkt
CASE
  WHEN <cond1> THEN <expr1>
  WHEN <cond2> THEN <expr2>
  [ ELSE <default_expr> ]
END
```

```qkt
LET sizing = CASE
  WHEN atr(btc, 14) > 200 THEN 0.05        -- volatile: small size
  WHEN atr(btc, 14) > 100 THEN 0.10        -- normal
  ELSE 0.15                                -- quiet: bigger
END

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING sizing
```

`CASE` is an **expression**, not a control-flow statement. It evaluates and returns a value; the surrounding context (here `LET sizing = ...`) decides what to do with it.

If no `WHEN` matches and there's no `ELSE`, the expression returns `null`.

## Math helpers

```qkt
abs(<expr>)
max(<a>, <b>)
min(<a>, <b>)
sqrt(<expr>)
log(<expr>)
exp(<expr>)
floor(<expr>)
ceil(<expr>)
round(<expr>)
pow(<base>, <exp>)
```

```qkt
LET vol_norm = (btc.close - sma(btc.close, 20)) / atr(btc, 14)
LET signal_strength = abs(vol_norm)

WHEN signal_strength > 2 THEN LOG INFO "strong dislocation" z=vol_norm
```

## Aggregates

```qkt
sum(<expr>, <period>)        -- rolling sum
avg(<expr>, <period>)        -- = sma
count(<predicate>, <period>) -- count of true
```

```qkt
LET pct_up_days = count(btc.close > btc.close[1], 20) / 20
WHEN pct_up_days > 0.7 THEN LOG INFO "70%+ of last 20 bars up"
```

## Null handling

Expressions return `null` when:

- An indicator isn't warm yet
- A lookback `[N]` is out of range
- A division has denominator 0
- A function gets unexpected input

`null` propagates through arithmetic: `null + 5 = null`. Comparisons with `null` always return `false`. This means **conditions short-circuit safely during warmup** — your rule simply doesn't fire while data is missing.

!!! info "Explicit `IS NULL` / `IS NOT NULL` coming in Phase 24"
    Phase 24 will add the explicit checks. See [Planned features](../../planned.md#phase-24-risk-sizing-primitives). Today, the silent short-circuit handles every case where you'd want them — your rule simply doesn't fire while an indicator is null.

## Type rules (loose)

The DSL is dynamically typed at the expression level. Most operations coerce sensibly:

- Number + Number → Number
- Number + Null → Null
- Boolean AND/OR Boolean → Boolean
- Comparing Number to Number → Boolean
- Comparing Number to Null → False
- String concat is not supported in conditions; strings are only valid in `LOG` action arguments

Mixing types in arithmetic produces a compile error: `5 + "hello"` is a parse-time failure.

## Common gotchas

- **Division by zero returns `null`, not infinity or an error.** A divide-by-zero in a condition makes the condition `false`. Be aware.
- **Operator precedence.** `AND` binds tighter than `OR`. Parentheses are free.
- **`a == b` vs `a = b`** — both work. Pick one and stick with it for the project.
- **No string operations in conditions.** Strings are for `LOG` only. Don't try `WHEN btc.symbol = "BTCUSDT"` (the parser doesn't expose `symbol` on streams).
- **`null` is opinionated.** Treating null-comparisons as false simplifies most code but can hide bugs. Explicit `IS NULL`/`IS NOT NULL` lands in Phase 24.

## What this composes with

- [Conditions](conditions.md) — the most common host for expressions
- [Indicators](indicators.md) — produce numbers your expressions consume
- [SIZING](sizing.md) and [BRACKET](bracket.md) — arithmetic in action parameters
- [LET](let-defaults.md) — name a complex expression for reuse
