# Phase 43.8: structure positions — fields, close, Greeks — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a rule opens `ps`, a strategy can read and manage it:

```
WHEN POSITION.ps = 0 AND … THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { … } SIZING 1 PCT RISK
WHEN POSITION.ps.pnl_pct >= 50 OR POSITION.ps.dte <= 21 THEN CLOSE ps
```

The fields are `POSITION.ps.delta|gamma|vega|theta|dte|credit|max_loss|pnl|pnl_pct`, and bare
`POSITION.ps` gives the size. `CLOSE ps` closes every held leg as one group.

**Architecture:**
- **Defects found while planning, fixed first:**
  - An unwind leg that the venue *rejects* (for example, a leg whose contract has just expired) was
    sent again at once and rejected again, without end. A rejected unwind leg is now final. Legs
    whose contract has expired are left to settlement and never closed.
  - The planner's maximum loss took one minimum over legs of different expiries, which understates
    the loss of a calendar whose long leg expires first. `StructureRisk.maxLoss` now sums each
    expiry's mark value less its minimum payoff, the same per-expiry rule as `OptionMargin` (A42).
- **Typed groups.** The new form is `Signal.SubmitGroup(structureId, alias, requests,
  closes: String?)`. A group that closes a structure (an unwind or a `CLOSE`) names that structure,
  and its `force` is derived from that. The `unwind:` id prefix is gone.
- **`StructureBook`** (in `app`, one per pipeline) is the single writer of structure state. It
  records each strategy's live structure per alias, with these states:
  - `PENDING`: open group accepted, legs working;
  - `OPEN`: every leg filled;
  - `UNWINDING`: a leg failed;
  - `CLOSING`: `CLOSE` sent.

  Each leg records its entry quantity and average price, its held quantity, and its realized
  premium P&L (from closing fills, and at expiry from the settlement print, at its price). A
  structure leaves the book when no leg is held and no leg order is working. `StructureCoordinator`
  drives the book from bus events and unwinds through it.
- **`StructureView`** (read-only, `strategy` package) is on `StrategyContext` (default empty). It
  gives the live structure for an alias and the mark of a symbol (the pipeline's price tracker, the
  same mark equity uses).
- **Fields** (`StructureFieldCompiler`). Structure aliases are known at compile time from the
  strategy's `OPEN` actions. A structure field on a stream alias, a stream-only accessor on a
  structure alias, or an alias that is both a stream and a structure is a compile error. Every field
  except bare `POSITION.ps` is `Undefined` unless the structure is `OPEN`.

  | Field | Unit | Definition |
  |---|---|---|
  | `POSITION.ps` | contracts per leg | the size from acceptance until the structure leaves the book; 0 otherwise |
  | `credit` | account currency | opening premium received: Σ −signed entry qty × contract size × entry price (negative for a debit) |
  | `max_loss` | account currency | worst expiry loss from opening: `StructureRisk.maxLoss` at entry prices; `Undefined` when unbounded |
  | `pnl` | account currency | premium P&L before fees: realized + Σ held qty × size × (mark − entry) |
  | `pnl_pct` | percent | 100 × pnl ÷ \|credit\|; `Undefined` when credit is 0 |
  | `dte` | days, fractional | to the nearest expiry of a held leg |
  | `delta`, `gamma` | underlying units, per unit of price | Σ held qty × size × Black-76 Greek |
  | `vega` | account currency per vol point | Σ held qty × size × vega ÷ 100 |
  | `theta` | account currency per day | Σ held qty × size × theta ÷ 365 |

  Greeks come from the latest snapshot at or before NOW. Each leg uses its contract's mark IV
  (usable as in the selector: IV > 0, no older than the quote age), with forward = that expiry's
  median underlying, rate 0, and T from the clock. A held leg without a usable IV makes the Greek
  `Undefined`, never 0 (E20).
- **`CLOSE ps`**: on an `OPEN` structure, emits one closing group (market orders for every held,
  unexpired leg) and marks it `CLOSING`. On any other state it fires a `Suppressed` with the reason.
  `OPEN ps` while `ps` is live fires a `Suppressed` (one live structure per alias).

**Spec:** §6.4 position fields; §6.5; E20, E21.

## Global constraints

- No change for strategies without structures: parse pins, fingerprints, golden CFD pins.
- `TradingPipeline` is baselined and must not grow: the binder takes the price tracker in place of
  the redundant `latencyEnabled` (it reads `latency.enabled`).
- Files ≤ 200 lines; KDoc; Greeks and field values verified against independent Python arithmetic
  on the real fixtures.

## Review focus

- A structure whose leg expires while another leg is held: realized at the settlement price, fields
  over the held legs, and the structure leaves the book only when everything is settled.
- `CLOSE ps` while pending or unwinding; `OPEN ps` while closing.
- Two aliases holding the same contract: closes and expiry are attributed per structure.
- The `pnl_pct` sign for debit and credit structures.
- Greeks when one leg's IV is stale.

---

### Task 1: unwind rejection and expiry fixes; `StructureRisk` per-expiry max loss in the planner
### Task 2: typed `SubmitGroup`, `StructureBook`, `StructureView` on the context, coordinator on the book, `OPEN` refused while live
### Task 3: `StructureGreeks` (pure, verified independently) and the `POSITION.ps.*` fields with compile checks
### Task 4: `CLOSE ps`
### Task 5: end to end on real snapshots (open, read fields, close on `pnl_pct`), docs, parity row, capability catalog
