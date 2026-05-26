# Backtest vs live — execution parity

Where `qkt backtest` and live MT5 trading agree, where they don't, and what you can claim from a backtest report.

This is the execution-side companion to the data-side parity reports in this directory. Those compare TradingView vs MT5 as **market-data sources**. This one compares the **execution pipelines** that consume those ticks.

## The proven contract — strategy pipeline is shared

Both `Backtest` (`src/main/kotlin/com/qkt/backtest/Backtest.kt`) and `LiveSession` (`src/main/kotlin/com/qkt/app/LiveSession.kt`) construct the same `TradingPipeline`. The strategy compilation, indicator math, candle aggregation, rule firing, signal-to-`OrderRequest` translation, and risk engine are byte-identical between modes.

`BacktestLiveParityTest` at `src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt` enforces this contract: same ticks + same strategy must produce identical trade lists in both paths. CI runs it on every push.

If the trade lists ever drift in that test, the pipeline contract is broken and the test fails.

## The unproven part — broker layer is different

`BacktestLiveParityTest` uses `LiveSession` with its default `PaperBroker`. So the proven equation is:

```
Backtest + PaperBroker  ===  LiveSession + PaperBroker
```

It does **not** prove `Backtest === LiveSession + MT5Broker`. Every divergence below lives between `PaperBroker` and `MT5Broker`, and is invisible to the parity test.

## Catalog of broker-layer divergences

Each row lists the symptom, the source file the live behavior lives in, and whether the backtest models it.

