# Live position resize — design spec

**Phase:** 5 (DSL). **Closes:** #541, #544, #552, #553.

## Goal

Let a strategy set an open position's target size as a per-bar function of an indicator
and have the engine trim or add to reach that target, without closing and reopening. This
is the second-moment risk overlay (volatility targeting / Moreira–Muir) and the
rebalance-to-target-weight pattern that several qkt-forge candidates need: direction is a
weak base signal, and the Sharpe lift comes from continuously scaling exposure to
`k / realized_vol`.

Entry-time sizing as a function of an indicator already works (`SIZING = k / atr(...)`);
the missing piece is **re-sizing a live position each bar**, which today is only expressible
as close+reopen (breaks leg/cost-basis tracking) and has no DSL surface.

## DSL surface

```
RESIZE <stream> TO <sizing-expr> [ MIN_STEP <expr> ]
```

- `<sizing-expr>` reuses the existing `SizingAst` grammar, so the target can be any of the
  sizing forms — a raw quantity expression (`RESIZE aud TO 0.01 / atr(aud.candle, 14)`),
  `PCT_EQUITY`/`PCT_BALANCE` (the #552 rebalance-to-target-weight case), `NOTIONAL`, etc. It
  evaluates to a **target magnitude** (lots) for the symbol's PRIMARY leg.
- `MIN_STEP <expr>` (optional) is the anti-churn deadband — the smallest `|delta|` worth
  acting on. Default: `max(0.05 * target, <symbol min lot>)`. Exposed so the grid can tune it.
- Lives in a rule: `WHEN <cond> THEN RESIZE aud TO <expr>` re-evaluates the target every bar
  the rule fires. The rule cadence *is* the per-bar trigger; no new evaluation loop.

### Semantics

Let `cur = |current PRIMARY qty|`, `target = max(0, eval(sizing-expr))`, `delta = target − cur`.

| Condition | Action |
| --- | --- |
| no open PRIMARY for the symbol | no-op (open with `BUY`/`SELL` first) |
| `target == 0` | full close of the PRIMARY (existing close path) |
| `delta > 0` (grow) | market **add**, same side, qty `delta` — averages into the PRIMARY |
| `delta < 0` (shrink) | market **partial close-by-ticket** of the PRIMARY for `|delta|` |
| `|delta| < MIN_STEP` | no-op (deadband) |

The side is the existing PRIMARY's side — resize only changes magnitude. **One-step sign
flips are out of scope**: the theses flatten on a direction flip (`RESIZE … TO 0` or
`CLOSE`) and re-enter, so resize never crosses zero. Resize targets the **PRIMARY** leg only;
`STACK`/`INDEPENDENT` legs are untouched.

## Components

### 1. AST + parser
- `ActionAst.Resize(stream: String, target: SizingAst, minStep: ExprAst?)` in
  `dsl/ast/RuleAst.kt`.
- Parser: `RESIZE <ident> TO <sizing> [MIN_STEP <expr>]`. `RESIZE`, `TO`, `MIN_STEP` are new
  keywords. Reuses the existing sizing-clause parser.

### 2. Action compilation (`dsl/compile/ActionCompiler.kt`)
- `compileResize(stream, target, minStep)` returns `(EvalContext) -> List<Signal>`:
  1. resolve the symbol's PRIMARY leg via `positions`; if none → `emptyList()`.
  2. evaluate `target` (clamped ≥ 0) and `minStep`.
  3. compute `delta`; apply the deadband.
  4. emit the trim/add/close signal(s) — all market orders:
     - grow → `Signal.Submit(OrderRequest.Market(side = primary.side, qty = delta))`.
     - shrink → `Signal.Submit(OrderRequest.Market(side = opposite, qty = |delta|,
       closesLegId = primary.legId, closesTicket = primary.brokerTicket))`.
     - to-zero → the existing full-close signal.

### 3. Partial leg reduction (`positions/StrategyPositionTracker.kt`)
- A reducing fill (a market order carrying `closesLegId` for `|delta|` < leg qty) replaces the
  PRIMARY with a `(qty − |delta|)` leg at the **same `entryPrice` and `openedAt`**, realizing
  P&L only on the closed portion. This mirrors the existing same-direction averaging path,
  inverted. Full-qty reducing fills close the leg (unchanged behaviour).

### 4. Protective-order quantity sync (`app/OrderManager.kt`)
- After a resize fill changes the PRIMARY quantity, sync the leg's protective orders: for the
  stop (and TP, if any) keyed to the primary entry, **cancel and re-submit at the same price
  with the new quantity**. The price never re-anchors. If the leg has no bracket, nothing to
  sync.

## Backtest = live parity

- Trim/add are market orders filled at the current bar price (`PaperBroker.fillMarket` →
  `priceProvider.lastPrice`), byte-identical to a `BUY`/`SELL` at that bar.
- Stop-sync is cancel + re-submit at the **same** stop price → deterministic, no re-anchor.
- The deadband is a deterministic `BigDecimal` comparison.
- `RESIZE` is a new opt-in action: existing strategies are byte-identical (no `RESIZE`, no
  new code path taken).

## Error handling

- `RESIZE` on a symbol not bound in `SYMBOLS` → compile error (same as `BUY`).
- Negative target → clamped to 0 (treated as flatten).
- `RESIZE` while a same-symbol pending entry is unfilled → acts on the filled PRIMARY only;
  pending entries are unaffected.

## Testing

- **Unit (delta mapping):** grow / shrink / flat / no-open-primary / deadband each produce the
  expected signal set.
- **Position tracker:** partial reduction preserves `entryPrice`/`openedAt` and realizes the
  correct partial P&L; a full reduction closes the leg.
- **Stop sync:** the protective order's quantity tracks the position across a grow and a
  shrink; its price is unchanged.
- **DSL compile:** `RESIZE aud TO 0.01 / atr(aud.candle, 14) MIN_STEP 0.001` parses + compiles.
- **End-to-end backtest:** a vol-target strategy (`WHEN true THEN RESIZE aud TO k/atr`) resizes
  deterministically over real bars; a paper backtest is byte-identical on re-run.

## Out of scope (documented follow-ups)

- One-step sign flips (cross-zero rebalance) — flatten + re-enter instead.
- Resize of `STACK`/`INDEPENDENT` legs.
- Re-anchoring the stop price on resize (deliberately avoided — parity landmine).
