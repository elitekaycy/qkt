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
| 9 | **OCO atomicity** | both legs always coupled in memory | client-emulated independent tickets; cancel-on-fill has a poll/network window, and a detected second fill is closed immediately by its owned position ticket with a critical alert | divergent edge case with compensation |
| 10 | **Pending-order persistence** | always in memory of the running backtest | persists to the broker's order book; daemon restart re-reads via `MT5StateRecovery` | divergent edge case |
| 11 | **Latency** | instantaneous tick → fill | gateway HTTP round-trip + venue execution latency | divergent |
| 12 | **Retcode handling** | no concept | MT5-specific retcodes (`10009`, `10015`, `10015` price, etc.) translated to `OrderRejected` reasons | divergent |
| 13 | **Trading calendar / sessions** | runs through every tick the feed produces | respects venue session hours (gaps in `/tick` during weekends, holidays) | aligned in qkt by the `TradingCalendar` injection; divergent if backtest data covers a window live wouldn't trade |
| 14 | **Above-`volume_max` orders** | MT5_SIM rejects from `InstrumentMeta.volumeMax` | rejects pre-flight from `/symbol_info.volume_max` or a profile override | **closed in MT5_SIM** (`MT5BrokerSimulatorTest`, `MT5BrokerIntegrationTest`) |

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
authentic golden replay covers one market order. `qkt golden capture` now turns retained
demo sessions into checksummed tick/fill/order/gateway bundles, and `qkt golden materialize`
verifies and converts their structured ticks and candles into the normal replay stores. A captured
bundle is evidence, not automatically a new verifier assertion. The retained live-validation
scenarios can be checked offline with `scripts/live-validation/compare-golden-replay.sh`; it compares
full-tick, plain-bar, and tick-resolved report bundles with the linked live request and fill. Promote
representative captures into regression tests
for pending/OCO orders, partial fills, rejected requests, and volatile-period latency. Those
residuals must not be inferred from the single exact fill. `qkt backtest --chaos` applies the
seeded stress preset; it does not claim to reproduce every gateway HTTP or venue-retcode sequence.
Operational proof for a second MT5 profile remains tracked by #44.

Strict read-only captures use the same comparator in a separate mode. Their engine
journal records source-timeframe warmup ticks and exact DSL stream candles, allowing
the materializer to retain M1 and M5 bar stores without mixing synthetic warmup
streams. The comparator requires exact live/full-tick/plain-bar warmup and indicator
traces plus flat accounting; it makes no order/fill claim.

## 2026-06-10 audit addendum — divergences this catalog was missing

