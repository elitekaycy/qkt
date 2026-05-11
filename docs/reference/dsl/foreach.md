# FOR EACH

A compile-time macro that applies the same rule body to multiple streams. One source rule becomes N independent rules at parse time — there's no runtime iteration.

## Shape

```qkt
FOR EACH <iter_var> IN <stream1>, <stream2>, ... DO
    <rule body using iter_var>
```

The iteration variable substitutes textually for each stream in the list. Each substitution produces one rule.

## Basic example

```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
    eth = BACKTEST:ETHUSDT EVERY 1m
    sol = BACKTEST:SOLUSDT EVERY 1m

FOR EACH s IN btc, eth, sol DO
    WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
    THEN BUY s SIZING 0.1
         BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 100 PCT }
```

This expands at compile time to three independent rules — one each for btc, eth, sol. The substitution is purely textual at the AST level.

Equivalent without `FOR EACH`:

```qkt
RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 100 PCT }

    WHEN ema(eth.close, 9) CROSSES ABOVE ema(eth.close, 21)
    THEN BUY eth SIZING 0.1 BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 100 PCT }

    WHEN ema(sol.close, 9) CROSSES ABOVE ema(sol.close, 21)
    THEN BUY sol SIZING 0.1 BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 100 PCT }
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
FOR EACH s IN btc, eth, sol DO
    WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
    THEN BUY s SIZING 0.1

FOR EACH s IN btc, eth, sol DO
    WHEN ema(s.close, 9) CROSSES BELOW ema(s.close, 21)
     AND position(s) > 0
    THEN CLOSE s
```

This expands to 6 rules (3 entry rules + 3 exit rules). Note the iteration variable can be reused across blocks — there's no scope collision.

## Mixing with explicit rules

`FOR EACH` and `RULES` can coexist:

```qkt
RULES
    -- Special rule for BTC only
    WHEN btc.close > 70000 AND position(btc) = 0
    THEN BUY btc SIZING 0.5

FOR EACH s IN eth, sol, ada DO
    -- Same rule applied to alts
    WHEN ema(s.close, 12) CROSSES ABOVE ema(s.close, 48)
    THEN BUY s SIZING 0.1
```

Order: explicit `RULES` first, then `FOR EACH` blocks. The DSL parser handles both.

## Iteration variables in nested expressions

`s` is replaced anywhere in the rule body — in stream-field access, in indicator calls, in `position(s)`, in `BUY s`:

```qkt
FOR EACH s IN btc, eth DO
    WHEN ema(s.close, 9) > sma(s.close, 50)             -- s.close
     AND rsi(s.close, 14) > 50
     AND position(s) = 0                                -- position(s)
    THEN BUY s SIZING 1.0 PCT RISK                      -- BUY s
         STOP_LOSS AT s.close - atr(s, 14) * 2          -- atr(s, 14)
```

Three substitutions of `s` per rule for `btc`, three for `eth`, etc.

## Limitations

- **Stream list is static.** `FOR EACH s IN btc, eth, sol DO` — the streams are listed inline. You can't reference a variable or a config-driven list.
- **No nested iteration.** `FOR EACH a IN ... FOR EACH b IN ...` is **not supported**. If you genuinely need a Cartesian product across symbols, write the rules explicitly.
- **No filtering.** `FOR EACH s IN btc, eth, sol WHERE s.kind = "spot"` is **not supported**. Filter the list manually.
- **Iteration variable can't be used in `LET`.** `LET` clauses are file-level; they can't reference the iteration variable.

## Common gotchas

- **The iteration variable shadows declared streams of the same name.** Don't pick `btc` as the iterator if you have a stream called `btc`. The parser may not catch this; the substitution will silently misbehave.
- **`FOR EACH` is end-of-file.** Place it after `RULES`. Putting it before causes a parse error.
- **One iterator per `FOR EACH`.** No tuples; no multiple variables. `FOR EACH s, c IN ...` is invalid.
- **No range syntax.** No `FOR EACH i IN 1..5`. The iteration list is always streams.

## What this composes with

- [Streams](streams.md) — the list of streams comes from `SYMBOLS`
- [Actions](actions.md) — the rule body uses the iteration variable in `BUY <s>`, etc.
- [Conditions](conditions.md) — conditions can reference the iteration variable freely
- [Phase 11e — multi-stream](../../phases/phase-11e-multistream.md) — design notes on the AST-level expansion
