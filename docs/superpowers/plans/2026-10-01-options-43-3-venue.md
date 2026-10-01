# Phase 43.3: option venue for backtests — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `.qkt` strategy can name a catalogued option contract, see its quotes from the stored
chain, buy and sell it on a simulated venue that fills at the **next** snapshot's bid/ask, pay the
venue's capped fees, and hold it to expiry for cash settlement at the delivery price, with P&L that
reconciles to the premiums, fees and settlement value.

**Architecture:**
- **Symbols.** qkt names an option `DERIBIT:BTC_USDC_25DEC26_92000_C` (Deribit's name with `-`
  written `_`, an identifier the DSL already parses); venue data keeps venue names. `OptionSymbols`
  maps both ways exactly.
- **Quotes.** `OptionQuotes.sides(quote, root)` is the one rule for a quote's tradeable sides: a book
  row gives its own bid/ask (a missing or zero side is absent); a trade row whose mark is at most
  `maxQuoteAge` old gets `mark ∓ max(tick at mark, markSpread × mark)` snapped outward to the tick
  grid (a bid at or below zero is absent); an older trade row has no sides.
- **Data.** `OptionChainMarketSource` (TICKS capability, routed for the run's option symbols in
  `StoreMarketSource`) emits one `Tick(symbol, mark, atMs, bid, ask)` per contract per snapshot from
  the root's declared chain series (`chains: trade | book`), cut at expiry. A mark ≤ 0 emits nothing.
- **Venue.** `OptionExchange : Broker` (NETTING) holds MARKET and LIMIT orders; on a tick of that
  contract strictly after the order's submit time it reads the snapshot quote at that instant
  through `ChainQuoteLookup` (day-cached store reads at exact instants, never ahead of the clock):
  BUY market fills at the ask, SELL at the bid, a missing side rejects the order; a resting limit
  fills at its limit when the side crosses it. Volume is checked against step/min; long-only in
  43.3 (a SELL beyond the held quantity is rejected, shorts arrive with structures and margin in
  43.4).
- **Fees.** `OptionFee`: per contract `min(takerFeeRate × underlying, feeCapRate × premium) ×
  contractSize`; at expiry `min(deliveryFeeRate × delivery, feeCapRate × intrinsic) × contractSize`
  for in-the-money contracts; both as `EXCHANGE_FEE` venue costs.
- **Expiry.** `OptionExpiry` settles every net position on the first tick of any symbol at or after
  expiry at intrinsic value from the catalog's delivery price (`max(S−K,0)` / `max(K−S,0)`), exit
  reason `EXPIRY`, recorded in the `SettlementLog`; no delivery price → the run fails naming the
  catalog refresh.
- **Routing.** `optionSymbols(...)` next to `futuresSymbols`; `ReplayFuturesRoutes` (renamed only if
  needed) adds the option route; the `DerivativeSymbolChecks` refusal becomes "options need a chain
  series and a catalog" checks.

**Spec:** `docs/superpowers/specs/2026-09-30-futures-options-design.md` §6.5, §11 E19/E21;
research `docs/research/2026-10-01-deribit-options-free-data.md` §6–8.

## Global constraints

- CFD and futures behaviour unchanged: golden pins, futures identity tests, parse pins green.
- No DSL grammar change. No look-ahead: a fill reads only the snapshot at the filling tick's
  instant, strictly after the decision.
- Money in `BigDecimal`; fees and settlement exact; files ≤ 200 lines; KDoc; commits via the helper.
- Divergences catalogued in `docs/parity/backtest-vs-live.md`: top-of-book fills of any size, the
  trade-chain spread model, snapshot-granular limit fills, marks at bid/ask (not clamped mid).

## Review focus

- A market order on a snapshot with no ask (BUY) or bid (SELL) is rejected, never filled at the mark.
- An order decided on a snapshot fills on a later snapshot, even when another contract's tick at the
  same instant arrives after the decision.
- An expired contract held with no further ticks of its own still settles (on any later tick).
- An out-of-the-money expiry books zero settlement value and no delivery fee; the fee cap binds for
  cheap options.
- A strategy naming an option with no chain data, no catalog entry, or no delivery price fails before
  or at the first affected point with a message naming the fix.

---

### Task 1: option symbols in the qkt form
- `OptionSymbols.qktCode(venueName)` / `venueName(qktCode, rootCode)`; `OptionCatalogRegistry` keys
  and root claims use the qkt form; a test proves `DERIBIT:BTC_USDC_25DEC26_92000_C` parses in a
  `.qkt` file and the hyphen form does not.

### Task 2: OptionQuotes and option root keys
- `chains: trade | book` (required for a traded root), `markSpread` (required for `trade`),
  `maxQuoteAge` (duration, default `1h`), `feeCapRate`, `deliveryFeeRate` under `options:`.
- `OptionQuotes.sides(...)` with the rules above; tests for one-sided books, zero sides, stale
  marks, the tick-step grid at a step boundary, and a spread that would push the bid below zero.

### Task 3: OptionChainMarketSource and ChainQuoteLookup
- Ticks per contract from the declared series; expiry cut; mark ≤ 0 skipped; `--bars` refused for
  option symbols; the lookup answers the quote at an exact instant with a one-day cache.

### Task 4: OptionExchange with fees
- MARKET/LIMIT, next-snapshot fills, side rules, long-only, volume checks, fee model, cancel.

### Task 5: OptionExpiry and routing
- Settlement on any tick at or after expiry; delivery fee; `optionSymbols`; route wiring; refusal
  replaced by the checks above.

### Task 6: end-to-end on real data and P&L identity
- A recorded BTC_USDC chain fixture (real snapshots with provenance) through a `.qkt` strategy that
  buys a call and holds it to expiry; asserts realized P&L = (settlement − premium) × size − fees,
  reports present, and CFD pins unchanged. Docs: how-to Scenario 2c, divergence catalog rows.