Rows surfaced by the full engine audit (#142, issues #356-#401). Items marked
FIXED now behave identically in both modes; the rest are inherent differences to
keep in mind when reading a backtest.

| # | Divergence | Status |
| --- | --- | --- |
| A1 | Halt rules: backtest used to wire ZERO halt rules while live halts | FIXED (#362) — backtests build the same config-driven halt set and report halts |
| A2 | Warmup: live waited a full live window post-deploy; CLI backtests consumed the first N in-window bars | FIXED (#383, #947) — one shared coordinator seeds closed pre-window history in live and backtest before DSL binding (`BacktestFromStoreTest`) |
| A3 | GTD expiry: venue ignores expiration; engine sweep was disabled | FIXED (#368) — engine sweep owns GTD in live; backtest sweep identical |
| A4 | Trigger side: everything triggered on mid; venue triggers on bid/ask | FIXED (#382) — side-aware in PaperBroker, MT5_SIM, and engine-held triggers; bar-sourced backtests have no quote depth, so they still effectively trigger on the synthesized price |
| A5 | Costs: live PnL/halts were commission/swap-blind | FIXED (#392, #644) — venue costs net out of realized in live; backtest models per-lot commission and deterministic long/short swap points at configured UTC rollovers. Live uses venue-reported swap, while replay uses the point-in-time rates in `instruments.yaml`; rate-history drift remains an input-data divergence |
| A6 | Bar synthesis order: `BarTickFeed` emits each bar's extremes adverse-first for the net position side (net LONG → Low first, net SHORT → High first, flat → Low first), decided after the bar's open tick so an entry filled on the open steers its own bar. Residual approximation: opposing exposure nets to one sign, and the true intra-bar path is unknowable from OHLC — plain-bars sweeps therefore run per-combo (`BacktestSweep`), since one shared tick stream cannot be adverse-first for every combo's positions | MITIGATED for the plain `--bars` research tier (pessimistic for both sides; was optimistic for shorts). RESOLVED by `--bars --tick-fills`, which resolves fills on real ticks for every fill-possible bar and is byte-identical to a full-tick replay (`TickResolvedParityTest`) |
| A7 | Tick sampling: backtest replays every stored tick; live MT5 used to poll `/symbol_info_tick`, which returns only the quote current at the instant of the call, so every tick arriving between two polls was lost permanently. Measured against Exness XAUUSD M1 bars over 240 bars / 74,331 ticks: live captured 52-214 of 310 ticks per bar depending on cadence, understating each bar's high by about 0.04 and overstating its low by about 0.06, compressing bar range 2.0-6.5% and ATR(1m) 1.9-5.4% below the venue's own bars. Engine-held trails, latches and stacks therefore walked a coarser price path than replay | FIXED — the live source now polls `/copy_ticks_range` for the window `(watermark, now]`, so the delivered stream no longer depends on poll cadence: a tick arriving between rounds falls inside the next window by construction. Aggregation itself was never the defect — fed every tick, `CandleAggregator` reproduces MT5's own bars exactly (239/240 bars bit-identical on OHLC; the one miss was a still-forming bar). Ticks are deduped against the watermark and merged across symbols into one timestamp-ordered stream (`Mt5TickRangePollingTest`). Verified live against a local Exness gateway with the real daemon: across 13 complete M1 bars, every one matched the venue's own bar EXACTLY on both `tick_volume` and range (293/293, 203/203, 171/171, 160/160, 159/159, 120/120, 109/109, 91/91, 135/135, 121/121, 135/135, 125/125, 203/203), the only residual being a constant +0.130 level offset — exactly half the 0.26 spread, since qkt builds from the bid/ask mid and MT5 builds from the bid. The same daemon on the predecessor endpoint under identical conditions under-counted every bar, by 20% to 59% (mean -37%), with a -12.8% range bias. Residual: bounded-queue shedding under load |
| A8 | SCHEDULE timing: backtest fires on the next replayed tick after the trigger time; live fires from a 1Hz wall-clock heartbeat even with no ticks | INHERENT — sub-second placement differences |
| A9 | Calendars: the backtest CLI uses fixed per-symbol calendar rules (crypto for `BTC*`/`*USDT`, FX default otherwise); live and portfolio book-risk annualization use the broker profile calendar. The FX weekend boundary is a FIXED UTC hour year-round and does not track New York DST (up to 1h off near the close/open in winter) | INHERENT — pinned by `FxCalendarTest`, `PortfolioRiskAggregatorTest` |
| A10 | `x.bid` / `x.ask` / `x.spread` evaluate Undefined on bar-sourced backtest data — spread-aware rules silently never fire in bar backtests (tick-sourced backtests carry real quotes) | OPEN (#389) — prefer tick data for spread-aware strategies |
| A12 | Quiet-symbol candle close: live closes an ended bar from the 1Hz heartbeat even with no next tick; backtest closes only on the next replayed tick (event time is its only clock) | INHERENT — affects the last bar before a session gap |
| A11 | Live-only operational effects: restart reconcile, OCO restore, poller-synthesized closes, gateway-outage suspensions, and the market-data gate including its broker-clock-skew check (#395/#396/#810 are live-only by design) | PARTIAL — replay now evaluates the runaway breaker with the configured live thresholds, reports every would-be trip, and can enforce them with `--enforce-live-breakers`. The remaining operational effects have no replay equivalent. The expired-before-submit GTD reject (#811) is wired in both modes but cannot fire under event time, where a fresh deadline is always in the future |
| A13 | Week-close entries: an entry signalled on the final pre-weekend bar closes via the live heartbeat (A12) when the feed is already stale and the venue shut, so the market-data gate rejects it; backtest closes the same bar on the venue's reopen tick and fills at the reopen price | MITIGATED (#888/#890) — the rejected fire re-arms and, if the condition still holds on the first post-reopen bar close, enters one bar later than backtest. The one-bar entry lag is INHERENT |
| A14 | Warmup seed grid: MT5 aggregates multi-hour history on the broker's day boundary, which put seeded H4/D1 bars on a shifted grid vs the epoch-aligned UTC bars live aggregation and backtest use | FIXED (#887) — multi-hour warmup history is fetched as H1 and rebuilt on the UTC grid; `CandleHub.seed` fail-closes on off-grid bars |
| A15 | Margin floor is a live pre-trade rule because replay has no venue margin-level feed | INHERENT — repeated missing reads fail closed for new exposure; risk-reducing exits remain allowed (`MarginFloorTest`) |
| A16 | Standalone live sessions default to fresh venue equity for drawdown and percent-of-equity sizing; replay uses model equity | DECLARED/CONFIGURABLE (#939) — `risk.live_equity_basis: modeled` pins live to `starting_balance + qkt realized + qkt unrealized`; `venue` remains the compatibility default (`LiveSessionBrokerEquityTest`) |
| A17 | Burst entries versus the runaway breaker: the breaker counts closing fills per strategy (default 10 in 600s), runs only in the live assembly, and halts the strategy PERSISTENTLY. A strategy that opens and closes more than that in ten minutes — any `TIMES N`, deep `STACK`, or N-action rule with N above the threshold — halts live while backtest completes the run | DECLARED/CONFIGURABLE — raise `max_round_trips_10m` to cover the strategy's busiest ten minutes, or `0` to disable. Pinned by `TimesEntryParityTest`; replay can enforce it with `--enforce-live-breakers` |
| A18 | Cross-stream entry before the target stream has closed a bar: a bracketed or pending order on a stream other than the one whose bar fired the rule prices itself from that stream's last CLOSED candle, so on the target's first bar there is none and the order is dropped with only a warn line — no rejection, no suppressed signal, and it is invisible in the trade record. Plain unbracketed orders are unaffected (they need no price to construct) | OPEN — declare `WARMUP` on every stream a rule can trade. Behaviour pinned by `TimesEntryParityTest`; the silent drop is an observability gap |
| A19 | Ladder rungs anchor to the SEED FILL, so ordinary market-seed entry drift shifts every rung with it. A ten-level 2-point ladder filled 4 legs live and 5 in replay purely because the market seed filled 2 points apart (…247 live, …249 replay), moving all ten rungs and bringing one more into range of the same low. The fill model itself is correct and parity-clean: a tick gapping through several rungs fills each at the gap price (a resting limit fills at its level or better), a rung the tape never reaches never fills, and tick, bar and live-paper agree all the way down a ten-rung ladder (`LimitLadderMechanicsTest`) | INHERENT — a ladder backtest predicts leg COUNT only as well as it predicts the seed fill. Size a ladder to survive one rung either way, or seed with a LIMIT instead of a market order to pin the anchor |
| A20 | Burst entry price dispersion: a backtest fills every leg of a burst at one price, while live places them serially (about four a second) and each leg fills at the market it arrives to. Measured on a 5-leg gold burst: replay filled all five at 4402.490, live filled 4402.490, 4402.490, 4402.301, 4402.174, 4402.174 — 316 points of dispersion in 1.4 seconds. A 30-leg EURUSD burst over 7 seconds drifted only 2 points, because the instrument barely moved. The dispersion scales with the instrument's volatility over the placement window, not with the leg count alone | INHERENT — a burst backtest's average entry is optimistic by roughly half the instrument's movement over the placement window. Strategies whose edge is a few points per leg must model this; `compare-stack-replay.sh` measures it per run |
| A21 | `max_open_positions` counted only FILLED positions, so a burst outran it live: every order is risk-checked before any of its fills return, so each saw an empty book and passed. A strategy capped at one symbol opened two live (5 gold + 10 EURUSD, zero rejections) while the backtest — where fills land between submissions — enforced the cap and rejected the second symbol. Same strategy, same config, opposite answers, with live being the unsafe side | FIXED — the rule now counts symbols held OR with a live entry order (`MaxStrategyOpenPositions`, `PositionProvider.pendingEntrySymbols`). Re-run live: the same scenario now fills 5 gold and rejects all 10 EURUSD with the backtest's reason. Single-symbol bursts are unaffected (30-leg EURUSD burst still fills 30/30) |
| A22 | `max_trades_per_day` counted entry FILLS, so a burst outran it live for the same reason A21 did: the whole burst is risk-checked before any of it fills, and every order reads the same pre-burst total. Measured live: a strategy capped at 60 executed 100 in one `TIMES 100` burst with zero rejections, while the backtest stopped it at 60 | FIXED — the cap now adds this strategy's live entry orders on the request's side. The side filter is load-bearing: an open position's protective stop and target rest on the OPPOSITE side and stay live until it closes, so counting both sides reported a phantom pending entry per filled position and halved the effective cap. Re-run live: 60 entries and 40 rejections, matching the backtest exactly, with replay parity passing (`PacerRulesTest`) |
| A23 | `book_risk.max_gross_exposure` is inert for a standalone strategy: `BookRiskController` is constructed only by `PortfolioDeployer`, so a single-strategy deployment ignores the block. Measured: a 100-leg EURUSD burst reached roughly 1.16x account capital in notional against a declared `max_gross_exposure: 0.60`, with no rejection | MITIGATED — the daemon now warns loudly at start when `book_risk` limits are configured, so the config can no longer imply a bound that is not applied. Enforcement for standalone deployments remains a design decision: `max_position_size` is per-symbol and `max_open_positions` caps distinct symbols, so neither bounds total notional. Deploy as a portfolio for book risk, or bound exposure inside the strategy |
| A24 | Live tick catch-up after an outage: the live source resumes from the newest broker timestamp it has emitted, and that watermark survives the out-of-session skip. An unclamped resume would request the entire gap in one round — the whole weekend on the Monday open — and replay ticks that are minutes or days stale into rules that would act on them at current prices. A backtest has no such gap | DECLARED — `maxCatchupMs` (60s default) bounds one round's window; ticks older than that after an outage are skipped, not replayed. The engine resumes at the current market rather than firing rules against prices that are gone (`Mt5TickRangePollingTest`) |
| A25 | Heartbeat-driven bar close versus tick arrival lag: live closes a quiet symbol's bar on a 1Hz wall-clock heartbeat, lagged by `candle_close_grace_ms`, while a backtest closes purely on event time. When a tick's arrival lag exceeds the grace, its bar has already closed and the tick is rejected as late — the bar then silently under-reports the venue. Measured live: the lag between a tick's broker stamp and its arrival ran a 100ms median and 192ms p99 unloaded, but a competing poller against the same single-threaded MT5 terminal pushed the p90 to 1458ms, and one bar recorded 18 of the venue's 111 ticks while every neighbouring bar matched exactly (93 dropped ticks, all in that bar) | FIXED — the grace default rose from 500ms to 2000ms, sized from that measured distribution, and a dropped late tick now logs a throttled warning instead of only incrementing a counter. Re-run under contention: 0 dropped ticks and 6/6 bars exact on volume and range. An active symbol closes tick-driven and never reaches the heartbeat path, so the grace costs close latency on quiet symbols only. Contention severity differed between the two runs, so this is not a controlled A/B: the grace is sized from the lag distribution, not from the drop count. A venue-side stall is the residual case no grace can cover: observed live, the MT5 terminal delivered no ticks for 63s while the venue recorded 418 and 334 ticks in those minutes, then backfilled the whole burst at once — 322 ticks arrived after their bars had closed and were dropped, every one of them inside a single millisecond. A closed bar cannot accept backfill without breaking determinism, so those bars under-report on any polling scheme; the predecessor endpoint never even fetched them. `maxCatchupMs` bounds the wasted work. Counting was itself wrong here: `droppedLateTicks` read only the default window aggregator and reported zero while a multi-stream strategy's hub slots were dropping, so the hub's slots are now summed in too (`CandleHubLateDropTest`) |
| A26 | Warmup history silently fell back to tick aggregation whenever the bar store missed ANY day of the requested range. Every range longer than a few days misses the days the venue did not trade, so in practice any multi-day (and therefore any higher-timeframe) warmup took the fallback. Against an ordinary tick store that is merely slow; against a golden-replay store it returns different numbers, because the materialized tick file also carries warmup ticks rehydrated from EVERY stream's timeframe and `BarTickFeed` puts a bar's whole volume on its close tick — so aggregating that file into one timeframe sums volume across all of them. Measured on a 4-stream gold capture: a 120-bar 1h warmup read 52,737 on a bar the venue recorded as 9,828 (= 9,828 twice plus the 4h bar's 33,081), while the same warmup shortened to 10 bars — short enough to stay inside days the store fully covered — read it correctly. Live was never affected: its warmup comes from `Mt5BarFetcher` bars per stream, and its `sma(volume,10)` matched the venue's own last ten hours exactly | FIXED — the CSV bar tier now skips days it does not have instead of disqualifying the range, matching the binary `--bars` tier which already tolerated gaps; only a range the store cannot serve at all still falls back (`LocalMarketSourceBarStoreTest`). Re-measured on the same capture: an 11-rule strategy across 1m/5m/1h/4h spanning a live 4h close now replays IDENTICALLY to live in both full-tick paper and full-tick mt5-sim. Bars-paper still differs on the live-window bar it rebuilds from 1m bars, which is the documented bar-synthesis tier, not this defect |
| A17 | Measured-usage ramp caps live order quantity during a configured post-deploy window; replay does not model deployment age | INHERENT — pinned by `MeasuredUsageTest` |
| A18 | Equity-curve window: replay used to sample warmup ticks and seeded pre-window bars onto the equity curve, so a replay's sample count (and Sharpe) depended on how much warmup history preceded `--from`; live never had those samples | FIXED — `EquityCurveCollector` floors samples at the replay window start (`windowStartMs`); trades, PnL, and drawdown are unaffected. Measured on the same fills: a 2023–2024 daily replay whose warmup reached back to 2021 carried 600 flat pre-window samples, so Sharpe read 0.66 instead of 0.92 and Sortino 1.06 instead of 1.49; golden replays and forge gate metrics (`sharpe` is qkt's `sharpeRatio`) recorded before this change are diluted in proportion to their warmup length (`EquityCurveCollectorTest`) |

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
| #620 | Portfolio live sessions share one `BookRiskController` (exposure limit rule + sizing scale), sampled on the portfolio candle cadence from real child legs; per-child `maxDailyLoss` became book-wide to match the backtest | `PortfolioRiskAggregatorTest`, `BacktestBookRiskTest` |
| #621 | `time_msc` fields are UTC epoch millis and are no longer offset-shifted; only naive datetime strings use the broker offset (one rule, one boundary) | `MT5ClientTest`, `Mt5BarFetcherTest` |
| #622 | A configured live session fails closed: no silent `PaperBroker` fallback for unrouted symbols | `LiveSessionBrokerCoverageTest` |
| #623 | Session-scoped indicators (`SessionRange`, `SessionVwap`, `AnchoredReturn`) refuse to latch partial initial windows — Undefined until the first complete window | `SessionRangeTest`, `SessionVwapTest`, `AnchoredReturnTest` |
| #624 | The tick-fills classifier expands the mid bar range by the slice's max half-spread, so levels crossed only by the executable quote resolve on real (side-aware) ticks | `OrderManagerIntrabarFillTest`, `BarResolvedFeedTest` |
| #625 | Backtest sims never fill an order cancelled earlier in the same tick | `PaperBrokerTest`, `MT5BrokerSimulatorTest` |
| #626 | Backtest and live honor each halt event's `cancelWorkingOrders`; cancellation is strategy-scoped and retains protective exits | `OrderManagerTest`, `BacktestRiskParityTest` |
| #627/#628 | Portfolio backtests accept always-run `CAPITAL`/`WEIGHT` and `RISK OF BOOK` topologies. They refuse conditional `WHEN..RUN` gates and portfolio `--bars`/`--bar-tf`/`--tick-fills` rather than silently changing topology | `BacktestCommandPortfolioTest`, `PortfolioDeployerBacktestParityTest` |
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

## 2026-07-31 parity verification (#948)

Every open issue in the parity epic was rechecked against `dev`. Stale reports are resolved by
existing shared-code evidence; confirmed residuals were fixed without introducing a second
execution, risk, accounting, or warmup pipeline.

| Issue | Verified result | Evidence |
| --- | --- | --- |
| #934 | Book-risk annualization uses the routed calendar. Conditional and always-run portfolios with book streams sample on portfolio candle close; streamless books use the documented heartbeat fallback | `PortfolioDeployerE2ETest`, `PortfolioRiskAggregatorTest` |
| #935 | Net costs stay in cash P&L, while win rate, profit factor, streaks, and Monte Carlo use exposure-reducing fills only. `financing.csv` exports swap cost and its signed P&L impact for CSV reconciliation | `ReportBuilderTest`, `BacktestReportWriterTest` |
| #936 | All five configured `MaxStrategy*` limits are built once by `StrategyRiskRuleFactory` and consumed by live and replay | `StrategyRiskRuleFactoryTest`, `BacktestRiskParityTest` |
| #937 | `daily_dd_basis` is threaded through global and per-strategy replay risk state | `BacktestRiskParityTest` |
| #938 | A portfolio-wide replay halt cancels entries and emits deterministic close orders for every child-owned leg on the breach tick, matching live flatten-before-halt | `BacktestRiskParityTest` |
| #939 | Standalone live equity remains venue-based by default and can be pinned to modeled parity with `risk.live_equity_basis: modeled` | `ConfigTest`, `LiveSessionBrokerEquityTest` |
| #940 | Margin floor and measured-usage ramp remain intentionally live-only and are declared in A15/A17; neither restriction is implied by a backtest | `MarginFloorTest`, `MeasuredUsageTest` |
| #941 | Live child/standalone feeds subscribe configured FX conversion symbols in addition to traded streams; conversion symbols do not become tradable streams | `StrategyHandleTest`, `PortfolioDeployerE2ETest` |
| #942 | Halt cancellation honors `cancelWorkingOrders`, scopes by strategy id, and retains protective exits | `OrderManagerTest`, `BacktestRiskParityTest` |
| #943 | A live deploy with a drawdown limit refuses a non-positive `starting_balance` instead of silently making static drawdown inert | `StrategyHandleTest` |
| #944 | MT5 `volume_max` is parsed and enforced in live preflight and MT5_SIM | `MT5ClientTest`, `MT5BrokerIntegrationTest`, `MT5BrokerSimulatorTest` |
| #945 | `qkt instruments verify` compares YAML contract size, volume bounds/step, point size, digits, and stops level against `/symbol_info`, exiting non-zero on drift | `InstrumentsCommandTest` |
| #946 | The catalog cadence/topology claims are corrected and a real `PortfolioDeployer` topology with `CAPITAL`, `WEIGHT`, `RISK OF BOOK`, and book allocation is compared with backtest output | `PortfolioDeployerBacktestParityTest` |
| #947 | CLI/store replay derives the same exact-stream warmup plan as live and seeds pre-window closed bars before DSL binding | `BacktestFromStoreTest`, `CompiledStrategyAutoWarmupTest` |
| #1071 | Backtest position model is venue-derived: CLI runs default to HEDGING (per-leg books, exits close their own leg) matching the retail-MT5 accounts, `--position-mode netting` for netted venues; `expected_margin_mode` asserts the live account matches; stale netting exits are retired and a reduce-only tripwire alerts on any exit that adds exposure (#1069/#1070) | `HedgingModeBacktestTest`, `StaleBracketExitAfterReversalTest`, `MT5AccountVerifierTest`, `OrderManagerReduceOnlyExitTest` |

## 2026-09-02 position ledger — one book, derived P&L (#1096, #1097, #1098)

| Row | Behavior | Proof |
| --- | --- | --- |
| Leg intent on the order | Every leaf order carries `LegIntent` (Open/Close/Net); the fill resolver reads it first, then the owned leg by ticket, then the venue default. Backtest and live book from the same intent, so a venue-detected close and a backtest close realize the same leg | `LegIntentResolverTest`, `LegIntentPlannerTest`, `TradingPipelineOcoEntryTest` |
| One leg per venue ticket | A re-report of an execution on an owned ticket (restart recovery) books only the venue's cumulative increment; a close naming a leg the book does not hold books nothing | `StrategyPositionTrackerReplayTest`, `Mt5CommentMatchTest` |
| Account book derived | The account position view is an index over the strategy ledger, never a second writer; account and strategy realized are the same number from one ledger | `LedgerAccountingCharacterizationTest`, report column pairs byte-identical on the fixture set |
| One accounting fold | Every realized amount (execution, financing, boot reconcile) is one `FillAccountedEvent` folded once into both accumulators, the daily tracker, trade history, pacer and halts | `LedgerAccountingCharacterizationTest`, `TradingPipelineVenueCostsTest` |
| Flatten leg by leg | Halt-flatten closes each ledger leg with `Close(legId, ticket)` on every venue; no account-net path | `LiveSessionFlatten*` suites, `MT5Broker` close-by-ticket |
| Vanished-ticket retirement | A ledger leg whose venue ticket is gone from two consecutive clean snapshots is closed from deal history through the ordinary fill path | `MT5PositionPollerCloseTest` |

### Residual divergences (known, accepted, tracked)

| Residual | Behavior | Tracking |
| --- | --- | --- |
| CROSSES cold start | Warmup replay does not evaluate rule expressions, so a `CROSSES` node's prev-state is unset on the first post-deploy bar — it returns Undefined (rule does not fire) for exactly one bar. Fail-safe: a missed signal, never a wrong one | inherent to replay-without-firing |
| Freeze-level in backtest | Live `modifyPosition` rejects SL/TP moves inside `SYMBOL_TRADE_FREEZE_LEVEL` (surfaced + logged); the mt5-sim does not model freeze-level, so a backtest trail always tightens where live may be refused | #638 residual |
| Tick-fills synthetic marks | A symbol with an open position but no live orders resolves SYNTHETIC under `--tick-fills`, so its intrabar equity marks come from synthetic points (fills are exact; drawdown sampling is approximate) | #642 residual |
| Venue partials on fallback exits | When a venue partial fills a fallback (non-attached) bracket, exits are sized to the first fill's volume; a later remainder fill has no engine exit | follow-up if partial-fill venues go live |
| Already-crossed native stops | The fill decision is aligned: MT5 converts a STOP already through the latest ask/bid to MARKET (StopLimit to LIMIT), matching the engine-held path. Backtest fills on its crossing tick, while live fills after dispatch at the venue's later executable price, so latency/slippage can still change the fill price | #815; decision pinned by `AlreadyCrossedStopParityTest`, live wire/protection by `MT5BrokerIntegrationTest` |

With the same input event stream, calendar, instrument metadata, cost and risk configuration,
modeled live-equity basis, and venue-shaped broker model, the backtest is faithful for live
dollar/trade replication within the declared bounds above. This is a shared-runtime claim, not a
claim that `PaperBroker` or historical ticks predict venue latency, retcodes, partial fills, feed
sampling, or other explicitly listed live-only effects. Use `mt5-sim` and retained venue evidence
when the result will be cited as MT5-shaped rather than research-tier output.

## File pointers

- Pipeline contract — `docs/phases/phase-4-backtest.md` (the "Same pipeline, live execution" section)
- Pipeline parity test — `src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt`
- Live-pipeline construction — `src/main/kotlin/com/qkt/app/LiveSession.kt` (`broker = buildBroker(paperBroker, ...)`)
- Backtest-pipeline construction — `src/main/kotlin/com/qkt/backtest/Backtest.kt:fromStore`
- `PaperBroker` fills — `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- `MT5Broker` quantization (v0.26.3 + v0.26.4) — `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` (`quantizeForPlacement`)
- Strategy-port parity (separate concern) — `qkt-prod/docs/PARITY.md`
- Data-source parity (the prices, separate concern) — `docs/parity/parity-dukascopy-vs-mt5-xauusd.md` (dukascopy is the backtest source); `docs/parity/parity-bars-xauusd-m5.md`, `docs/parity/parity-ticks-xauusd.md` (TV vendor cross-check)
