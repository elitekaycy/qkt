# Pyramiding — STACK layered entries

Add to a winning position as it moves in your favor. The `STACK` keyword turns one signal into N price-triggered entries, each with its own size, all sharing a common bracket. When the trade fails, only the layers that filled need to be unwound — unfilled layers cancel automatically.

## What it does

- **Trades** Bitcoin on 1-hour candles
- **Buys** on EMA cross-up — that's the seed layer
- **Adds two more layers** as price rises by $200 and $400 above entry
- **Shared bracket** for all three layers: stop-loss at $300 below the seed entry, take-profit at $1000 above
- **Time fence** — if the second/third layer hasn't triggered within 4 hours, abandon it

The classic case for pyramiding: you've identified a momentum signal, you want to enter aggressively as confirmation comes, but you don't want to throw a 3× position on at signal time in case the breakout fails.

## The strategy file

```qkt title="strategies/pyramid.qkt"
STRATEGY pyramid_breakout VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

RULES
    WHEN ema(btc.close, 12) CROSSES ABOVE ema(btc.close, 48)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.05
         STACK 3 SPACING 200 ABOVE WITHIN 4h
         BRACKET {
           STOP_LOSS BY 300,
           TAKE_PROFIT BY 1000
         }
         LOG "pyramid start"
```

What `STACK 3 SPACING 200 ABOVE WITHIN 4h` means:

- `STACK 3` — three layers total (one seed + two adds)
- `SPACING 200` — each subsequent layer triggers 200 points above the previous fill
- `ABOVE` — layers trigger only on upward movement (use `BELOW` for averaging-down strategies)
- `WITHIN 4h` — unfilled layers abandon after 4 hours

If the seed fills at $67,000:

- Layer 1 (seed) fills at market: $67,000
- Layer 2 triggers when price reaches $67,200 (entry + 200)
- Layer 3 triggers when price reaches $67,400 (entry + 400)
- After 4 hours, any unfilled layer cancels

If price hits $67,500 then reverses to $66,700 (stop) — only layers 1+2 filled, both close out at the shared stop.

## How to run it

```bash
./scripts/fetch-dukascopy.sh BTCUSDT 2024-01-01 2024-06-01
qkt backtest strategies/pyramid.qkt --from 2024-01-01 --to 2024-06-01
```

## What to expect

Pyramiding amplifies both wins and losses on the trades where the additional layers fill. A 6-month BTC backtest at 1h might look like:

```text
Trades:           23           (signals; each may be 1-3 layers)
Layers filled:    61
Final realized:   2,150.00
Win rate:         0.391        (counted per signal)
Sharpe (daily):   1.05
Max drawdown:     -480.00
```

The winners are bigger than a non-pyramid version because the extra layers ride the move; the losers are about the same size (only the seed layer fills before the stop).

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
```

This avoids slippage on the adds but may miss the layer if price gaps through the level.

### Trailing the bracket after fills

Out of scope for the DSL today. The bracket is set when the seed fills and stays put. To trail, run a separate rule that modifies the stop based on highest price seen.

## Common gotchas

- **Layers share one bracket.** The stop is set at seed time and applies to all filled layers. If you want each layer to have its own stop, you need separate strategies, not STACK.
- **Time fence is forgiving.** `WITHIN 4h` starts from seed fill time. If layer 2 fills at 3h59m, layer 3 still has 1 minute to trigger — once that minute elapses, layer 3 abandons.
- **Position sign matters for `BELOW` vs `ABOVE`.** `BELOW` only makes sense for long-side averaging-down (price falls = trigger). For short-side average-up, use `ABOVE`.
- **Stack size compounds risk.** Three layers at 0.05 lots = 0.15 lots peak position. Make sure your risk rules account for the max, not the seed.

## What this example demonstrates

- The `STACK` keyword and its modifiers (`SPACING`, `ABOVE`/`BELOW`, `WITHIN`)
- Layer-list form for custom sizing per layer
- The `entry` keyword inside a layer list (refers to seed fill price)
- Shared `BRACKET` across all layers in a stack
- Phase 13a feature in production form
