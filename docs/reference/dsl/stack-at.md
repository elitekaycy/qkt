# STACK_AT — conditional bracketed stacks

Fire independent micro-trades after a primary fill, once the position shows conviction. Each `STACK_AT` clause attaches its own SL/TP bracket and tracks as a separate leg — closing the primary does NOT close the stacks, and a stack hitting its own TP does not affect the primary or other stacks.

This is the multi-leg pattern from the production hedge-straddle: a directional break enters, then as MFE grows the strategy layers in three independent positions, each with its own risk and reward. Per the pa-quant analysis, this pattern roughly doubles 6-month P&L on top of the no-stack profile.

`STACK_AT` is distinct from [`STACK`](stack.md). `STACK` is pyramiding — one position, shared bracket, sequential triggers. `STACK_AT` is leg-based — N independent positions, each with its own bracket, fired by max-favorable-excursion thresholds.

## Shape

```qkt
BUY <stream> SIZING <primary_size>
    BRACKET { STOP LOSS BY <p>, TAKE PROFIT BY <q> }
    STACK_AT MFE >= <threshold> WITHIN <duration>
        SIZING <stack_size>
        BRACKET { STOP LOSS BY <s>, TAKE PROFIT BY <t> }
    STACK_AT MFE >= <threshold2> WITHIN <duration2>
        SIZING <stack_size>
        BRACKET { STOP LOSS BY <s>, TAKE PROFIT BY <t> }
    ...
```

- `MFE >= <threshold>` — the stack fires when the **primary leg's** max favorable excursion (high-water mark of `current_price - entry_price` for a BUY) crosses the threshold, in price units
- `WITHIN <duration>` — abandons the clause if the threshold isn't reached within the window since the primary fill. Each clause has its own deadline.
- `SIZING <stack_size>` — the stack leg's quantity. Currently restricted to literal `SizeQty` (absolute lots) — risk-based and percent sizing not yet supported.
- `BRACKET { ... }` — the stack's own SL/TP. Each leg is independent. Required.

Multiple `STACK_AT` clauses on one action are independent — they fire in MFE order as their thresholds cross, each abandoning on its own deadline.

## Three-tier hedge-straddle example

```qkt
BUY gold SIZING 0.20
    BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
    STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
    STACK_AT MFE >= 20 WITHIN 60m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
    STACK_AT MFE >= 30 WITHIN 90m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
```

Primary fills at $2,000 for 0.20 lots. Then:

- Price reaches $2,010 within 30 min (MFE = 10) → tier-1 fires: a fresh 0.06-lot BUY with its own 2/20 bracket.
- Price reaches $2,020 within 60 min from primary fill (MFE = 20) → tier-2 fires.
- Price reaches $2,030 within 90 min (MFE = 30) → tier-3 fires.

If MFE peaks at 25 within 30 min and pulls back, tier-1 has already fired; tier-2 fires when MFE crosses 20 again; tier-3 is abandoned if 90 min elapses before MFE reaches 30.

When the primary's bracket fires (SL or TP), the **primary leg only** closes. Stack legs continue with their own brackets.

## How tiers fire

On every market tick after the primary fills:

1. The MFE tracker on the primary leg updates with the new price.
2. For each `STACK_AT` clause not yet fired or abandoned:
   - If `mfe >= threshold` AND `elapsed <= within` → fire (emit a stack order).
   - Else if `elapsed > within` → mark abandoned (won't fire this primary's lifecycle).
3. A tier fires at most once per primary lifecycle.

If a single big tick crosses multiple thresholds, all qualifying tiers fire on the same tick — they're independent.

## How legs track

After tier-1 fires and the stack market fills:

```
LegBook(EURUSD):
  PRIMARY  legId=primary-1  side=BUY  qty=0.20  entry=2000.00
  STACK    legId=stack-tier0  side=BUY  qty=0.06  entry=2010.00  parentLegId=primary-1
```

The position's net view (`POSITION.gold`) returns the combined quantity. Per-leg state is observable via the leg book.

When the stack's own TP fires at $2,030, the stack leg closes — primary leg is untouched:

