# STACK — pyramiding

Turn one entry signal into N layered orders. Each layer fires at its own price; all layers share a common `BRACKET`. Unfilled layers cancel automatically when the trade exits or after a time fence.

The classic use case: an entry signal is right but you don't want full size on day 1 — you'd rather add to a winning trade as confirmation comes. `STACK` is qkt's pyramiding primitive.

## Shape — compact form

```qkt
BUY <stream> SIZING <seed_size>
    STACK <n> SPACING <points> <ABOVE|BELOW> [ WITHIN <duration> ]
    BRACKET { ... }
```

- `STACK <n>` — total layers, including the seed (so `STACK 3` = 1 seed + 2 adds)
- `SPACING <points>` — distance between consecutive layers (price points)
- `ABOVE|BELOW` — direction: `ABOVE` adds on favorable moves (pyramid-up); `BELOW` adds on adverse moves (average-down)
- `WITHIN <duration>` — abandon unfilled layers after this duration from seed fill

### Pyramid-up example

```qkt
BUY btc SIZING 0.05
    STACK 3 SPACING 200 ABOVE WITHIN 4h
    BRACKET {
      STOP_LOSS BY 300,
      TAKE_PROFIT BY 1000
    }
```

If the seed fills at $67,000:

- **Layer 1 (seed)** — fills at market: $67,000
- **Layer 2** — triggers when price reaches $67,200 (seed + 200)
- **Layer 3** — triggers when price reaches $67,400 (seed + 400)
- After 4 hours, any unfilled layer abandons

If price hits $67,500 then reverses to $66,700 (stop), only layers 1+2 filled, both close out at the shared stop.

### Average-down example

```qkt
BUY btc SIZING 0.05
    STACK 3 SPACING 200 BELOW WITHIN 4h
    BRACKET {
      STOP_LOSS BY 600,        -- wide stop — we're committing to averaging
      TAKE_PROFIT BY 300
    }
```

`BELOW` adds each layer 200 points below the previous fill. Mean-reversion style — buy more as it drops. **Note the much wider stop** — averaging-down commits you to letting the position move against you before stopping out.

## Shape — layer-list form (custom per-layer)

For fully customized pyramids — different sizes per layer, different prices, mixed market/limit:

```qkt
BUY <stream> STACK [
  <layer_1>,
  <layer_2>,
  ...
]
BRACKET { ... }
```

Each layer:

```qkt
<size>                          -- seed: fires at market
<size> AT <price_expr>          -- triggers at price (market order on touch)
<size> LIMIT AT <price_expr>    -- limit order at price (waits)
```

### Custom-weighted pyramid

```qkt
BUY btc STACK [
  0.05,                         -- seed: 0.05 lots at market
  0.10 AT entry + 200,          -- 2x size at +200
  0.15 AT entry + 400           -- 3x size at +400
]
BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 1000 }
```

The variable `entry` is the seed fill price — usable inside layer expressions only.

### Limit-order adds (no slippage)

```qkt
BUY btc STACK [
  0.05,
  0.10 LIMIT AT entry + 200,    -- waits at $67,200 as a resting limit
  0.15 LIMIT AT entry + 400
]
BRACKET { ... }
```

Limit layers avoid slippage on the adds but may miss the layer if price gaps through.

### Average-down with custom sizes

```qkt
BUY btc STACK [
  0.05,
  0.10 AT entry - 200,          -- buy more when down 200
  0.15 AT entry - 400           -- buy even more when down 400
]
BRACKET { STOP_LOSS BY 800, TAKE_PROFIT BY 400 }
```

## How fills and brackets work together

When a layer fills, the **shared bracket** applies to the **combined position**. The stop-loss and take-profit prices are set once at seed-fill time and don't move as more layers fill (unless you also use `TRAILING_STOP`).

Example: seed fills at $67,000 with `BRACKET { STOP_LOSS BY 300 }` → shared stop at $66,700. When layer 2 fills at $67,200, the stop remains at $66,700 — layer 2's individual loss tolerance is $500, not $300.

This is by design — the stop is the **portfolio-level safety net**, not per-layer. If you want per-layer stops, you need separate strategies, not STACK.

## Time fences

`WITHIN <duration>` abandons unfilled layers after a deadline measured from the seed fill.

```qkt
STACK 3 SPACING 200 ABOVE WITHIN 4h    -- 4-hour deadline
STACK 5 SPACING 100 ABOVE WITHIN 30m   -- 30-minute deadline
```

If the second/third/etc. layer hasn't triggered before the deadline elapses, its pending order cancels. The position you already have stays open (managed by the bracket).

Duration suffixes: `s` (seconds), `m` (minutes), `h` (hours), `d` (days).

## Sizing in STACK

Compact form: all layers share the same `SIZING` value (or `DEFAULTS.sizing`).

Layer-list form: each layer carries its own size. The leading literal in each entry is the layer's `SIZING <value>` — same form as a non-STACK action's sizing.

```qkt
BUY btc STACK [
  1.0 PCT RISK,                       -- seed: 1% risk
  2.0 PCT RISK AT entry + 200,        -- next layer: 2% risk
  3.0 PCT RISK AT entry + 400         -- last layer: 3% risk
]
STOP_LOSS AT entry - atr(btc, 14) * 2
```

This pyramids risk — each successful layer commits more equity. Common in trend-following.

## Order matters

Layers in the list trigger **in order**. Layer 2 can't fill before layer 1 (the seed). Layer 3 can't fill before layer 2. Each layer waits for the previous one's trigger.

If you want layers that all fire independently on conditions, you don't want STACK — you want separate rules.

## Combining with TRAILING_STOP

A trailing stop on a STACK trails relative to the **highest favorable price seen across all fills**:

```qkt
BUY btc SIZING 0.05
    STACK 3 SPACING 200 ABOVE WITHIN 4h
    BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 2000 }
    TRAILING_STOP BY 1 PCT
```

As price rises and more layers fill, the trail follows the highest point. Locks in profit on the whole stacked position as the trend continues.

## Common gotchas

- **`STACK N` includes the seed.** `STACK 3` = 1 seed + 2 adds, not 1 + 3.
- **Direction matters.** `ABOVE` for buys = pyramid-up; `BELOW` for buys = average-down. For sells, it's reversed: `ABOVE` for sells = average-up (short adds as price rises against you); `BELOW` for sells = pyramid-down (short adds as price falls in your favor).
- **Shared bracket = portfolio stop.** Don't expect each layer to have its own stop. If you need that, run separate strategies.
- **Time fence starts at seed fill.** Not at signal time. If your seed is a limit that takes 20 minutes to fill, the 4h timer starts after the 20 minutes.
- **Margin/sizing checks happen at each layer.** A layer that would exceed `max-position-pct` is rejected at fill time, not pre-emptively cancelled. The seed succeeds; later layers may fail.
- **Cancel via `CANCEL <stream>`.** Cancels pending stack layers but leaves filled position alone. Pair with `CLOSE <stream>` for a full unwind.

## What this composes with

- [SIZING](sizing.md) — per-layer sizing in the layer-list form
- [BRACKET](bracket.md) — shared stop/target for the stack
- [Actions](actions.md) — `CANCEL <stream>` to abandon unfilled layers
- [Pyramiding example](../../examples/pyramiding.md) — full deployment with commentary
- [Phase 13a — STACK](../../phases/phase-13a-stack.md) — design notes
