# MT5BrokerSimulator — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close five execution-parity divergences between backtest and live MT5 (rows 1, 2, 3, 4-6 in `docs/parity/backtest-vs-live.md`) by adding an opt-in `MT5BrokerSimulator` next to `PaperBroker`.

**Architecture:** New class implementing the existing `Broker` interface, used by `Backtest` when the caller passes `brokerKind = BrokerKind.MT5_SIM`. Reads `InstrumentMeta` from the existing `InstrumentRegistry` (hard-error on missing, consistent with Phase 30). Quantizes volume and rounds prices the same way `MT5Broker.prepareForPlacement` does. Pulls bid/ask off the tick for fills; falls back to a synthetic spread when ticks don't carry bid/ask.

**Spec:** GitHub issue [#43](https://github.com/elitekaycy/qkt/issues/43).

**Working branch:** `issue43-mt5-broker-simulator`. One PR into `dev` at the end.

---

## Scope decisions (made autonomously per session brief)

| Decision | Choice | Why |
|---|---|---|
| Class shape | New `MT5BrokerSimulator` (additive) | Doesn't break `PaperBroker`; users opt in. Reversible later if we want to make it the default. |
| InstrumentMeta requirement | Hard-error if missing | Consistent with Phase 30 — "every symbol needs an entry". Silent defaults hide bugs. |
| Spread source | Prefer `tick.bid`/`tick.ask`; synthetic fallback | Real CSV ticks may not carry bid/ask. Synthetic spread = `pointSize × spreadPoints` (default 2 points). |
| Slippage model | Pluggable `SlippageModel` interface; default `ZeroSlippage` | Deterministic by default. `FixedPointsSlippage` provided for testing realistic worst-case. |
| Migration | Opt-in via `BrokerKind` enum on `Backtest` constructor; CLI flag `--broker mt5-sim` | Default unchanged. Existing baselines stable. Breaking-change label not actually triggered yet. |

These are conservative defaults. If the user wants the simulator as the default in a follow-up, that's a one-line change.

---

## File structure

| File | Action |
|---|---|
| `src/main/kotlin/com/qkt/broker/SlippageModel.kt` | Create — interface + `ZeroSlippage` + `FixedPointsSlippage` |
| `src/main/kotlin/com/qkt/broker/MT5BrokerSimulator.kt` | Create — main simulator class |
| `src/main/kotlin/com/qkt/backtest/BrokerKind.kt` | Create — `enum class { PAPER, MT5_SIM }` |
| `src/main/kotlin/com/qkt/backtest/Backtest.kt` | Modify — accept `brokerKind`, dispatch to simulator |
| `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` | Modify — `--broker mt5-sim` flag |
| `src/test/kotlin/com/qkt/broker/MT5BrokerSimulatorTest.kt` | Create — unit tests for quantization, rounding, bid/ask fills, slippage |
| `src/test/kotlin/com/qkt/backtest/BacktestMt5SimTest.kt` | Create — integration test wiring through Backtest |
| `docs/parity/backtest-vs-live.md` | Modify — mark rows 1, 2, 3, 4-6 "closed in MT5_SIM" |
| `docs/reference/cli-commands.md` | Modify — document `--broker` flag |

---

## Tasks

- [x] Plan written
- [ ] **Task 1: SlippageModel** — interface, ZeroSlippage, FixedPointsSlippage
- [ ] **Task 2: MT5BrokerSimulator** — implements Broker; quantizes volume DOWN to `volumeStep`; rejects below `volumeMin`; rounds prices HALF_EVEN to `digits`; market BUY fills at `ask` (or `price + spread/2`), SELL at `bid` (or `price - spread/2`); bracket triggers fill at ask/bid same way; applies SlippageModel on top of fill price
- [ ] **Task 3: BrokerKind enum + Backtest wiring** — `brokerKind: BrokerKind = BrokerKind.PAPER`. In `run()`, build a broker factory from the kind and use it instead of hard-coded `PaperBroker(...)`
- [ ] **Task 4: CLI flag** — `BacktestCommand` reads `--broker` (paper|mt5-sim, default paper). Surfaces in `--help`
- [ ] **Task 5: Unit tests for the simulator** — mirror `PaperBrokerTest` style
- [ ] **Task 6: Integration test** — backtest with `brokerKind=MT5_SIM`, assert quantization + spread reflected vs same backtest with PAPER
- [ ] **Task 7: Parity doc update** — flip rows 1, 2, 3, 4-6 from "divergent" to "closed (MT5_SIM)"; CLI ref entry
- [ ] **Task 8: Commit, push, PR**

---

## Acceptance

- All new unit + integration tests pass.
- Existing test suite still green (PAPER path unchanged).
- ktlint clean.
- `qkt backtest <file> --broker mt5-sim` runs end to end and produces a report with quantized volumes + spread-adjusted fills.
- Parity doc reflects which rows the simulator now closes.

---

## Deferred (separate follow-ups, not in this PR)

- Row 8 — `tradeStopsLevel` enforcement in backtest (separate concern, may need its own issue).
- Row 9 — OCO atomicity edge cases.
- Row 11 — latency simulation (separate issue #140).
- Loading captured bid/ask streams as the spread source — for now, synthetic fallback is the only option when ticks don't carry bid/ask.
- Making `MT5_SIM` the new default for backtest. Decide after operator usage confirms it doesn't surprise anyone.
