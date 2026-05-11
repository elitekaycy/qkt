# SIZING

Every way to specify the size of a `BUY` or `SELL` order. Sizing is the single mandatory field on entry actions (unless `DEFAULTS.sizing` is set).

## The six forms

| Form | Meaning |
| --- | --- |
| `SIZING <number>` | Fixed lots / units |
| `SIZING <pct> PCT OF EQUITY` | Percentage of total equity (cash + open P&L) |
| `SIZING <pct> PCT OF BALANCE` | Percentage of cash balance only |
| `SIZING <usd> USD` | Fixed USD notional value |
| `SIZING <pct> PCT RISK` | Risk-based — sized so the stop costs exactly N% |
| `SIZING POSITION(<stream>)` | The full current position quantity (for closes/scaling) |

## Fixed quantity

```qkt
BUY btc SIZING 0.1
```

Buys 0.1 of whatever the venue's unit is. For Bybit spot BTC, that's 0.1 BTC. For MT5 EURUSD, that's 0.1 lots = 10,000 units of base currency.

**When to use:** prototyping, simple strategies, when the venue's lot size aligns with your risk budget at known prices.

**Gotcha:** doesn't scale with account size. A `0.1 lots` size on a $1,000 account is risky; on $100,000 it's tiny. Use percent or risk-based sizing for portable strategies.

## Percent of equity

```qkt
BUY btc SIZING 5.0 PCT OF EQUITY
```

Sizes the position at 5% of `account.equity`. If equity is $10,000, the order is sized to represent $500 of position value (at current price).

**Variants:**

```qkt
SIZING 5.0 PCT OF EQUITY    -- 5% of equity
SIZING 10  PCT OF BALANCE   -- 10% of cash balance only (ignores open P&L)
```

The difference matters when you have unrealized P&L: `EQUITY` includes it (the size scales with open profit/loss), `BALANCE` doesn't.

**When to use:** position sizing that scales with account performance. Good for compounding strategies.

**Gotcha:** doesn't account for stop distance. A 5% position with a tight stop loses very little; with a wide stop loses a lot. Use `PCT RISK` for stop-aware sizing.

## Fixed USD notional

```qkt
BUY btc SIZING 1000 USD
```

Sizes the position to represent exactly $1000 of notional value (at current price). For BTC at $50k, that's 0.02 BTC. For BTC at $60k, that's 0.01666... BTC.

**When to use:** consistent dollar exposure across symbols of different price levels. "I want $1000 of BTC and $1000 of EUR regardless of how each is priced."

**Gotcha:** doesn't scale with account growth. Set once, stays static.

## Risk-based

```qkt
BUY btc SIZING 1.0 PCT RISK STOP_LOSS AT btc.close - atr(btc, 14) * 2
```

Sizes the position so that **hitting the stop-loss costs exactly 1% of equity**. The engine computes:

```text
size = (equity * risk_pct / 100) / stop_distance
```

Where `stop_distance = entry_price - stop_loss_price` for longs.

This is the professional risk-management standard. Every trade risks the same dollar amount regardless of how far the stop is — wide stops get small positions; tight stops get bigger ones.

**Requires a `STOP_LOSS` on the same action.** If you write `SIZING 1.0 PCT RISK` without a stop, the parser rejects it (the engine can't compute size without a stop distance).

**When to use:** any serious live-trading strategy. The discipline alone is worth it.

**Gotcha:** if the stop is very tight, the computed size may exceed `max-position-pct` in your risk rules. The engine rejects the order. Either widen the stop or relax the cap.

## Position-based (for partial closes)

```qkt
SELL btc SIZING POSITION(btc)
```

Sells exactly the current position size — equivalent to `CLOSE btc`. Mostly used in scale-out logic where you want to express "sell half of the current position":

```qkt
SELL btc SIZING POSITION(btc) * 0.5     -- partial close: 50% of position
```

You can multiply, divide, or do any arithmetic on `POSITION(stream)`.

## Using LET to share sizing logic

A common pattern is naming a sizing computation:

```qkt
LET riskPct = 1.0
LET stopDist = atr(btc, 14) * 2

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING riskPct PCT RISK
         STOP_LOSS AT btc.close - stopDist
```

Or via `DEFAULTS`:

```qkt
DEFAULTS {
  sizing = 1.0 PCT RISK
  stopLoss = atr(SYMBOL, 14) * 2
}

RULES
    WHEN ... THEN BUY btc     -- inherits both
```

## Multiple sizings per stack

A `STACK` (pyramid) carries one `SIZING` for the seed layer, but you can override per layer in the layer-list form:

```qkt
BUY btc STACK [
  0.05,                       -- seed: 0.05 lots
  0.10 AT entry + 200,        -- layer 2: 0.10 lots at +200
  0.15 AT entry + 400         -- layer 3: 0.15 lots at +400
]
BRACKET { ... }
```

See [STACK](stack.md) for the full layer-list grammar.

## Common gotchas

- **Sizing is required.** Either on the action or via `DEFAULTS.sizing`. Both missing = parse error.
- **Percent-of-equity ignores stop distance.** Risk-based sizing is usually what you want.
- **Risk-based requires a stop.** Without `STOP_LOSS ...` on the same action, the parser rejects it.
- **Broker minimum sizes.** MT5 brokers enforce a minimum lot (`volumeMin`) and step (`volumeStep`). If your computed size is below the minimum, the order rejects. The error message is broker-specific.
- **Whole-number lots on some venues.** Futures often require integer contracts. A computed size of `0.327` will round (typically down) or reject. Check your venue's specs.
- **Sizing in DSL is venue-side units.** A "size of 0.1" means 0.1 of the venue's unit (lots, contracts, base currency) — not 0.1 USD or 0.1% of anything. Read the broker docs.

## What this composes with

- [Actions](actions.md) — `SIZING` is a modifier on `BUY` / `SELL`
- [BRACKET](bracket.md) — `PCT RISK` sizing requires `STOP_LOSS` in the bracket (or bare)
- [LET](let-defaults.md) — name a sizing value for reuse
- [DEFAULTS](let-defaults.md#defaults) — set a default sizing for the whole strategy
- [Risk-managed example](../../examples/risk-managed.md) — full deployment using `PCT RISK`
