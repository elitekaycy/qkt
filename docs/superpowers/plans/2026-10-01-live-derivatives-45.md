# Live futures and options (phase 45) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** a strategy that backtests on option structures, chain analytics streams or continuous futures
deploys and trades live through a `type: gateway` account, with the same meaning as its backtest.

**Architecture:** live reuses the backtest's pieces wherever they are mode-free (instrument layering,
calendar rules, structure machinery, `ContinuousContractBroker`) and adds only what live alone needs: a
chain recorder fed by gateway quotes, a live chain analytics feed, read-only feed handling in broker
routing, and restart persistence for structures.

**Spec:** `docs/superpowers/specs/2026-09-30-futures-options-design.md` (live sections), wire spec
`docs/superpowers/specs/2026-10-01-vgp-v1-wire.md`.

## Global Constraints

- CFD sessions build exactly what they built before: every change is keyed on a declared `futures:` or
  `options:` root, an `OPTIONS:`/`CHAIN:` stream, or a continuous symbol.
- Files ≤ 200 lines (tests ≤ 220); KDoc on public API; ktlint clean; golden CFD pins green.
- Every live/backtest difference that remains is a row in `docs/parity/backtest-vs-live.md`.

## Gap evidence (survey at `8adb74df`)

1. `SessionBrokers.buildBroker` demands a broker for the read-only `options`/`chain` labels.
2. Option legs picked at fire time have no route (routes are exact symbol sets).
3. The daemon registry has no futures or options layers: every `OPEN` is suppressed live.
4. Nothing writes chain snapshots live, so `ChainView` has no chain at the clock.
5. `CHAIN:` streams have no live source and fail warmup.
6. With no options layer, every option contract tick forms candles.
7. A strategy whose first stream is `OPTIONS:`/`CHAIN:` runs on the FX calendar.
8. Contract legs are not reconciled at startup, and structures do not survive a restart.
9. Continuous futures are refused live and would route unstitched.

## Tasks

### Task 1: one instrument registry builder for backtest and live
- Rename `cli/BacktestInstruments` to `cli/InstrumentFiles` (same layering); the daemon builds its
  registry with it instead of YAML + standard. Fixes gaps 3 and 6.
- Test: a daemon-style registry built from an instruments file with `options:` resolves an option
  contract and reports the root; a CFD-only file resolves exactly as before.

### Task 2: broker routing for read-only feeds and fed-root contracts
- `SessionBrokers.buildBroker` drops `OPTIONS:`/`CHAIN:` streams (read-only), and routes every
  catalogued contract of a fed root `OPTIONS:<V>.<R>` to the account labelled `<v>`, on the same broker
  instance as that account's declared streams. Fixes gaps 1 and 2.
- Tests: a strategy feeding a root and declaring no contract gets one broker for the root's account; a
  leg symbol of the root routes there; an unknown contract still has no route.

### Task 3: one calendar rule for a strategy's symbols
- `strategyCalendar(symbols, instruments, calendarOf)` (futures roots' calendar, else the first symbol
  that is not a read-only feed, else crypto) used by `backtestCalendar` and by live
  (`StrategyHandle`, `PortfolioDeployer`) with `liveCalendarFor` as `calendarOf`. Fixes gap 7.

### Task 4: live chain recorder over gateway quotes
- `ChainRecorder` (derivatives.options.chain, venue-free): latest quote per catalogued contract; at
  each cadence boundary appends a `book` snapshot at the boundary instant (each quote aged to it,
  expired and uncatalogued contracts left out) on its own writer thread.
- The gateway account converts quotes of a fed root to `ChainQuote`s (`QuoteSource.BOOK`) and feeds one
  recorder per root when the root declares `chains: book`; cadence `chain_snapshot_seconds` (default
  300). `ConnectorContext` carries the instrument registry. Fixes gap 4.

### Task 5: live chain analytics feed
- `ChainAnalyticsMarketSource.liveTicks`: polls the root's day file (the `ChainView` stamp rule) and
  emits one metric tick per snapshot newer than the last emitted; the live composite routes `CHAIN:`
  to it when an options root is declared; warmup reads its bars. Fixes gap 5.

### Task 6: restart safety
- Startup reconcile covers persisted and venue contract symbols, not only declared streams.
- `StructureBook` state persists with the session and is restored before the first tick.

### Task 7: live continuous futures
- Load futures catalogs (Task 1), route `ROOT@front` through `ContinuousContractBroker` over the
  account broker, map live contract ticks to the continuous symbol across rolls, futures calendar
  (Task 3), remove the refusal in `LiveSymbolChecks`. Fixes gap 9.

### Task 8: docs, parity rows, review
- Connector reference, structures doc (live section), parity rows for every remaining difference,
  one fresh whole-branch review and one fix pass.
