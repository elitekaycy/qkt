# STACK_AT — conditional bracketed stacks

Fire independent micro-trades after a primary fill, once the position shows conviction. Each `STACK_AT` clause attaches its own SL/TP bracket and tracks as a separate leg — closing the primary does NOT close the stacks, and a stack hitting its own TP does not affect the primary or other stacks.

This is the multi-leg pattern from the production hedge-straddle: a directional break enters, then as MFE grows the strategy layers in three independent positions, each with its own risk and reward. Per the pa-quant analysis, this pattern roughly doubles 6-month P&L on top of the no-stack profile.

`STACK_AT` is distinct from [`STACK`](stack.md). `STACK` is pyramiding — one position, shared bracket, sequential triggers. `STACK_AT` is leg-based — N independent positions, each with its own bracket, fired by max-favorable-excursion thresholds or MAE-then-recovery recoil tiers.

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
    STACK_AT MAE >= <threshold3> RECOVER <distance> WITHIN <duration3>
        SIZING <stack_size>
        BRACKET { STOP LOSS BY <s>, TAKE PROFIT BY <t> }
    ...
```

- `MFE >= <threshold>` — the stack fires when the **primary leg's** max favorable excursion (high-water mark of `current_price - entry_price` for a BUY) crosses the threshold, in price units
- `MAE >= <threshold> RECOVER <distance>` — the stack arms when the **primary leg's** max adverse excursion crosses the threshold, then fires after price recovers by `<distance>` from the worst adverse extreme
- `WITHIN <duration>` — abandons the clause if the threshold isn't reached within the window since the primary fill. Each clause has its own deadline.
- `SIZING <stack_size>` — the stack leg's quantity. Supports literal lots and arithmetic over `ENTRY_QTY`; risk-based and percent sizing are not supported.
- `BRACKET { ... }` — the stack's own SL/TP. Each leg is independent. Required.

Multiple `STACK_AT` clauses on one action are independent. MFE tiers fire as their thresholds cross. MAE recovery tiers arm and fire independently, each abandoning on its own deadline.

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

## Recoil tiers

```qkt
BUY gold SIZING 0.20
    BRACKET { STOP LOSS BY 25, TAKE PROFIT BY 60 }
    STACK_AT MAE >= 20 RECOVER 15 WITHIN 1h
        SIZING ENTRY_QTY * 0.50
        BRACKET { STOP LOSS BY 25, TAKE PROFIT BY 60 }
```

For a BUY primary filled at $2,000:

- Price falls to $1,980: MAE reaches 20, so the tier arms.
- Price falls further to $1,970: the adverse extreme moves down, so the recovery trigger floats down with it.
- Price recovers to $1,985: recovery from the $1,970 extreme is 15, so the stack fires.

For a SELL primary the directions invert: MAE grows when price rises above entry, and recovery is measured downward from the adverse high. A recoil tier fires at most once. Its `WITHIN` window starts at the primary fill and it is abandoned if the window elapses or the primary closes before firing.

## How tiers fire

On every market tick after the primary fills:

1. The primary leg's MFE/MAE tracker updates with the new price.
2. For each `STACK_AT` clause not yet fired or abandoned:
   - If `mfe >= threshold` AND `elapsed <= within` → fire (emit a stack order).
   - If `mae >= threshold`, arm the recovery tier at the current adverse extreme; if the adverse extreme worsens, move the recovery anchor.
   - If an armed recoil tier has recovered by `RECOVER <distance>` AND `elapsed <= within` → fire.
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
STACK_AT MFE >= 10 WITHIN 30m                  -- literal: OK
STACK_AT MFE >= 5 * 2 WITHIN 30m               -- compile-folded to 10: OK
STACK_AT MAE >= 20 RECOVER 5 * 3 WITHIN 1h     -- compile-folded recovery: OK
STACK_AT MFE >= atr(gold, 14) WITHIN 30m       -- rejected at compile time
```

`SIZING` for `STACK_AT` is limited to literal lots (`SizeQty`) and arithmetic over `ENTRY_QTY`. Risk-fraction (`RISK 0.01`), notional (`100 USD`), and percent-of-equity sizing are not supported for stacks.

`BRACKET` for `STACK_AT` must use `BY <distance>` for both legs. `AT <price>`, `PCT <frac>`, and `RR <multiplier>` forms are rejected — the stack's bracket is computed from the stack's own entry price at fire time, so absolute and ratio-based forms don't translate cleanly.

## Reading MFE and MAE from the DSL

`POSITION.<stream>.mfe` returns the primary leg's current MFE in price units. Useful for logging or as a condition that gates other rules:

```qkt
WHEN POSITION.gold.mfe > 25
THEN LOG "primary is up 25+ points" mfe=POSITION.gold.mfe
```

`POSITION.<stream>.mae` returns the primary leg's current MAE in price units:

```qkt
WHEN POSITION.gold.mae > 20
THEN LOG "primary drawdown over 20 points" mae=POSITION.gold.mae
```

Both accessors return `0` if no primary leg exists.

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
- **Recoil recovery floats with the worst adverse price.** If a BUY tier arms at $1,980 and price falls to $1,970, `RECOVER 15` fires at $1,985, not $1,995.
- **`STACK_AT` doesn't move existing brackets.** The primary's bracket stays at its original SL/TP; only new stack orders are added.
- **No retroactive fire.** The engine first evaluates on the tick *after* the primary fill. If the primary fills already past a tier's threshold, the tier fires on the next tick, not at fill time.

## What this composes with

- [BRACKET](bracket.md) — each `STACK_AT` carries one
- [SIZING](sizing.md) — restricted to literal lots and `ENTRY_QTY` arithmetic for stacks; full surface on the primary
- [OCO_ENTRY](actions.md#oco_entry) — STACK_AT on each leg is the hedge-straddle shape
- [Actions](actions.md) — `STACK_AT` attaches to `BUY` / `SELL`
- [Phase 27 spec](../../superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md) — design notes and the LegBook semantics
