# Phase 43.7: option structures in the DSL — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A rule opens a multi-leg option position whose contracts are chosen from the chain when it
fires:

```
WHEN … THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC {
    SELL PUT DELTA 0.25 DTE 30 TO 45,
    BUY PUT DELTA 0.10 SAME EXPIRY
} SIZING 1 PCT RISK
```

The legs go to the venue together. Their margin is judged as one position. If a leg fails, the legs
that filled are closed.

**Architecture:**
- **Parsing.** `ActionParser` gains `OPEN <alias> = OPTIONS ON <VENUE>:<ROOT> { leg (, leg)* }
  [SIZING …]`. The words `OPTIONS`, `CALL`, `PUT`, `DELTA`, `DTE`, `SAME` and `EXPIRY` are matched
  contextually, so there is no new `TokenKind`. A leg is `BUY|SELL CALL|PUT DELTA <0..1> (DTE <n> TO
  <m> | SAME EXPIRY)`. The first leg must give a DTE window. The action is the new sealed
  `ActionAst.OpenStructure(alias, root, legs, sizing)`. Existing ASTs are unchanged, so the parse pins
  hold. Every exhaustive `when` over `ActionAst` handles it.
- **Compile.** `StructureCompiler`, at fire time:
  - reads the latest snapshot of the root at or before NOW (`ChainView`) and selects each leg
    (`OptionSelector`);
  - sizes the contracts. `SIZING <qty>` means contracts per leg. `SIZING n PCT RISK` means
    `floor(equity × n% / maxLossPerUnit)` to the volume step, where max loss per unit is the
    structure's mark value less its minimum expiry payoff (`OptionPayoff`). Structures with an
    unbounded loss are refused for PCT RISK;
  - emits one `Signal.SubmitGroup(structureId, legs)`.

  A leg that selects nothing, or a size below the minimum, fires nothing and logs why.
- **Group submission.** `OrderSubmitter` handles a `SubmitGroup` atomically. Each leg passes the
  per-order rules. The option margin is judged once with every leg as filled
  (`RiskEngine.approveGroup`). If any leg is refused, no leg is sent. Otherwise the legs are
  published buys first.
- **Compensation.** A per-strategy `StructureCoordinator`, bound like the stack orchestrator, maps leg
  order ids to their structure. When a leg is cancelled or rejected after acceptance, it:
  - cancels the structure's still-working legs;
  - closes the filled legs at market, shorts first so no intermediate state is unbounded.
- **Registry.** `StructureBook` records each structure's legs, quantities and fills by alias, for
  43.8's `POSITION.ps.*` fields.

**Spec:** §6.4 (structure syntax, selector), §6.5 (legs in one step, flatten on later-leg rejection,
PCT RISK by max loss, undefined risk refused); §11 E21.

## Global constraints

- No change to existing ASTs, fingerprints or parse pins. Contextual words only.
- A structure needs its root fed (`OPTIONS:<VENUE>.<ROOT>`). Compilation refuses a structure whose
  root is not a declared feed.
- Files ≤ 200 lines; KDoc; commits via the helper; real-data end to end with independent arithmetic.

## Review focus

- A structure whose second leg selects nothing fires nothing: no orphan first leg.
- A credit spread passes margin as a group while its short leg alone would not.
- A leg cancelled at the next snapshot (no side) after another filled: the filled leg is closed, and
  a short is closed before a long.
- PCT RISK with an unbounded structure is refused. A size that rounds to zero fires nothing.
- The rule edge: a refused group does not consume the edge as an accepted fire.

---

### Task 1: AST and parser (OpenStructure, legs, contextual words), plus every exhaustive `when`
### Task 2: StructureCompiler: selection, sizing, SubmitGroup signal
### Task 3: group submission with group margin (RiskEngine.approveGroup, OrderSubmitter)
### Task 4: StructureCoordinator compensation and StructureBook
### Task 5: end to end on the real trade-chain fixture (a put credit spread opened by one rule,
legs at independently derived prices, held to expiry, P&L identity), a compensation path, docs.
