# Actions — BUY, SELL, CLOSE, CANCEL, LOG

The verbs that go after `THEN`. Each action is a complete imperative — "do this exact thing." A rule can have multiple actions separated by `;` or newlines.

## The action verbs

| Verb | What it does |
| --- | --- |
| `BUY <stream> ...` | Open or add to a long position |
| `SELL <stream> ...` | Open or add to a short position |
| `CLOSE <stream>` | Flatten the position on this stream |
| `CLOSE_ALL` | Flatten every open position |
| `FLATTEN` | Alias for `CLOSE_ALL` — reads better in session-end / risk-off rules |
| `RESIZE <stream> TO <sizing>` | Trim or add to set the position to a per-bar target size, without close+reopen |
| `CANCEL <stream>` | Cancel any pending orders on this stream |
| `CANCEL_ALL` | Cancel every pending order |
| `OCO_ENTRY { leg1, leg2 }` | Two pending entries linked one-cancels-other; whichever fills, the other auto-cancels |
| `LOG [WARN|ERROR|DEBUG] "<msg>" [field=expr ...]` | Emit a structured log line (default level is INFO) |

`FLATTEN` and `CLOSE_ALL` compile to the same engine path; pick whichever reads more naturally:

```qkt
WHEN NOW.hour_utc = 21 THEN FLATTEN          -- session close
WHEN ACCOUNT.realized_pnl < -1000 THEN FLATTEN   -- daily loss kill switch
```

## `BUY <stream>` and `SELL <stream>`

The entry verbs. Both take the same set of modifiers.

```qkt
BUY <stream>
    [ SIZING <size_spec> ]
    [ <order_type_modifier> ]
    [ BRACKET { ... } ]
    [ STACK ... ]
    [ TIF <gtc|ioc|fok|day> ]
```