```
LegBook(EURUSD):
  PRIMARY  legId=primary-1  side=BUY  qty=0.20  entry=2000.00
```

PnL realizes on the stack's qty × distance, independently of the primary's PnL.

## Threshold and sizing expressions

The threshold supports compile-time-constant arithmetic — literals and `+`/`-`/`*`/`/` over literals. References, indicators, and `NOW.<field>` are rejected to keep the per-tick path cheap:

```qkt
STACK_AT MFE >= 10 WITHIN 30m         -- literal: OK
STACK_AT MFE >= 5 * 2 WITHIN 30m      -- compile-folded to 10: OK
STACK_AT MFE >= atr(gold, 14) WITHIN 30m   -- rejected at compile time
```

`SIZING` for `STACK_AT` is limited to literal lots (`SizeQty`). Risk-fraction (`RISK 0.01`), notional (`100 USD`), and percent-of-equity sizing are not supported for stacks in Phase 27 — they'll land in a later phase once the leg-level risk-accounting story is finished.

`BRACKET` for `STACK_AT` must use `BY <distance>` for both legs. `AT <price>`, `PCT <frac>`, and `RR <multiplier>` forms are rejected — the stack's bracket is computed from the stack's own entry price at fire time, so absolute and ratio-based forms don't translate cleanly.

## Reading MFE from the DSL

`POSITION.<stream>.mfe` returns the primary leg's current MFE in price units. Useful for logging or as a condition that gates other rules:

```qkt
WHEN POSITION.gold.mfe > 25
THEN LOG "primary is up 25+ points" mfe=POSITION.gold.mfe
```

The accessor returns `0` if no primary leg exists.

## Combinability

- ✓ `BRACKET` on the primary — primary and each stack have independent brackets
- ✓ Multiple `STACK_AT` on one action — N tiers fire independently
- ✓ `OCO_ENTRY` with `STACK_AT` on each leg — whichever side fills attaches its stacks
- ✗ Same action with both `OCO` and `STACK_AT` — rejected at compile time
- ✗ Same action with both `STACK` (pyramiding) and `STACK_AT` — rejected at compile time
- ⚠ Native broker brackets — Phase 27 parent-close detection only covers PaperBroker's bracket-fallback path; live MT5 brackets need broker-side leg correlation work before the engine knows when the parent's TP/SL fires

## Broker capability gate

A strategy that uses `STACK_AT` is rejected at deploy time if the routing broker doesn't declare `MULTI_POSITION_PER_SYMBOL`. PaperBroker and MT5 (any venue) support it natively. Bybit Spot does NOT (netting-only); Bybit Linear supports it in hedge mode only.

The error names the strategy, symbol, and broker so the fix is unambiguous:

```
Strategy 'hedge_straddle' uses STACK_AT on XAUUSD but routing broker 'BybitSpot'
does not declare MULTI_POSITION_PER_SYMBOL
```

## Common gotchas

- **Threshold is in price units, not pips/points.** `STACK_AT MFE >= 10` means MFE = $10, not 10 pips. For XAUUSD that's $10/oz.
- **Window starts at primary fill, not signal time.** A 30m window for a tier means 30 minutes after the primary entry market actually fills — not 30 minutes after the rule's `WHEN` condition first matched.
- **Abandoned clauses don't fire later in the same lifecycle.** Once a tier's window expires without the threshold crossing, that tier is dead for this primary. A future primary on the same symbol gets fresh tiers.
- **`STACK_AT` doesn't move existing brackets.** The primary's bracket stays at its original SL/TP; only new stack orders are added.
- **No retroactive fire.** The engine first evaluates on the tick *after* the primary fill. If the primary fills already past a tier's threshold, the tier fires on the next tick, not at fill time.

## What this composes with

- [BRACKET](bracket.md) — each `STACK_AT` carries one
- [SIZING](sizing.md) — restricted to literal lots for stacks; full surface on the primary
- [OCO_ENTRY](actions.md#oco_entry) — STACK_AT on each leg is the hedge-straddle shape
- [Actions](actions.md) — `STACK_AT` attaches to `BUY` / `SELL`
- [Phase 27 spec](../../superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md) — design notes and the LegBook semantics
