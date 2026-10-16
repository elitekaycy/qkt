# Pyramiding — STACK layered entries

Add to a winning position as it moves in your favor. The `STACK` keyword turns one signal into N price-triggered entries. Each layer gets its own copy of the bracket, anchored to its own fill. Unfilled layers cancel automatically after a time fence.

## What it does

- **Trades** Bitcoin (`BTCUSD`) on 1-hour candles
- **Buys** on EMA cross-up — that's the seed layer
- **Adds two more layers** as price rises 200 and 400 points above the seed fill
- **One bracket per layer**: each layer's stop sits 300 below its own fill, its target 1000 above
- **Time fence** — if the second/third layer hasn't triggered within 4 hours, abandon it

The classic case for pyramiding: you've identified a momentum signal, you want to enter aggressively as confirmation comes, but you don't want to throw a 3× position on at signal time in case the breakout fails.

## The strategy file

```qkt title="strategies/pyramid.qkt"
STRATEGY pyramid_breakout VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1h

RULES
    WHEN ema(btc.close, 12) CROSSES ABOVE ema(btc.close, 48)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.05
         STACK 3 SPACING 200 ABOVE WITHIN 4h
         BRACKET {
           STOP_LOSS BY 300,
           TAKE_PROFIT BY 1000
         }
       ; LOG "pyramid start"
```

What `STACK 3 SPACING 200 ABOVE WITHIN 4h` means:

- `STACK 3` — three layers total (one seed + two adds)
- `SPACING 200` — each subsequent layer triggers 200 points above the previous one
- `ABOVE` — layers trigger only on upward movement (use `BELOW` for averaging-down strategies)
- `WITHIN 4h` — unfilled layers abandon 4 hours after the seed fill

If the seed fills at $67,000:

- Layer 1 (seed) fills at market: $67,000, stop $66,700, target $68,000
- Layer 2 triggers when price reaches $67,200: stop $66,900, target $68,200
- Layer 3 triggers when price reaches $67,400: stop $67,100, target $68,400
- After 4 hours, any unfilled layer cancels

If price hits $67,300 then reverses — only layers 1 and 2 filled. Layer 2 closes at its stop
($66,900) first, then layer 1 at $66,700, as two separate fills. See
[STACK → how fills and brackets work together](../reference/dsl/stack.md#how-fills-and-brackets-work-together).

## How to run it

```bash
qkt parse strategies/pyramid.qkt
qkt backtest strategies/pyramid.qkt --from 2026-01-02 --to 2026-09-15 --no-fetch
```

## What to expect

qkt 0.56.2, `BTCUSD` ticks from 2 January to 14 September 2026, the default paper broker and a
10,000 USD account:

```text
Backtest   2026-01-02 to 2026-09-15 (257 trading days)
Account    10,000.00 USD

Result
  Profit             +273.61 USD   (+2.7%)
  Round trips        136 closed, 35 won (25.7%)
  Open at the end    none
  Worst drop         3.4%    biggest fall from a peak
  Worst day          0.7%
  Sharpe ratio       0.77     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

Each filled layer is its own round trip. The report's `trades.csv` shows 71 signals: all 71 seeds
filled, 42 reached the second layer and 23 the third, which is the 136. Pyramiding amplifies
whichever way the trades go after the adds fill; losers that never reach layer 2 stay at one
layer's size. The paper broker fills at mid with no costs, so treat the profit as an upper bound.

## How to adapt it

### Average-down instead of pyramid-up

For mean-reversion-style "buy more as it falls":

```qkt
BUY btc SIZING 0.05
STACK 3 SPACING 200 BELOW WITHIN 4h
BRACKET { STOP_LOSS BY 600, TAKE_PROFIT BY 300 }
```

`BELOW` means each layer fires 200 points **below** the previous fill. Note the much wider stop — you're committing to letting the position move against you before stopping out.

### Custom per-layer sizing

By default each STACK layer carries the same `SIZING`. To weight them differently — say, smaller seed, bigger adds — use a layer list:

```qkt
BUY btc STACK [
  0.05,                       -- seed at market
  0.10 AT entry + 200,        -- 2x size at +200
  0.15 AT entry + 400         -- 3x size at +400
]
BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 1000 }
```

`entry` refers to the seed fill price. This is the form for fully-customized pyramids.

### Limit orders for the adds

`STACK` defaults to market orders for the layers. To use limit orders that wait at a specific price:

```qkt
BUY btc STACK [
  0.05,
  0.10 LIMIT AT entry + 200,
  0.15 LIMIT AT entry + 400
]
BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 1000 }
```

This avoids slippage on the adds but may miss the layer if price gaps through the level.

### Trailing the stop after fills

Give the bracket an armed trailing stop leg; each filled layer trails its own stop:

```qkt
BUY btc SIZING 0.05
STACK 3 SPACING 200 ABOVE WITHIN 4h
BRACKET { STOP LOSS TRAILING 300 AFTER MFE >= 0, TAKE PROFIT BY 2000 }
```

See [STACK → combining with a trailing stop](../reference/dsl/stack.md#combining-with-a-trailing-stop).

## Common gotchas

- **Each layer has its own bracket.** The same distances, measured from each layer's own fill, so a filled stack exits as several separate closes. There is no basket-level stop; for one, write a rule over `POSITION.btc.entry_price` and `CLOSE`.
- **Time fence is forgiving.** `WITHIN 4h` starts from seed fill time. If layer 2 fills at 3h59m, layer 3 still has 1 minute to trigger — once that minute elapses, layer 3 abandons.
- **Position sign matters for `BELOW` vs `ABOVE`.** `BELOW` only makes sense for long-side averaging-down (price falls = trigger). For short-side average-up, use `ABOVE`.
- **Stack size compounds risk.** Three layers at 0.05 lots = 0.15 lots peak position. Make sure your risk rules account for the max, not the seed.

## What this example demonstrates

- The `STACK` keyword and its modifiers (`SPACING`, `ABOVE`/`BELOW`, `WITHIN`)
- Layer-list form for custom sizing per layer
- The `entry` keyword inside a layer list (refers to seed fill price)
- One `BRACKET` clause copied onto every layer, anchored to that layer's fill
