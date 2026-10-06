# Phase 43.6: option root feed and leg selector — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The groundwork structures need, all usable and tested on its own:
- A run can carry a whole option root. Every quoted contract is marked and tradeable, not only the
  contracts declared as streams.
- A deterministic selector picks contracts from the chain by right, expiry window and delta.

Phase 43.7 builds the `OPTIONS ON … { legs }` action on top of this.

**Architecture:**
- **Root feed symbol `OPTIONS:<VENUE>.<ROOT>`** (`OptionRootSymbol`). `OptionRootMarketSource`
  decodes each stored day once. At each snapshot instant it emits one tick per quoted contract, in
  contract order, priced at the mark with [OptionQuotes] sides, with nothing at or after a contract's
  expiry. When the window covers an expiry, it emits each contract's settlement print at the
  intrinsic value.
- **Feeding and routing.** The root feed symbol is declared as an ordinary observation-free stream:
  `chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h`. Its ticks carry contract symbols, so no candle is ever
  keyed by the root symbol itself. It is read-only (no orders on `chain`).
- **Whole-root route.** `StoreMarketSource` routes `OPTIONS:` symbols to the root source.
  `replayOptionRoutes` routes every catalogued contract of a fed root to the option venue, by the
  prefix `<VENUE>:<ROOT>_`.
- **Coverage** checks the root's chain days over the run.
- **`OptionSelector`** (pure) selects from one `ChainSnapshot` plus the catalog listings. Given right,
  target |delta|, a DTE range or a fixed expiry, and the quote age:
  - **Usable quotes:** a mark IV > 0, and a mark no older than the root's quote age.
  - **Expiry choice:** the nearest expiry whose days to expiry fall in the range.
  - **Strike choice:** the quote whose Black-76 |delta| (forward = that expiry's median `underlying`,
    rate 0) is nearest the target, ties to the lower strike.
  - Returns null when nothing qualifies.
- **`ChainView`** gives a strategy's latest snapshot at or before NOW, keeping one decoded day per
  root. It is look-back only.

**Spec:** §6.4 (leg selection rule); §6.3 chains.

## Global constraints

- No change for runs without an `OPTIONS:` stream (golden pins, option and futures tests).
- No look-ahead: the selector reads only the latest snapshot at or before NOW.
- Files ≤ 200 lines; KDoc; commits via the helper; selector expectations recomputed independently.

## Review focus

- A root feed and an explicitly declared contract stream for the same contract: ticks are not
  duplicated into the contract's candles.
- A contract held from a root feed but never declared marks at its own quotes (P&L, margin) and
  settles at expiry.
- The selector with no expiry in range, a range spanning several expiries, delta ties, put deltas
  (negative), and stale or zero-IV quotes.
- `ChainView` at an instant before the first snapshot of the day reads the previous day, never a
  later snapshot.

---

### Task 1: OptionRootSymbol and OptionRootMarketSource
### Task 2: routing, read-only stream, coverage, whole-root venue route
### Task 3: OptionSelector and ChainView (pure plus cached store)
### Task 4: end to end — a strategy feeds the root and trades a declared contract of it. Each of the
contract's ticks arrives once (a fed root serves its declared contracts, never a second feed), and
marks, margin and expiry work. Then docs.
