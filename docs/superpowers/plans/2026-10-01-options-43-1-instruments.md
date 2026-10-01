# Phase 43.1: option instruments and the Deribit catalog — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** qkt knows an option contract the way it knows a futures contract: an `options:` root in
`instruments.yaml`, a per-underlying contract catalog fetched for free from Deribit, and
`InstrumentMeta` with `OptionTerms` for every catalogued contract — so later phases can store chains,
simulate fills and settle expiries against real metadata.

**Architecture:** `OptionTerms(root, underlyingIndex, strike, right, expiryMs, style = EUROPEAN,
settlement = CASH, margin, exchangeFeePerContract, takerFeeRate)` joins the sealed `DerivativeTerms`;
every `when` over it is extended (the compiler lists them). `OptionRoot` (declared under `options:`,
strict keys like `futures:`) gives contract size, currency, tick steps (`tickSteps: [{above: 1000,
tick: 20}]` over a base `tickSize`), volume step/min, fees and the settlement index. An
`OptionCatalog` (`contracts/<VENUE>/<ROOT>.options.json`) lists contracts with strike, right and expiry;
`OptionCatalogRegistry` layers into the existing registry like `ContractCatalogRegistry`.
`qkt fetch DERIBIT:BTC_USDC --catalog` builds the catalog from `public/get_instruments` (live and
expired, history host), keeping only `instrument_type: linear` contracts settled and quoted in the
root's currency, and records delivery prices from `public/get_delivery_prices` for the settlement
index.

**Spec:** `docs/superpowers/specs/2026-09-30-futures-options-design.md` (§6.1, §11 E18);
research: `docs/research/2026-10-01-deribit-options-free-data.md`

## Global constraints

- CFD and futures behaviour unchanged (golden pins, futures identity tests); no AST change.
- Symbols are Deribit names behind the venue prefix (`DERIBIT:BTC_USDC-27SEP24-60000-C`); decimal
  strikes use Deribit's `d` (`9d5` = 9.5); names are validated file-safe.
- Inverse (`BTC-…`) and non-linear contracts are refused with a named reason (E18).
- Files ≤ 200 lines; KDoc; commits via the helper; network tests never run in the suite (recorded
  fixtures only, with provenance).

## Review focus

- A contract whose settlement or quote currency is not the root's (the `SOL_USDC-13FEB24` listing quoting SOL) → skipped with a warning, never catalogued under a wrong currency.
- Price-dependent tick: a level of 1005 snaps on the 20 grid, 995 on the 5 grid, in the direction that never fills early.
- A strategy naming an option missing from the catalog → fails before the run, naming the refresh command.
- Deribit paging (`has_more`, `continuation`) and request-rate limits handled without losing contracts.
- Existing `when (derivative)` sites (accounting, margin, futures routing) treat an option deliberately, not by an `else`.

---

### Task 1: OptionTerms and option roots
- `OptionTerms`, `OptionStyle`, `OptionSettlement`; `OptionRoot` + `OptionRootsFile` (`options:` list, strict keys, near-miss key check, validation wrapped with the root name); `TickSteps` (base tick + steps) with `tickAt(price)` and directional snapping.
- Every `DerivativeTerms` consumer reviewed: futures-only code paths check `is FutureTerms` explicitly; `ExchangeSimulator`, `ContinuousChains`, `futuresSymbols` exclude options.
- Tests: parsing, refusals, tick steps, CFD/futures registries unchanged.

### Task 2: option catalog and registry
- `OptionContract(symbol, strike, right, expiryMs)`, `OptionCatalog`, `OptionCatalogStore` (JSON, like `ContractCatalogStore`), `OptionCatalogRegistry` (metadata for every catalogued contract; `missingReason` naming `qkt fetch <ROOT> --catalog`), layered by `BacktestInstruments`.
- Deribit name parsing (`<UNDERLYING>-<DDMMMYY>-<STRIKE>-<C|P>`, `d` decimals, expiry 08:00 UTC) cross-checked against the instrument's own `strike`, `option_type`, `expiration_timestamp`.

### Task 3: `qkt fetch <ROOT> --catalog` for Deribit
- `DeribitClient` (JSON-RPC over HTTPS, paging, 429 back-off), `DeribitOptionCatalog` (live + expired instruments, linear-in-root-currency filter with a warning per skipped contract), delivery prices for the root's settlement index.
- Recorded fixture responses with provenance; a manual real fetch documented in the research note.