| # | Concern | Backtest (`PaperBroker`) | Live (`MT5Broker`) | Status |
|---|---|---|---|---|
| 1 | **Volume quantization** | fills exactly the requested `quantity` (`PaperBroker.publishFill`) | rounds DOWN to `volume_step` from `/symbol_info` (`MT5Broker.quantizeForPlacement`, v0.26.3) | **closed in MT5_SIM** |
| 2 | **Price rounding** | uses the raw 8-decimal `BigDecimal` from the engine | rounds `price`/`sl`/`tp`/`stopLimit` to `digits` (HALF_EVEN) before sending (`MT5Broker.quantizeForPlacement`, v0.26.4) | **closed in MT5_SIM** |
| 3 | **Below-`volume_min` orders** | fills regardless of how small | rejected pre-flight with `OrderRejected("quantized volume below venue volumeMin …")` | **closed in MT5_SIM** |
| 4 | **Bracket entry fills** | fills at `tickPrice` the moment the trigger is crossed (`PaperBroker.fillFromTrigger`) | venue fills at actual ask (for BUY_STOP) or bid (for SELL_STOP) when the trigger prints | **closed in MT5_SIM** |
| 5 | **Spread / slippage** | uses `tick.price` (the mid set by `Mt5TickFeedSource` when `last=0`) | live pays the venue spread; volatile bars also slip | **closed in MT5_SIM** |
| 6 | **Market-order fill price** | `priceProvider.lastPrice(symbol)` — the last tracked tick (`PaperBroker.fillMarket`) | MT5 fills at venue ask/bid at submit time, with `deviation` slack | **closed in MT5_SIM** |
| 7 | **Contract size** | reads `contractSize` from `InstrumentRegistry`; both backtest and live multiply through it | MT5 sizes positions as `lot × contract_size` (XAUUSD = 100 oz/lot) | **closed in Phase 30** |
| 8 | **`tradeStopsLevel`** | not enforced (closing that is the simulator's job) | rejected pre-placement in `MT5Broker` since Phase 30 | live closed in Phase 30, backtest open |
| 9 | **OCO atomicity** | both legs always coupled in memory | emulated via comment-tag prefix + position poller; cancel-on-fill has a few-ms window between the fill event and the sibling-cancel request | divergent edge case |
| 10 | **Pending-order persistence** | always in memory of the running backtest | persists to the broker's order book; daemon restart re-reads via `MT5StateRecovery` | divergent edge case |
| 11 | **Latency** | instantaneous tick → fill | gateway HTTP round-trip + venue execution latency | divergent |
| 12 | **Retcode handling** | no concept | MT5-specific retcodes (`10009`, `10015`, `10015` price, etc.) translated to `OrderRejected` reasons | divergent |
| 13 | **Trading calendar / sessions** | runs through every tick the feed produces | respects venue session hours (gaps in `/tick` during weekends, holidays) | aligned in qkt by the `TradingCalendar` injection; divergent if backtest data covers a window live wouldn't trade |

## Rows 1, 2, 3, 4-6 — closed in MT5_SIM

`MT5BrokerSimulator` (added 2026-05-25, issue #43) is an opt-in backtest broker
that mirrors the live MT5 venue's quantization, rounding, volume-min validation,
and ask/bid fill rules. Closes the five "high-impact, deterministic" divergences
that previously made backtest fill prices and sizes diverge from what live MT5
would have produced.

**Opt in:**

```bash
qkt backtest <file> --broker mt5-sim ...
```

Or programmatically:

```kotlin
Backtest(strategies = ..., ticks = ..., brokerKind = BrokerKind.MT5_SIM, instruments = registry)
```

**What it requires:** `InstrumentMeta` for every symbol the strategy trades
(volumeStep, volumeMin, digits, pointSize). Provided via `YamlInstrumentRegistry`
loaded from `data/instruments.yaml`, or any other `InstrumentRegistry`
implementation. A missing entry fails the order with `OrderRejected`, consistent
with the Phase 30 hard-error stance.

**What it doesn't model yet:** row 8 (`tradeStopsLevel`), row 9 (OCO atomicity),
row 11 (latency — separate issue #140), row 12 (retcodes). These remain
divergent under `--broker mt5-sim` too; tracked as follow-ups.

`PaperBroker` remains the default. Existing backtests are unaffected unless they
opt in explicitly.

## Contract size (#7) — closed in Phase 30

Phase 30 added an `InstrumentMeta` primitive resolved at strategy load via [`InstrumentRegistry`](../phases/phase-30-instrument-metadata.md). Both `PaperBroker` and live MT5 paths multiply through `contractSize`, so a backtest trade and a live trade for the same symbol now use the same dollar-per-unit-of-price math. The hedge-straddle's `/100` workaround was removed as part of the migration.

Historical note kept for context: before Phase 30, backtest PnL was off by a factor of `contractSize` (~100× for XAUUSD), so it could be used for ranking and drawdown comparison but not as a dollar figure. That caveat no longer applies.

## How to use the backtest safely today

- **Use the backtest to compare strategies and parameters against each other.** Rule firing, signal counts, win rate, drawdown ordering, sharpe ranking all transfer.
- **PnL is now in real dollars** as of Phase 30 — but **still don't expect bit-identical live numbers**. Spread, slippage, latency, and bid/ask fill prices (rows 4–6, 11) still differ. Treat backtest PnL as a defensible estimate, not a tick-perfect prediction.
- **Don't backtest a brand-new strategy and immediately wire to live without a paper-mode run.** The MT5Broker can reject orders the backtest happily accepted (rows 3, 8 — live closed in Phase 30 but backtest still permissive — and 12).
- **Treat `qkt backtest` as the pipeline test plus a contract-size-correct PnL test, but not yet a fill-price test.** Closing the fill-price gap is the `MT5BrokerSimulator` work.

## What would close the gap

A `MT5BrokerSimulator` usable in backtest mode, mirroring:

- `volume_step` / `volume_min` quantization (rows 1, 3)
- `digits` price rounding (row 2)
- bid/ask fills at venue prices (rows 4-6)
- contract-size multiplier in fill PnL (row 7, the big one)
- `tradeStopsLevel` rejection (row 8)
- realistic latency model (rows 11)

Plus a sibling test `BacktestMT5LiveParityTest` that replays a recorded MT5 session and asserts the simulator matches. That's the work that turns "backtest of hedge_straddle returned +$13.25" into "this is what live should look like."

Tracked as a future engine phase — not scheduled. Until it ships, this document is the answer to "can I trust the backtest."

## File pointers

- Pipeline contract — `docs/phases/phase-4-backtest.md` (the "Same pipeline, live execution" section)
- Pipeline parity test — `src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt`
- Live-pipeline construction — `src/main/kotlin/com/qkt/app/LiveSession.kt` (`broker = buildBroker(paperBroker, ...)`)
- Backtest-pipeline construction — `src/main/kotlin/com/qkt/backtest/Backtest.kt:fromStore`
- `PaperBroker` fills — `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- `MT5Broker` quantization (v0.26.3 + v0.26.4) — `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` (`quantizeForPlacement`)
- Strategy-port parity (separate concern) — `qkt-prod/docs/PARITY.md`
- Data-source parity (separate concern) — `docs/parity/parity-bars-xauusd-m5.md`, `docs/parity/parity-ticks-xauusd.md`
