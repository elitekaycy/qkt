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
  4. emit the trim/add signal:
     - grow → same-side `Signal.Buy`/`Signal.Sell` for `delta` — the tracker averages it into the primary.
     - shrink / to-zero → opposite-side `Signal.Buy`/`Signal.Sell` for `|delta|` — the tracker's
       netting path reduces (or closes) the primary.

### 3. Partial leg reduction (`positions/StrategyPositionTracker.kt`) — no new code
- A resize-down is a plain opposite-direction net trade, which the tracker's existing
  opposite-direction path already handles: it realizes P&L on the closed portion and replaces the
  PRIMARY with a `(qty − |delta|)` leg at the **same `entryPrice` and `openedAt`** (or closes it
  when `|delta| = qty`). No change needed.

### 4. Risk on a resized position — the `CLOSE`-rule idiom
- A `CLOSE`/`CLOSE_ALL` action always flattens the **current** position quantity, so a stop
  written as a rule — `WHEN <stop condition> THEN CLOSE <stream>` — is inherently sized to the
  resized position. No bracket and no OrderManager change are needed.
- **A `BRACKET` is incompatible with `RESIZE`** and must not be combined with it: in qkt's
  truthful position model with a multi-position broker, a bracketed entry is an INDEPENDENT
  ticketed leg that is invisible to the net `POSITION.<sym>` view and to `RESIZE` (which targets
  the netting PRIMARY). Use the `CLOSE`-rule stop instead. A bracket-aware resize (operating on
  independent ticketed legs) is a future enhancement, out of scope here.

## Backtest = live parity

- Trim/add are net `BUY`/`SELL` market trades filled at the current bar price
  (`PaperBroker.fillMarket` → `priceProvider.lastPrice`), byte-identical to a hand-written
  `BUY`/`SELL` at that bar.
- The deadband is a deterministic `BigDecimal` comparison.
- `RESIZE` is a new opt-in action with **no engine/execution change** — existing strategies are
  byte-identical.

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
- **CLOSE-rule stop:** after a resize grows the position, a `CLOSE` rule closes the full resized
  quantity (the synced-stop idiom).
- **DSL compile:** `RESIZE aud TO 0.01 / atr(aud.candle, 14) MIN_STEP 0.001` parses + compiles.
- **End-to-end backtest:** a strategy resizes (grow / shrink / flatten) deterministically over a
  tick sequence; the net position ends flat.

## Out of scope (documented follow-ups)

- One-step sign flips (cross-zero rebalance) — flatten + re-enter instead.
- Resize of `STACK`/`INDEPENDENT` legs, and a **bracket-aware resize** (resizing an independent
  ticketed bracketed position) — needs design; use a `CLOSE`-rule stop for now.
