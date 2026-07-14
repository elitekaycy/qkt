# Backtest vs live — execution parity

Where `qkt backtest` and live MT5 trading agree, where they don't, and what you can claim from a backtest report.

This is the execution-side companion to the data-side parity reports in this directory. Those compare TradingView vs MT5 as **market-data sources**. This one compares the **execution pipelines** that consume those ticks.

## The proven contract — strategy pipeline is shared

Both `Backtest` (`src/main/kotlin/com/qkt/backtest/Backtest.kt`) and `LiveSession` (`src/main/kotlin/com/qkt/app/LiveSession.kt`) construct the same `TradingPipeline`. The strategy compilation, indicator math, candle aggregation, rule firing, signal-to-`OrderRequest` translation, and risk engine are byte-identical between modes.

`BacktestLiveParityTest` at `src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt` enforces this contract: same ticks + same strategy must produce identical trade lists in both paths. CI runs it on every push.

If the trade lists ever drift in that test, the pipeline contract is broken and the test fails.

## Broker-layer proof boundary

`BacktestLiveParityTest` uses `LiveSession` with its default `PaperBroker`. That test proves:

```
Backtest + PaperBroker  ===  LiveSession + PaperBroker
```

That test alone does **not** prove `Backtest === LiveSession + MT5Broker`. Separate
`MT5BrokerSimulatorTest` coverage pins deterministic venue rules. The authentic
`MT5GoldenVerifierTest` additionally replays one retained Exness demo XAUUSD market order against
its raw bid/ask ticks and matches the venue deal at zero price and volume tolerance.

