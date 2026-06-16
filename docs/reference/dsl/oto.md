# OTO — one-triggers-other (`ON_FILL`)

An **OTO** ("one triggers other") is a parent order that carries one or more child orders placed **only when the parent fills**. If the parent never fills — it is cancelled, or expires — the children are dropped and never reach the venue.

This is different from a [BRACKET](bracket.md) (entry plus protective stop/take-profit on the same instrument) and from [STACK](stack.md) (pyramiding more of the same direction). OTO is the general "on fill, place *this specific other order*" relationship: the child can be a **different symbol**, the **opposite side**, and priced **relative to where the parent actually filled**.

```qkt
WHEN gold.close > ema(gold.close, 50)
THEN BUY gold SIZING 1
  ON_FILL { SELL silver SIZING 2 }
```

On the gold buy filling, a market sell of silver is placed — a legged hedge that exists only once the first leg is real.

## Syntax

```
<BUY|SELL> <stream> SIZING <s> [ORDER_TYPE = <type>]
  ON_FILL { <child> [; <child>]* }
```

Each `<child>` is itself a `BUY`/`SELL` action with its own `SIZING` and optional `ORDER_TYPE`. Children are separated by `;`. The parent is a normal order — market, or a pending `LIMIT`/`STOP` that places its children when it triggers.

## Pricing a child off the parent fill

Inside `ON_FILL`, the keyword `entry` refers to the **parent's fill price**, so a child can sit a fixed offset away — the algo-only "scale in / re-enter at a level relative to where I got filled" shape.

```qkt
WHEN breakout
THEN BUY gold SIZING 1 ORDER_TYPE = STOP AT gold.high + 1
  ON_FILL { BUY gold SIZING 0.5 ORDER_TYPE = LIMIT AT entry - 10 }
```

The parent is a stop entry on a breakout; once it fills, a half-size limit scale-in is planted 10 below the actual fill. `entry` is exact for a `LIMIT`/`STOP` parent (it fills at its price) and the signal-time estimate for a `MARKET` parent.

`entry` is only meaningful inside an `ON_FILL` block; using it anywhere else is a compile error.

## Different symbol, opposite side

A child names its own stream and side, so OTO expresses legged spreads and offsetting hedges:

```qkt
-- Enter the cheap leg; on fill, short the rich leg market — a paired spread.
WHEN zscore(eur.close / gbp.close, 96) <= -2.0
THEN BUY eur SIZING 1
  ON_FILL { SELL gbp SIZING 1 }
```

## Lifecycle

The engine ([OrderManager][om]) places the parent, holds the children until the parent fills, then submits them. If the parent is cancelled or expires unfilled, the children are discarded — there is never an orphan child working at the venue without its parent having filled.

[om]: ../../architecture.md

## Limitations (v1)

- The parent carries no `BRACKET`, `OCO`, or `STACK` on the same action — keep the parent a plain entry. (Combine shapes by nesting strategies/rules instead.)
- Children are plain `BUY`/`SELL` orders: no nested `ON_FILL`, `BRACKET`, `OCO`, `STACK`, or per-child `TIF` yet. Children rest as GTC.
- Children cannot target a `BASKET` alias.

## What this composes with

- [Actions](actions.md) — `BUY`/`SELL` are the parent and children
- [SIZING](sizing.md) — parent and each child are sized independently
- [BRACKET](bracket.md) — the on-fill-children shape OTO generalizes
- [STACK](stack.md) — pyramiding into a winner, the same-direction special case
