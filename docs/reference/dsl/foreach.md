# FOR EACH

A compile-time macro that applies the same rule body to multiple streams. One source rule becomes N independent rules at parse time — there's no runtime iteration.

## Shape

<!-- qkt-doc: grammar -->
```qkt
RULES
    FOR EACH <iter_var> IN [<stream1>, <stream2>, ...] DO
      <rule body using iter_var>
```

`FOR EACH` goes inside `RULES`, and the stream list needs its square brackets. Over a [symbol set](streams.md#symbol-sets-exnesseurusd-xauusd-alpaca-where-)
declared in `SYMBOLS` the brackets go away:

<!-- qkt-doc: grammar -->
```qkt
RULES
    FOR EACH <iter_var> IN <set> DO
      <rule body using iter_var>
```

```qkt
SYMBOLS
    fx = EXNESS:{EURUSD, XAUUSD, USDJPY} EVERY 15m

RULES
    FOR EACH m IN fx DO
      WHEN rsi(m.close, 14) < 30 AND POSITION.m = 0
      THEN BUY m SIZING 0.1
```

A picked list (`VENUE:{A, B}`) and a union of them expand at parse time exactly like the bracket form: one rule
per member, with `m` replaced by the member's alias (`fx.EURUSD`). A filtered set (`VENUE:* WHERE ...`) keeps the
rule as a template until a backtest resolves its members at start, then expands the same way.

The iteration variable substitutes textually for each stream in the list. Each substitution produces one rule.

## Basic example

```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
    eth = BACKTEST:ETHUSDT EVERY 1m
    sol = BACKTEST:SOLUSDT EVERY 1m

RULES
    FOR EACH s IN [btc, eth, sol] DO
      WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
      THEN BUY s SIZING 0.1
           BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }
```

This expands at compile time to three independent rules — one each for btc, eth, sol. The substitution is purely textual at the AST level.

Equivalent without `FOR EACH`:

```qkt
RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }

    WHEN ema(eth.close, 9) CROSSES ABOVE ema(eth.close, 21)
    THEN BUY eth SIZING 0.1 BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }

    WHEN ema(sol.close, 9) CROSSES ABOVE ema(sol.close, 21)
    THEN BUY sol SIZING 0.1 BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }
```

`FOR EACH` is just syntactic sugar — both versions compile to identical bytecode (well, AST nodes).

## When to use

- **Multi-asset strategies** where the same logic applies to every symbol
- **Sector / thematic baskets** — apply a momentum rule to a list of stocks
- **Multi-timeframe** when you want one rule across multiple timeframe streams of the same symbol

## When NOT to use

- **Streams with different parameters.** If you want different EMAs per symbol (e.g. `EMA(9)` for BTC but `EMA(20)` for EUR), write rules explicitly.
- **Cross-stream conditions.** A condition like `WHEN btc.close > eth.close` is a single rule, not a per-stream pattern. `FOR EACH` doesn't help.
- **Variable rule bodies.** The body must be identical except for the iteration variable. Conditional shape per symbol means manual rules.

## Multiple iterations

You can have multiple `FOR EACH` blocks in one file:

```qkt
FOR EACH s IN [btc, eth, sol] DO
    WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
    THEN BUY s SIZING 0.1

FOR EACH s IN [btc, eth, sol] DO
    WHEN ema(s.close, 9) CROSSES BELOW ema(s.close, 21)
     AND POSITION.s > 0
    THEN CLOSE s
```

This expands to 6 rules (3 entry rules + 3 exit rules). Note the iteration variable can be reused across blocks — there's no scope collision.

## Mixing with explicit rules

`FOR EACH` is a rule inside `RULES`, so it can sit before, between or after plain `WHEN` rules in
any order:

```qkt
RULES
    FOR EACH s IN [eth, sol, ada] DO
      -- Same rule applied to alts
      WHEN ema(s.close, 12) CROSSES ABOVE ema(s.close, 48)
      THEN BUY s SIZING 0.1

    -- Special rule for BTC only
    WHEN btc.close > 70000 AND POSITION.btc = 0
    THEN BUY btc SIZING 0.5

    FOR EACH s IN [eth, sol, ada] DO
      WHEN ema(s.close, 12) CROSSES BELOW ema(s.close, 48) AND POSITION.s > 0
      THEN CLOSE s
```

The expanded rules keep this order, so the alt entries are evaluated before the BTC rule and the
alt exits after it.

## Iteration variables in nested expressions

`s` is replaced anywhere in the rule body — in stream-field access, in indicator calls, in `POSITION.s`, in `BUY s`:

```qkt
FOR EACH s IN [btc, eth] DO
    WHEN ema(s.close, 9) > sma(s.close, 50)             -- s.close
     AND rsi(s.close, 14) > 50
     AND POSITION.s = 0                                -- POSITION.s
    THEN BUY s SIZING 1.0 PCT RISK                      -- BUY s
         BRACKET { STOP_LOSS AT s.close - atr(s, 14) * 2,   -- atr(s, 14)
                   TAKE_PROFIT AT s.close + atr(s, 14) * 4 }
```

Three substitutions of `s` per rule for `btc`, three for `eth`, etc.

## Cross-member functions

Inside `FOR EACH m IN <set>`, a rule can read the whole set at once. The set is the last argument, by name, and the
expression is written once with the loop variable, which ranges over the set's members inside the call:

| Function | Value in the rule for member `m` |
|---|---|
| `RANK_OF(expr, set)` | 1 + the number of members whose `expr` is strictly greater than `m`'s (1 = highest; ties share a rank) |
| `SUM_OF(expr, set)` | sum of `expr` over the members |
| `AVG_OF(expr, set)` | mean of `expr` over the members |
| `MAX_OF(expr, set)`, `MIN_OF(expr, set)` | largest / smallest `expr` over the members |
| `COUNT_OF(test, set)` | how many members the test holds for |

```qkt
STRATEGY fx_rotation VERSION 1
SYMBOLS
    fx = EXNESS:{EURUSD, GBPUSD, AUDUSD, USDJPY} EVERY 1h
RULES
    FOR EACH m IN fx DO
      WHEN RANK_OF(m.close / m.close[20] - 1, fx) <= 2 AND POSITION.m = 0
       AND COUNT_OF(m.close > ema(m.close, 50), fx) >= 3
      THEN BUY m SIZING 0.1
    FOR EACH m IN fx DO
      WHEN m.close / m.close[20] - 1 < AVG_OF(m.close / m.close[20] - 1, fx) AND POSITION.m > 0
      THEN CLOSE m
```

- **A member with no value yet is left out**, as SQL aggregates skip null: a member still warming up (or with no
  bar yet) does not count. `SUM_OF`, `AVG_OF`, `MAX_OF` and `MIN_OF` are undefined only when no member has a value;
  `RANK_OF` is undefined while `m`'s own value is; `COUNT_OF` counts the members whose test is true and is 0 when
  none is. This differs from `rank_of(a, b, c)` with peers listed by hand, which is undefined while any one of
  them is.
- The named set may be another set than the loop's (a union of picked sets included); `RANK_OF` then ranks `m`'s
  value among that set's values. Both sets must resolve at the same time: two picked lists, or two filtered sets
  in a backtest.
- The expression must use the loop variable: `SUM_OF(gold.close, fx)` is refused, since it would be one value
  counted once per member. A cross-member function cannot sit inside another's expression.
- Outside `FOR EACH`, and over a name that is not a symbol set, these functions are refused.
- Each member's value is read when that member's rule runs, the same cross-section the same rules written out by
  hand with `rank_of(a, b, c)` read (`PickedSetEquivalenceTest` proves they trade identically).

## Limitations

- **Stream list is static.** `FOR EACH s IN [btc, eth, sol] DO` — the streams are listed inline. A symbol set
  (`FOR EACH m IN fx DO`) is the one indirection: its members come from the declaration, or from the catalog at the
  start of a backtest, never from a variable.
- **No nested iteration.** `FOR EACH a IN ... FOR EACH b IN ...` is **not supported**. If you genuinely need a Cartesian product across symbols, write the rules explicitly.
- **No filtering in the loop.** `FOR EACH s IN [btc, eth, sol] WHERE s.kind = "spot"` is **not supported**. Filter
  at the declaration instead: `spot = BINANCE:* WHERE kind = spot TOP 20 BY contract_size EVERY 1m`.
- **Iteration variable can't be used in `LET`.** `LET` clauses are file-level; they can't reference the iteration variable.

## Common gotchas

- **The iteration variable shadows declared streams of the same name.** Don't pick `btc` as the iterator if you have a stream called `btc`. The parser may not catch this; the substitution will silently misbehave.
- **`FOR EACH` lives inside `RULES`.** Placing it before the `RULES` keyword is a parse error; anywhere among the `WHEN` rules is fine. The stream list needs its brackets: `FOR EACH s IN btc, eth DO` is rejected with `expected '[' to open stream alias list (a symbol set needs none: IN <set> DO)`.
- **A bare name after `IN` must be a set.** `FOR EACH s IN btc DO` over a plain stream `btc` is rejected: `'btc' is not a symbol set declared in SYMBOLS (a list of streams needs brackets: [btc])`.
- **One iterator per `FOR EACH`.** No tuples; no multiple variables. `FOR EACH s, c IN ...` is invalid.
- **No range syntax.** No `FOR EACH i IN 1..5`. The iteration list is always streams.

## What this composes with

- [Streams](streams.md) — the list of streams comes from `SYMBOLS`
- [Actions](actions.md) — the rule body uses the iteration variable in `BUY <s>`, etc.
- [Conditions](conditions.md) — conditions can reference the iteration variable freely
- [Phase 11e — multi-stream](../../phases/phase-11e-multistream.md) — design notes on the AST-level expansion