The authentic row is deliberately narrow. It proves one demo market-fill shape, instrument
metadata normalization, and source provenance. It does not prove OCO cancellation races, partial
fills, rejection retcodes, latency distributions, pending-order recovery, or a second broker.

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
| 8 | **`tradeStopsLevel`** | mt5-sim (opt-in `enforceStopsLevel`) validates pending trigger/limit distance from current price AND bracket exits from entry | rejected pre-placement in `MT5Broker`: entry distance from current price, SL/TP distance from entry, freeze-level on modifies (#638) | both halves aligned in #658; freeze-level live-only (see residuals) |
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

**What remains empirical or live-only:** venue OCO cancellation races, exact rejection
retcodes, and latency calibration. The simulator enforces configured stop-distance rules and
supports deterministic latency/rejection stress models, but those configured distributions are
not measurements of a particular live session.

`PaperBroker` remains the default. Existing backtests are unaffected unless they
opt in explicitly.

## Contract size (#7) — closed in Phase 30

Phase 30 added an `InstrumentMeta` primitive resolved at strategy load via [`InstrumentRegistry`](../phases/phase-30-instrument-metadata.md). Both `PaperBroker` and live MT5 paths multiply through `contractSize`, so a backtest trade and a live trade for the same symbol now use the same dollar-per-unit-of-price math. The hedge-straddle's `/100` workaround was removed as part of the migration.

Historical note kept for context: before Phase 30, backtest PnL was off by a factor of `contractSize` (~100× for XAUUSD), so it could be used for ranking and drawdown comparison but not as a dollar figure. That caveat no longer applies.

## How to use the backtest safely today

- **Use the backtest to compare strategies and parameters against each other.** Rule firing, signal counts, win rate, drawdown ordering, sharpe ranking all transfer.
- **PnL is now in real dollars** as of Phase 30 — but **still don't expect bit-identical live numbers**. Spread, slippage, latency, and bid/ask fill prices (rows 4–6, 11) still differ. Treat backtest PnL as a defensible estimate, not a tick-perfect prediction.
- **Don't backtest a brand-new strategy and immediately wire to live without a paper-mode run.** Plain `PaperBroker` remains permissive, while MT5 can still reject for live-only retcodes and session state.
- **Use `--broker mt5-sim` for venue-shaped fill tests.** The default paper tier is still a fast research model and should not be cited as MT5 fill-price evidence.

## Remaining MT5 gaps

`MT5BrokerSimulator` now models deterministic volume and price quantization, bid/ask fills,
contract-size PnL, stop-distance rejection, and configurable latency/rejection stress. The
authentic golden replay covers one market order. Broader venue claims still require
additional captures for pending/OCO orders, partial fills, rejected requests, and volatile-period
latency. Those residuals must not be inferred from the single exact fill. Operational proof for a
second MT5 profile remains tracked by #44.

## 2026-06-10 audit addendum — divergences this catalog was missing

Rows surfaced by the full engine audit (#142, issues #356-#401). Items marked
FIXED now behave identically in both modes; the rest are inherent differences to
keep in mind when reading a backtest.

| # | Divergence | Status |
| --- | --- | --- |
| A1 | Halt rules: backtest used to wire ZERO halt rules while live halts | FIXED (#362) — backtests build the same config-driven halt set and report halts |
| A2 | Warmup: live waited a full live window post-deploy; backtest consumed the first N bars | FIXED (#383) — seeded history credits the gate in live |
| A3 | GTD expiry: venue ignores expiration; engine sweep was disabled | FIXED (#368) — engine sweep owns GTD in live; backtest sweep identical |
| A4 | Trigger side: everything triggered on mid; venue triggers on bid/ask | FIXED (#382) — side-aware in PaperBroker, MT5_SIM, and engine-held triggers; bar-sourced backtests have no quote depth, so they still effectively trigger on the synthesized price |
| A5 | Costs: live PnL/halts were commission/swap-blind | FIXED (#392, #644) — venue costs net out of realized in live; backtest models per-lot commission and deterministic long/short swap points at configured UTC rollovers. Live uses venue-reported swap, while replay uses the point-in-time rates in `instruments.yaml`; rate-history drift remains an input-data divergence |
| A6 | Bar synthesis order: `BarTickFeed` emits O→L→H→C — for SHORT positions the favorable extreme arrives before the adverse one (optimistic). One ordering cannot be worst-case for both sides | INHERENT for the plain `--bars` research tier — read short-side bar results conservatively. RESOLVED by `--bars --tick-fills`, which resolves fills on real ticks for every fill-possible bar and is byte-identical to a full-tick replay (`TickResolvedParityTest`) |
| A7 | Tick sampling: backtest replays every stored tick; live MT5 polls at ~50ms with dedupe and burst shedding. Engine-held trails/latches/stacks walk different price paths | INHERENT — quantified in the data parity reports |
| A8 | SCHEDULE timing: backtest fires on the next replayed tick after the trigger time; live fires from a 1Hz wall-clock heartbeat even with no ticks | INHERENT — sub-second placement differences |
| A9 | Calendars: the backtest CLI uses fixed per-symbol calendar rules (crypto for `BTC*`/`*USDT`, FX default otherwise); live uses the broker profile's calendars. The FX weekend boundary is a FIXED UTC hour year-round and does not track New York DST (up to 1h off near the close/open in winter) | INHERENT — pinned by `FxCalendarTest` |
| A10 | `x.bid` / `x.ask` / `x.spread` evaluate Undefined on bar-sourced backtest data — spread-aware rules silently never fire in bar backtests (tick-sourced backtests carry real quotes) | OPEN (#389) — prefer tick data for spread-aware strategies |
| A12 | Quiet-symbol candle close: live closes an ended bar from the 1Hz heartbeat even with no next tick; backtest closes only on the next replayed tick (event time is its only clock) | INHERENT — affects the last bar before a session gap |
| A11 | Live-only operational effects: restart reconcile, OCO restore, poller-synthesized closes, gateway-outage suspensions, the runaway breaker and market-data gate including its broker-clock-skew check (#395/#396/#810 are live-only by design) | INHERENT — none have a backtest equivalent. The expired-before-submit GTD reject (#811) is wired in both modes but cannot fire under event time, where a fresh deadline is always in the future |

## 2026-07-03 hardening pass — parity-audit rows resolved (#658)

The 2026-07-02 parity audit (issues #614-#643) was resolved in one hardening PR.
Statuses below supersede any older row that disagrees; each FIXED row cites the
test class that pins it.

| Issue | Resolution | Pinned by |
| --- | --- | --- |
| #614 | Live deploy replays seeded candles through the full per-alias update path (indicators, aggregates, rolling snapshots) with rules and position transitions suppressed; session/anchored indicators declare timeframe-aware warmup horizons instead of `warmupBars = 1` | `CompiledStrategyAutoWarmupTest`, `WarmupRequirementsTest` |
| #615 | Live fills book the venue-reported executed volume (quantized, partial-aware); a partial response without a volume resolves as unknown-outcome instead of booking the full request | `MT5BrokerIntegrationTest`, `MT5ClientTest` |
| #616 | Engine-initiated closes attach venue deal costs (`commission + swap + fee`) to the fill; the shared pipeline nets them from realized PnL and halt inputs in both modes | `MT5BrokerIntegrationTest` |
| #617/#618 | Live armed trails cancel when their venue position ticket no longer exists (never a naked market order), and fall back to the strategy's PRIMARY position ticket when the leg map has no entry | `OrderManagerAttachedBracketTest`, `StrategyPositionTrackerStackTest` |
| #619 | RESIZE quantizes deltas to `volume_step`, floors at `volume_min`, shrinks by closing the primary's exact venue ticket, and reuses a stable order id so an in-flight resize cannot double-submit | `ActionCompiler` resize tests |
| #620 | Portfolio live sessions share one `BookRiskController` (exposure limit rule + sizing scale), sampled from real child legs each risk tick; per-child `maxDailyLoss` became book-wide to match the backtest | `LiveSessionBrokerCoverageTest` |
| #621 | `time_msc` fields are UTC epoch millis and are no longer offset-shifted; only naive datetime strings use the broker offset (one rule, one boundary) | `MT5ClientTest`, `Mt5BarFetcherTest` |
| #622 | A configured live session fails closed: no silent `PaperBroker` fallback for unrouted symbols | `LiveSessionBrokerCoverageTest` |
| #623 | Session-scoped indicators (`SessionRange`, `SessionVwap`, `AnchoredReturn`) refuse to latch partial initial windows — Undefined until the first complete window | `SessionRangeTest`, `SessionVwapTest`, `AnchoredReturnTest` |
| #624 | The tick-fills classifier expands the mid bar range by the slice's max half-spread, so levels crossed only by the executable quote resolve on real (side-aware) ticks | `OrderManagerIntrabarFillTest`, `BarResolvedFeedTest` |
| #625 | Backtest sims never fill an order cancelled earlier in the same tick | `PaperBrokerTest`, `MT5BrokerSimulatorTest` |
| #626 | Backtest halts cancel resting pendings, matching the live kill-switch | `ReplayEngine` halt subscription |
| #627/#628 | Portfolio backtests refuse WHEN..RUN / CAPITAL topologies and `--bars`/`--bar-tf`/`--tick-fills` rather than produce a misleading result | `BacktestCommandPortfolioTest` |
| #629 | `qkt sweep --tick-fills` errors instead of silently downgrading | `SweepCommandTest` |
| #630/#641 | `--bars` validates bar-store coverage per trading day (fail-loud, `--allow-incomplete` escape); non-Dukascopy streams are completeness-validated; empty feeds error instead of replaying nothing | `BarCompletenessValidatorTest`, `BacktestFromStoreTest` |
| #631 | MT5 warmup bars normalize bid OHLC to mid via half-spread, matching the backtest's mid bars | `Mt5BarFetcherTest` |
| #632/#633 | `NOW.*` and schedule actions evaluate at event time (bar close / scheduled fire time), and missed schedule occurrences replay one-by-one instead of coalescing | `NowAccessorEvalTest`, `ScheduleRunnerTest` |
| #634 | `CandleAggregator` never reopens a closed window; late ticks are dropped and counted | `CandleAggregatorTest` |
| #635 | Sim StopLimit/IfTouched-LIMIT activate a resting limit (no instant fill at the limit); limit fills are limit-or-better, never slipped adversely | `PaperBrokerTest`, `MT5BrokerSimulatorTest` |
| #636 | Expiry wins the deadline instant in both venue-held (sim `expireGtd` before the trigger pass) and engine-held (`now >= deadline`) paths | `OrderManagerGtdSweepTest` |
| #637 | A triggered order re-checks its live state before broker submission — a same-pass cancel can no longer double-submit | `OrderManagerBracketTest` |
| #639 | Crossed stored quotes (bid > ask) are dropped identically at read time by CSV and binary feeds, warn-counted, instead of crashing the replay | `CsvTickFeedTest`, `BinaryTickParityTest` |
| #640 | Fetch persists tick volume; old cached rows derive volume from stored side volumes at read time | `DukascopyTickFetcherTest`, `TickAssemblerTest` |
| #643 | Plain `--bars` stops that gap through their level fill at the adverse opening print, not the level | `PaperBrokerTest` |
| #390 | Bracket exits re-anchor on the actual fill price (fallback OCO and venue-attached modify both) | `OrderManagerAttachedBracketTest`, `OrderManagerTier2FallbackTest` |

### Residual divergences (known, accepted, tracked)

| Residual | Behavior | Tracking |
| --- | --- | --- |
| CROSSES cold start | Warmup replay does not evaluate rule expressions, so a `CROSSES` node's prev-state is unset on the first post-deploy bar — it returns Undefined (rule does not fire) for exactly one bar. Fail-safe: a missed signal, never a wrong one | inherent to replay-without-firing |
| Freeze-level in backtest | Live `modifyPosition` rejects SL/TP moves inside `SYMBOL_TRADE_FREEZE_LEVEL` (surfaced + logged); the mt5-sim does not model freeze-level, so a backtest trail always tightens where live may be refused | #638 residual |
| Tick-fills synthetic marks | A symbol with an open position but no live orders resolves SYNTHETIC under `--tick-fills`, so its intrabar equity marks come from synthetic points (fills are exact; drawdown sampling is approximate) | #642 residual |
| Venue partials on fallback exits | When a venue partial fills a fallback (non-attached) bracket, exits are sized to the first fill's volume; a later remainder fill has no engine exit | follow-up if partial-fill venues go live |
| Already-crossed native stops | The fill decision is aligned: MT5 converts a STOP already through the latest ask/bid to MARKET (StopLimit to LIMIT), matching the engine-held path. Backtest fills on its crossing tick, while live fills after dispatch at the venue's later executable price, so latency/slippage can still change the fill price | #815; decision pinned by `AlreadyCrossedStopParityTest`, live wire/protection by `MT5BrokerIntegrationTest` |

## File pointers

- Pipeline contract — `docs/phases/phase-4-backtest.md` (the "Same pipeline, live execution" section)
- Pipeline parity test — `src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt`
- Live-pipeline construction — `src/main/kotlin/com/qkt/app/LiveSession.kt` (`broker = buildBroker(paperBroker, ...)`)
- Backtest-pipeline construction — `src/main/kotlin/com/qkt/backtest/Backtest.kt:fromStore`
- `PaperBroker` fills — `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- `MT5Broker` quantization (v0.26.3 + v0.26.4) — `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` (`quantizeForPlacement`)
- Strategy-port parity (separate concern) — `qkt-prod/docs/PARITY.md`
- Data-source parity (the prices, separate concern) — `docs/parity/parity-dukascopy-vs-mt5-xauusd.md` (dukascopy is the backtest source); `docs/parity/parity-bars-xauusd-m5.md`, `docs/parity/parity-ticks-xauusd.md` (TV vendor cross-check)
