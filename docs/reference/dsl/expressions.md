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
account.equity         -- cash + open P&L
account.balance        -- cash only
account.realized       -- realized P&L since strategy start
account.unrealized     -- open-position P&L right now
```

```qkt
SIZING account.equity * 0.01 / atr(btc, 14)    -- risk-based size (manual)
```

## Position references

```qkt
position(<stream>)                          -- net quantity (signed)
position(<stream>).pnl                      -- open P&L
position(<stream>).realized                 -- total realized P&L on this symbol
position(<stream>).entry_price              -- average entry price
position(<stream>).holding_duration         -- ms since entry
```

```qkt
WHEN position(btc) > 0
 AND position(btc).pnl > position(btc).entry_price * 0.05    -- 5% in profit
THEN CLOSE btc
```

Note `position(<stream>)` returns a signed quantity. `position(btc) > 0` means long; `position(btc) < 0` means short; `position(btc) = 0` means flat. Many strategies guard entries with `position(btc) = 0`.

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

To explicitly check for null:

```qkt
WHEN ema(btc.close, 200) IS NOT NULL AND btc.close > ema(btc.close, 200)
THEN LOG INFO "above 200 EMA, and indicator is warm"
```

`IS NULL` and `IS NOT NULL` are the explicit checks. In practice you rarely need them; the silent short-circuit handles 99% of cases.

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
- **`null` is opinionated.** Treating null-comparisons as false simplifies most code but can hide bugs. Use `IS NOT NULL` explicitly if you need stricter semantics.

## What this composes with

- [Conditions](conditions.md) — the most common host for expressions
- [Indicators](indicators.md) — produce numbers your expressions consume
- [SIZING](sizing.md) and [BRACKET](bracket.md) — arithmetic in action parameters
- [LET](let-defaults.md) — name a complex expression for reuse