Trailing-stop order types ship as `ORDER_TYPE = TRAILING BY <distance>` and `ORDER_TYPE = TRAILING PCT <fraction>` (see [Trailing stop](../../how-to/add-stop-loss.md#trailing-stop) in the stop-loss recipe).

### Minimal BUY (uses DEFAULTS)

```qkt
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc
```

This is valid only if `DEFAULTS { sizing = ... }` is set (otherwise the parser complains). Sizing is the only field without a sensible compile-time default.

### Full BUY

```qkt
BUY btc
    SIZING 1.0 PCT RISK
    BRACKET {
      STOP_LOSS AT btc.close - atr(btc, 14) * 2,
      TAKE_PROFIT AT btc.close + atr(btc, 14) * 6
    }
    TIF GTC
```

`SELL` is identical in shape, just opens a short instead of a long.

### Order type modifiers

By default `BUY`/`SELL` submit market orders. To submit a limit order:

```qkt
BUY btc SIZING 0.1 LIMIT AT 67000      -- limit order at $67,000
```

To submit a stop entry (buy on breakout above a level):

```qkt
BUY btc SIZING 0.1 STOP AT 67500       -- triggers when price hits $67,500
```

!!! info "Stop-limit and if-touched coming in Phase 25"
    `STOP_LIMIT AT … LIMIT_PRICE …` and `IF_TOUCHED AT …` are **planned but not yet shipped**. See [Planned features](../../planned.md). Today, only `MARKET`, `LIMIT AT`, and `STOP AT` are supported as entry order types.

The order type modifier replaces the default `MARKET` and goes right after the stream/sizing.

## `CLOSE <stream>` and `CLOSE_ALL`

`CLOSE <stream>` flattens the position on that stream at market. No sizing needed — it closes the full current position.

```qkt
WHEN ema(btc.close, 9) CROSSES BELOW ema(btc.close, 21)
 AND POSITION.btc > 0
THEN CLOSE btc
```

`CLOSE_ALL` does the same for every open position across all symbols. Use sparingly — usually you want to be precise.

## `RESIZE <stream> TO <sizing>`

Set an open position to a target size each bar, trimming or adding to reach it without
close+reopen. This is the volatility-targeting / rebalancing primitive: a position whose
exposure should scale inversely to realized volatility, or to a target portfolio weight.

```qkt
-- Scale gold exposure inversely to its volatility, re-sized every bar.
WHEN POSITION.gold != 0
THEN RESIZE gold TO 0.01 / atr(gold.candle, 14)
```

- `<sizing>` reuses the SIZING grammar, so the target can be a quantity expression, `PCT OF
  EQUITY` (rebalance to a target weight), `NOTIONAL`, etc. It evaluates to a **target
  magnitude** for the symbol's primary position. (`RISK`-based sizing needs a stop distance and
  is not available here.)
- It resizes the existing primary to that magnitude, **same side**. `TO 0` flattens it. With no
  open position it is a no-op — open with `BUY`/`SELL` first.
- A grow is a same-side market add (averaged into the position); a shrink is a partial close at
  market (entry price preserved, P&L realized on the closed portion). Both fill at the bar price,
  so backtest equals live.
- `MIN_STEP <expr>` sets an anti-churn deadband — the smallest `|target − current|` worth acting
  on (default 5% of the target). Skips the micro-orders a continuous target would otherwise emit.

```qkt
RESIZE aud TO 0.02 / atr(aud.candle, 20) MIN_STEP 0.002
```

For risk on a resized position, use a `CLOSE` rule — it always flattens the **current** position
size, so it tracks the resize for free:

```qkt
WHEN gold.close < gold.close[1] - 3 * atr(gold.candle, 14) THEN CLOSE gold
```

Do **not** combine `RESIZE` with a `BRACKET` on the same entry: a bracketed position is held as a
separate, ticketed leg that the net-position resize cannot see. Resize works on the net position
that plain `BUY`/`SELL` opens; pair it with the `CLOSE`-rule stop above.

```qkt
WHEN ACCOUNT.equity < 5000
THEN CLOSE_ALL
     LOG WARN "equity below safety threshold — flattening"
```

## `CANCEL <stream>` and `CANCEL_ALL`

Cancels working orders without touching open positions.

```qkt
CANCEL btc           -- cancel any pending orders on btc, leaves the position alone
CANCEL_ALL           -- cancel every pending order
```

Use case: a STACK strategy with unfilled layers — you want to cancel the rest when conditions change.

```qkt
WHEN regime_changed
THEN CANCEL btc      -- abandon unfilled stack layers
     CLOSE btc       -- close the already-filled portion
```

## `OCO_ENTRY { leg1, leg2 }`

Submits two pending entry orders linked one-cancels-other. When either leg fills, the broker auto-cancels the other. Use for breakout straddles where you don't know which direction will resolve first.

```qkt
OCO_ENTRY {
    BUY  gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close + 50
         BRACKET { STOP LOSS BY 180, TAKE PROFIT BY 150 }
         TIF GTD UNTIL NOW + 10m,
    SELL gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close - 50
         BRACKET { STOP LOSS BY 180, TAKE PROFIT BY 150 }
         TIF GTD UNTIL NOW + 10m
}
```

Both legs are submitted to the broker as pending orders (typically `STOP AT` or `LIMIT AT`). Whichever triggers first becomes the live position; the OrderManager cancels the sibling on receipt of the fill event. Each leg carries its own `BRACKET` — when a leg fills, its stop-loss and take-profit attach to that position automatically.

### Children

- **Exactly two legs.** `OCO_ENTRY { ... }` with 0, 1, or 3+ legs is a parse error.
- **Each leg must be `BUY` or `SELL`.** Including `LOG`, `CLOSE`, or `CANCEL` inside an `OCO_ENTRY` block is a parse error.
- **Same stream or different streams.** The DSL doesn't restrict; the broker decides. Same-symbol opposite-side is the hedge-straddle case; different-symbol same-side is a pairs-trading entry.

### Time-in-force

The two legs typically share a `TIF GTD UNTIL NOW + <duration>` clause so both expire together if neither triggers. See [NOW](now.md) for relative deadlines.

### Common gotchas

- **Same-bar dual breach.** If a single candle's high and low cross both stop prices, the tiebreak is broker-dependent. In backtest, the leg with the closer trigger to the candle's open fills first.
- **Broker capability.** Bybit Spot is netting-only and does not support pending-pair OCO. Bybit Linear with hedge-mode and MT5 brokers (Phase 17 + 26b) do. As of Phase 26b, MT5 translates the pending family natively (BUY_STOP, SELL_STOP, BUY_LIMIT, SELL_LIMIT, BUY_STOP_LIMIT, SELL_STOP_LIMIT, server-side trailing). Async fill-event lifecycle (cancel-on-fill across MT5 tickets) lands in Phase 26c — until then, pending placements succeed live but qkt-side fill events for pending shapes arrive lazily via the position poller.
- **Pending orders aren't positions.** `POSITION.<stream> = 0` returns true while OCO legs are pending; gate entries with `POSITION.<stream> = 0 AND not has_pending_oco(...)` if you need that distinction.

### What this composes with

- [NOW](now.md) — session-hour gating + `NOW + duration` for GTD expiry
- [BRACKET](bracket.md) — per-leg SL/TP attached to the surviving fill

## `LOG`

Emits a structured log line. Three levels (`INFO`, `WARN`, `ERROR`, `DEBUG`) and optional structured fields.

```qkt
LOG [LEVEL] "<msg>" [<key>=<expr> ...]
```

### Simple message

```qkt
THEN LOG "entered long position"
```

Output (with the default logback config):

```text
2026-05-11T10:23:45.123 [main] INFO  com.qkt.app.LiveSession - [my-strategy] entered long position
```

### With placeholders

`{name}` placeholders in the message string get filled from the structured fields:

```qkt
THEN LOG "long entry at {price} with stop at {stop}"
     price=btc.close
     stop=btc.close - atr(btc, 14) * 2
```

The `{price}` and `{stop}` in the string are replaced with the evaluated values. The fields **also** appear in the JSON output (if you're using structured logging) under `log.price` and `log.stop`.

### Levels

`INFO` is the implicit default — `LOG "..."` produces an INFO line. For other levels use the keyword:

```qkt
LOG       "..."        -- INFO (default)
LOG WARN  "..."        -- something unusual but not fatal
LOG ERROR "..."        -- something failed
LOG DEBUG "..."        -- low-level detail; usually filtered out in production
```

There is no explicit `LOG INFO` keyword form — INFO is reached by omitting the level.

## Combining actions

Multiple actions per rule, separated by `;`:

```qkt
WHEN regime_changed
THEN
    CANCEL btc ;                                  -- cancel any pending btc orders
    CLOSE btc ;                                   -- flatten btc position
    LOG "regime change" old=old_regime new=new_regime
```

Actions fire in order. The next action sees the state after the previous one (so `LOG` after `CLOSE` sees the closed position).

## Optional clauses, in order

When you stack modifiers on a `BUY`/`SELL`, the order matters but the parser is forgiving:

1. `<stream>` (required)
2. `SIZING <spec>` (or inherited from `DEFAULTS`)
3. Order-type modifier (`LIMIT AT`, `STOP AT`) — defaults to market
4. `BRACKET { ... }` (or `STOP_LOSS ... TAKE_PROFIT ...` bare)
5. `STACK <n> SPACING <points> ABOVE|BELOW [WITHIN <duration>]` — pyramiding
6. `STACK_AT MFE >= <threshold> WITHIN <duration> SIZING <qty> BRACKET { ... }` — conditional bracketed stacks (multiple per action allowed; see [STACK_AT](stack-at.md))
7. `TIF <mode>` — time-in-force
8. `LOG ...` — usually a separate action after `;` but can be inline-chained

The most common patterns:

```qkt
-- Simple market buy with bracket
BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 100 PCT }

-- Limit entry with bracket
BUY btc SIZING 0.1 LIMIT AT 67000 BRACKET { ... }

-- Bare stop (no take-profit, exit via rule)
BUY btc SIZING 0.1 STOP_LOSS AT btc.close - atr(btc, 14) * 2

-- Stacked with shared bracket
BUY btc SIZING 0.1 STACK 3 SPACING 200 ABOVE WITHIN 4h
    BRACKET { STOP_LOSS BY 300, TAKE_PROFIT BY 1000 }
```

## Common gotchas

- **`BUY btc` without `SIZING` and without `DEFAULTS.sizing` is a parse error.** Sizing is required at exactly one of: action, DEFAULTS.
- **`CLOSE` doesn't take a size.** It closes the whole position. To exit partially, use a `BRACKET` with scale-out targets or a `SELL` that fires when long.
- **Edge-trigger gotcha for entries.** Without `AND POSITION.<stream> = 0`, a `BUY` rule fires once on signal — then if the signal stays true, it doesn't re-fire (edge-trigger). If you want re-entry capability, ensure the position guard is in place.
- **`LOG` is not an exit.** Logging doesn't change strategy state. Use `CLOSE` or `CANCEL` for actions; `LOG` for the audit trail.
- **`TRAILING` order type requires the explicit `ORDER_TYPE =` keyword.** Use `BUY btc ORDER_TYPE = TRAILING BY 50` — bare `BUY btc TRAILING BY 50` parses the BUY without an order type and chokes on `TRAILING` as the next statement.

## What this composes with

- [SIZING](sizing.md) — every way to specify position size
- [BRACKET](bracket.md) — stop-loss and take-profit groups
- [STACK](stack.md) — pyramiding multiple entries from one signal
- [STACK_AT](stack-at.md) — conditional bracketed stacks fired by MFE thresholds
- [Streams](streams.md) — what `<stream>` refers to
- [LOG/Logging](../../operations/logging.md) — log routing, MDC keys, file outputs
