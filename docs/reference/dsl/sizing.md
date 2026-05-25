# SIZING

Every way to specify the size of a `BUY` or `SELL` order. Sizing is the single mandatory field on entry actions (unless `DEFAULTS.sizing` is set).

## The forms available today

| Form | Meaning |
| --- | --- |
| `SIZING <number>` | Fixed lots / units |
| `SIZING <pct> PCT OF EQUITY` | Percentage of total equity (cash + open P&L) |
| `SIZING <pct> PCT OF BALANCE` | Percentage of cash balance only |
| `SIZING <usd> USD` | Fixed USD notional value |
| `SIZING <N> PCT RISK` | Sized so a stop-out loses N% of equity (sugar over `SIZING RISK N/100`) |
| `SIZING POSITION.<stream>` | The full current position quantity (for closes/scaling) |

## Fixed quantity

```qkt
BUY btc SIZING 0.1
```

Buys 0.1 of whatever the venue's unit is. For Bybit spot BTC, that's 0.1 BTC. For MT5 EURUSD, that's 0.1 lots = 10,000 units of base currency.

**When to use:** prototyping, simple strategies, when the venue's lot size aligns with your risk budget at known prices.

**Gotcha:** doesn't scale with account size. A `0.1 lots` size on a $1,000 account is risky; on $100,000 it's tiny. Use percent-based sizing for portable strategies.

## Percent of equity

```qkt
BUY btc SIZING 5.0 PCT OF EQUITY
```

Sizes the position at 5% of `ACCOUNT.equity`. If equity is $10,000, the order is sized to represent $500 of position value (at current price).

**Variants:**

```qkt
SIZING 5.0 PCT OF EQUITY    -- 5% of equity (includes open P&L)
SIZING 10 PCT OF BALANCE    -- 10% of cash balance only (ignores open P&L)
```

The difference matters when you have unrealized P&L: `EQUITY` includes it (the size scales with open profit/loss), `BALANCE` doesn't.

**When to use:** position sizing that scales with account performance. Good for compounding strategies.

## Fixed USD notional

```qkt
BUY btc SIZING 1000 USD
```

Sizes the position to represent exactly $1000 of notional value (at current price). For BTC at $50k, that's 0.02 BTC. For BTC at $60k, that's 0.01666… BTC.

**When to use:** consistent dollar exposure across symbols of different price levels. "I want $1000 of BTC and $1000 of EUR regardless of how each is priced."

## Position-based (for partial closes)

```qkt
SELL btc SIZING POSITION.btc
```

Sells exactly the current position size — equivalent to `CLOSE btc`. Mostly used in scale-out logic:

```qkt
SELL btc SIZING POSITION.btc * 0.5     -- partial close: 50% of position
```

You can multiply, divide, or do any arithmetic on `POSITION.<stream>`.

## Risk-percent sizing

```qkt
BUY btc SIZING 0.5 PCT RISK
    BRACKET { STOP_LOSS AT btc.close - atr(btc, 14) * 2, TAKE_PROFIT RR 3 }
```

Sizes the position so that, if the stop hits, the loss is exactly N% of equity. `SIZING 0.5 PCT RISK` is sugar for `SIZING RISK 0.005` — both compile to the same engine path. Use the PCT form to avoid decimal-shift bugs when expressing small risk fractions: `0.5 PCT RISK` is unambiguous; `RISK 0.005` invites typos.

Requires a `BRACKET` with a `STOP_LOSS` — without one the engine has no stop distance to size against and the order rejects at submission.

**When to use:** the default for portable strategies. Risk-percent sizing scales correctly with account size, stop distance, and instrument volatility.

## Computing risk-based size manually

The same effect is also achievable with `USD` sizing and a `LET` expression — useful when you want the size to factor in something `PCT RISK` doesn't model (e.g. correlation across positions):

```qkt
LET stopDist = atr(btc, 14) * 2
LET riskUsd  = ACCOUNT.equity * 0.01           # 1% of equity at risk
LET riskQty  = riskUsd / stopDist              # size that loses riskUsd if stop hits

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING riskQty
         BRACKET { STOP_LOSS AT btc.close - stopDist, TAKE_PROFIT AT btc.close + stopDist * 3 }
```

This is equivalent to `SIZING 1.0 PCT RISK` with the same bracket — you compute the size from `equity_at_risk / stop_distance` yourself. Reach for the manual form only when you need a sizing expression `PCT RISK` doesn't express.

## Defaults via DEFAULTS

If most of your strategies use the same sizing, hoist it:

```qkt
DEFAULTS {
  sizing = 0.1
}

RULES
    WHEN ... THEN BUY btc     -- inherits sizing = 0.1
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

See [STACK](stack.md).

## Common gotchas

- **Sizing is required.** Either on the action or via `DEFAULTS.sizing`. Both missing = parse error.
- **Percent-of-equity ignores stop distance.** A 5% position with a tight stop loses very little; with a wide stop loses a lot. Use the manual workaround above to factor in the stop.
- **Broker minimum sizes.** MT5 brokers enforce a minimum lot (`volumeMin`) and step (`volumeStep`). If your computed size is below the minimum, the order rejects.
- **Whole-number lots on some venues.** Futures often require integer contracts. A computed size of `0.327` will round (typically down) or reject. Check your venue's specs.
- **Sizing units are venue-side.** A "size of 0.1" means 0.1 of the venue's unit (lots, contracts, base currency) — not 0.1 USD or 0.1% of anything.

## What this composes with

- [Actions](actions.md) — `SIZING` is a modifier on `BUY` / `SELL`
- [BRACKET](bracket.md) — pair sizing with `STOP_LOSS`/`TAKE_PROFIT`
- [LET](let-defaults.md) — name a sizing computation for reuse
- [DEFAULTS](let-defaults.md) — set a default sizing for the whole strategy
- [Planned features](../../planned.md) — `PCT RISK` and what's coming
