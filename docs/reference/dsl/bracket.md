# BRACKET

A `BRACKET` attaches a stop-loss and a take-profit to an entry as one atomic group. The broker submits them together; when either side fills, the other auto-cancels (one-cancels-other semantics).

## Shape

```qkt
BUY <stream> SIZING <size>
    BRACKET {
      STOP_LOSS <distance_or_price>,
      TAKE_PROFIT <distance_or_price>
    }
```

Or, less verbose, the bare form (without the `BRACKET { ... }` wrapper) when you only need a stop:

```qkt
BUY <stream> SIZING <size>
    STOP_LOSS <distance_or_price>
```

## Distance specifications

Both `STOP_LOSS` and `TAKE_PROFIT` accept these forms:

### `BY <points>` — fixed distance in quote currency

```qkt
BRACKET {
  STOP_LOSS BY 100,
  TAKE_PROFIT BY 300
}
```

For BTC at $67,000 long, stop at $66,900, target at $67,300. The units match the symbol's quote (USD for crypto, pips for FX *but converted to price points*).

### `BY <pct> PCT` — percent of entry price

```qkt
BRACKET {
  STOP_LOSS BY 1.0 PCT,
  TAKE_PROFIT BY 3.0 PCT
}
```

For BTC at $67,000 long, stop at $66,330, target at $69,010.

### `AT <price_expression>` — absolute price

```qkt
BRACKET {
  STOP_LOSS AT btc.close - atr(btc, 14) * 2,
  TAKE_PROFIT AT btc.close + atr(btc, 14) * 6
}
```

Computed at action-execute time. `btc.close` is the entry price (the close of the bar that fired the rule). Arithmetic, indicator calls, and `LET`-bound values all work.

### Mixing

You can mix forms in one bracket:

```qkt
BRACKET {
  STOP_LOSS BY 2.0 PCT,                                  -- percent stop
  TAKE_PROFIT AT btc.close + atr(btc, 14) * 6            -- ATR target
}
```

## Scale-out (multi-leg take-profit)

The take-profit can be a list of partial-exit legs, each with a fraction and a price:

```qkt
BUY btc SIZING 1.0
BRACKET {
  STOP_LOSS BY 2 PCT,
  TAKE_PROFIT {
    0.33 AT btc.close * 1.01,        -- exit 33% at +1%
    0.33 AT btc.close * 1.02,        -- exit 33% more at +2%
    0.34 AT btc.close * 1.05         -- exit final 34% at +5%
  }
}
```

The fractions sum to ≤ 1.0 (a sum > 1.0 is a parse error; < 1.0 leaves a runner that closes on stop or on a later `CLOSE` action).

**When to use:** mean-reversion strategies, scaling out of trending moves to bank certainty.

## Bare `STOP_LOSS` (no bracket)

When the take-profit is rule-driven (e.g. exit on indicator cross), use a bare stop without a bracket wrapper:

```qkt
BUY btc SIZING 0.1
    STOP_LOSS AT btc.close - atr(btc, 14) * 2
```

The engine attaches the stop atomically with the entry. No take-profit means the position runs until either the stop hits or another rule fires `CLOSE`.

## Trailing stop

!!! info "Coming in Phase 25"
    `TRAILING_STOP BY <amount>` is **planned but not yet shipped**. The `TRAILING` token is recognised by the parser but the action-compiler → broker dispatch path isn't wired. See [Planned features](../../planned.md#phase-25-operator-tooling-dsl-extensions).

    **Workaround today:** track the position high in a rule and close manually when price retreats by the trail distance:

    ```qkt
    LET runHigh = highest(btc.close, 50)
    LET trailDist = atr(btc, 14) * 2

    RULES
        WHEN POSITION.btc > 0
         AND btc.close < runHigh - trailDist
        THEN CLOSE btc
             LOG "trailing-stop exit at {price}" price=btc.close
    ```

## How the bracket reaches the broker

The DSL submits one `BRACKET` request to the order manager. From there:

1. **If the broker supports `BRACKET` natively** (MT5 brokers, PaperBroker): the order manager submits the entry + stop + target as one atomic group. The venue handles OCO semantics.

2. **If the broker doesn't** (Bybit Spot via REST): the order manager submits the entry alone. On fill, it submits the stop and target as separate orders linked by OCO state. When one fills, the other auto-cancels via the engine.

The DSL is the same either way. See [Broker integration](../../concepts/broker-integration.md) for the capability matrix.

## Defaults via `DEFAULTS`

If most of your strategies use the same bracket pattern, hoist it:

```qkt
DEFAULTS {
  sizing = 0.1
  stopLoss = atr(SYMBOL, 14) * 2          -- 2-ATR stop
  takeProfit = atr(SYMBOL, 14) * 6        -- 6-ATR target (3R)
}

RULES
    WHEN signal_condition
    THEN BUY btc     -- inherits sizing + stop + target
```

`SYMBOL` substitutes for the rule's stream at compile time. See [LET and DEFAULTS](let-defaults.md).

## Common gotchas

- **Wrong side stop direction.** For a `BUY`, the stop must be **below** the entry price. The parser does check this for absolute prices but can't always check expressions (`btc.close + 100` for a long stop is a logic error). Test on backtest before live.
- **Scale-out fractions > 1.0** — parse error. ≤ 1.0; the remainder stays open as a runner.
- **Bracket stop too close** — MT5 brokers enforce `tradeStopsLevel` minimum distance. Orders too tight reject at the venue. Use `atr * <multiplier>` to scale; if the multiplier produces too-tight stops in low-vol regimes, the order rejects.
- **`TRAILING_STOP` not yet shipped** — see the admonition above. Use a rule-based trail until Phase 25.
- **Limit-entry bracket execution.** When the entry is a limit order (`BUY btc LIMIT AT 67000 BRACKET ...`), the bracket only activates after the limit fills. If the limit never fills, the bracket never sends.

## What this composes with

- [Actions](actions.md) — `BRACKET` is a modifier on `BUY`/`SELL`
- [SIZING](sizing.md) — risk-based sizing (`PCT RISK`) requires a stop in the bracket (or bare)
- [Indicators](indicators.md) — ATR is the canonical stop input
- [Expressions](expressions.md) — arithmetic in stop/target prices
- [Mean-reversion example](../../examples/mean-reversion.md) — heavy use of scale-out brackets
