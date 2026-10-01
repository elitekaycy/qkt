# Phase 43.5: short options with worst-case margin — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Options can be sold, not only bought: a strategy can write a cash-secured put, a covered
spread or any defined-risk combination, with margin that equity must carry before the order is
accepted. Short positions settle at expiry by paying the intrinsic value. Positions with unlimited
downside (a naked short call) are refused, never under-margined.

**Architecture:**
- **`OptionPayoff.minimum(legs)`** (pure, exact `BigDecimal`). The expiry payoff of signed option
  legs (right, strike, quantity, contract size) is piecewise linear in the settlement price S, with
  kinks at the strikes. Its minimum over S ≥ 0 is the least of the values at S = 0 and at each
  strike, unless the slope above the highest strike (the net calls) is negative, which means
  unbounded (null).
- **`OptionMargin`**:
  - Groups the account's option positions by root and expiry.
  - Per group: requirement = its current mark value Σ q·mark·contractSize, less its minimum expiry
    payoff.
  - A long option needs its premium.
  - A credit spread needs its width less its credit.
  - A short put needs its strike less its mark (cash-secured).
  - An unbounded group cannot be margined.
  - Pending orders are judged in the worst of four outcomes: positions plus this order, then plus all
    pending buys, all pending sells, or both.
- **`MarginRequirement`** adds the option portfolio requirement to the futures requirement. An order
  passes when equity covers both. An option order whose outcome leaves an unbounded group is refused
  with that reason. Orders that only reduce exposure pass, as before. The rule is wired wherever
  `MarginRequirement` is wired, in backtest and live alike.
- **`OptionExchange`** drops its long-only refusal. Short positions settle through the existing
  expiry path (the holder of a short pays intrinsic, side BUY, delivery fee as for longs).

**Spec:** §6.5 (exposure checks, undefined-risk refusal); §11 E21 (structures, 43.6).

## Global constraints

- Futures margin results unchanged (`MarginRequirementTest` and the futures margin backtests).
- CFD runs untouched: an option-free run computes no option margin.
- Exact decimal arithmetic for payoffs and margins; files ≤ 200 lines; KDoc; commits via the helper.

## Declared divergences (parity catalog)

- Margin is the exact worst-case expiry loss per root and expiry. That is more conservative than
  Deribit's standard margin, which lends against short options. Calendar spreads and options
  covered by futures or spot get no offset.

## Review focus

- A short call covered by a long call of a *higher* strike at the same expiry is bounded (width less
  credit). With a long call of a *different expiry* it is refused.
- A short put's requirement at S = 0 (the strike) is honoured even when far out of the money.
- An order that reduces a short (buying it back) always passes, even when equity is short.
- Pending orders cannot let a burst of sells exceed equity.
- Expiry: a short in the money pays intrinsic plus the delivery fee; out of the money it pays nothing.

---

### Task 1: OptionPayoff.minimum (pure)
Tests: long call/put, short put (S = 0), short call (unbounded), call spread, put spread, iron
condor, butterfly, mixed quantities and contract sizes, and zero net legs.

### Task 2: OptionMargin and the margin rule
Tests: premium for longs, cash-secured put, credit spread, unbounded refusal, reduce-only passes,
pending-order scenarios, futures plus options summed against equity, option-free runs unchanged.

### Task 3: venue shorts and expiry
Remove the long-only check. Short expiry ITM/OTM tests (cash, delivery fee). Order entry still
floors to `volumeStep` and the minimum.

### Task 4: end to end on real data, docs
A put credit spread sold on the real 26SEP26 quotes and held to expiry. The P&L identity is
recomputed independently: credits received, fees, intrinsic paid at the 84042.83 delivery. Then
docs, the parity row (replacing A42) and the how-to.
