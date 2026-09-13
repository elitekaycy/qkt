# Backtest ↔ Live parity — quant trust audit (2026-07-31)

## Provenance

Framing question: **Can a `qkt` backtest — with risk, sizing, portfolio, and costs
configured as they run live — be trusted to produce the same trades and the same CSV
that live trading would produce, and where it can't, is every divergence declared and
bounded?**

Method: 13 parity dimensions × the backtest/live construction paths, run as two
adversarial-verification workflows plus driver hand-verification against source.

- Round 1 (4 dimensions completed before a credit interruption): mode-wiring, risk,
  sizing, portfolio → 42 raw findings deduped to 21 canonical.
- Round 2 (9 remaining dimensions + adversarial verification): fill-model,
  order-lifecycle, costs, venue-behavior, data-fidelity, time-session, look-ahead,
  reporting-truth, parity-enforcement → 40 raw findings, verified by 2 independent
  refuters per High/Critical, 1 per Medium (Opus 4.8, 42 agents, 0 errors).
- The 21 canonical findings from round 1 were hand-verified by the driver against
  actual source (both the backtest side and the live side of each).

Counts: **~28 confirmed/plausible divergences survive; 12 findings refuted under
verification.** Every High/Critical below was reproduced from the cited lines in this
working tree (`fix/861-risk-env-vars`), not from any prior audit doc.

Scope note: this audits the *code as configured to match live*. It is not a claim that
the shipped 14-sleeve book is mis-trading today — several findings are latent (trigger
only with a specific config, instrument, or equity scale). Each says when it bites.

---

## Verdict

**Backtest numbers are trustworthy for the shape of an edge (rule firing, ordering of
strategies by Sharpe/drawdown, win/loss direction) — but NOT yet trustworthy as a
faithful dollar-and-trade replica of live once you turn on the things that make a
backtest realistic (costs, per-strategy risk caps, portfolio book-risk, equity sizing).**

The shared `TradingPipeline` core genuinely is byte-identical between modes — that part
of the parity claim holds and is CI-pinned (`BacktestLiveParityTest`). The divergences
live in three places the shared-core claim does not cover:

1. **Construction asymmetry** — five per-strategy risk rules, the daily-drawdown basis,
   the margin floor, and the measured-usage window are wired in `LiveSession` but never
   in the backtest. A strategy that halts (or gets rejected) live keeps trading in its
   backtest, so the backtest CSV shows trades live would never place.
2. **Portfolio book-risk** — the live `BookRiskController` is built with a different
   annualization and sampled on a 1-second wall clock vs the backtest's per-bar close;
   on any book with `allocation`/`vol-target` configured, live order sizes silently
   differ from backtested sizes. This is the one **Critical**.
3. **Reporting truth** — once costs are on, entry fills carry `realized = −commission`
   and get counted as *losing trades*, so win rate, profit factor, average loss and the
   Monte-Carlo drawdown distribution are all deflated. The headline stats an operator
   reads to decide "go live?" are corrupted precisely in the cost-realistic run.

None of these appear in `docs/parity/backtest-vs-live.md`, whose A1 row claims the
backtest "builds the same config-driven halt set" and whose completeness the operator is
told to trust. **The catalog is the credible artifact here, and its gaps are the finding.**

Recommendation: treat current backtest PnL/win-rate/DD as an **estimate with a known
optimistic bias** until the P0/P1 items below close. The single-strategy, costs-off,
paper-broker path is faithful; the portfolio + costs + per-strategy-risk path is not.

---

## Top priorities (ranked)

| # | Severity | Finding | Why it matters first |
|---|---|---|---|
| P0 | Critical | **Book-risk controller annualization + sampling cadence differ** (`PortfolioDeployer.kt:112`, `ReplayEngine.kt:260`) | Any portfolio with `book_risk.allocation`/vol-target sizes every child differently live vs backtest — silently. Directly breaks the 14-sleeve book's sizing parity. |
| P1 | High | **Reporting: entry fills counted as losing trades** (`ReportBuilder.kt:87`, `WinLossStats.kt:41`) | Corrupts win rate, profit factor, avg loss, Monte-Carlo — the exact numbers you gate go-live on — whenever costs are configured. |
| P1 | High | **Five per-strategy risk caps are live-only** (`BacktestContext.kt:152`) | `MaxStrategyDailyLoss/PositionSize/OpenPositions/Drawdown/DailyDrawdown` halt/reject live but not in backtest → backtest shows trades live blocks. |
| P1 | High | **Daily-drawdown basis not threaded to backtest** (`ReplayEngine.kt:247`) | `daily_dd_basis: equity` anchors the halt at day-start equity live, day-start balance in backtest → the daily-DD halt fires at different points. |
| P1 | High | **Book-drawdown breach: live flattens, backtest rides** (`PortfolioRiskAggregator.kt:108`) | Post-breach equity path, max-DD, and the trade CSV's exit rows do not represent live after any book halt. |
| P1 | High | **`% OF EQUITY` sizing basis differs** (`StrategyPnL.kt:120`) | Live sizes off polled venue equity (incl. swap, other positions, deposits); backtest off modeled equity → every subsequent order size drifts. |
| P1 | High | **Margin floor + measured-usage are live-only and undeclared** (`LiveSession.kt:1119/1128`) | Live rejects entries below 200% margin and mini-sizes for 24h after every deploy; backtest fills them → phantom backtested trades. |
| P1 | High | **FX conversion pairs not subscribed live** (`StrategyHandle.kt:254` vs `BacktestContext.kt:361`) | Non-account-currency instruments needing a configured pair: backtest trades, live refuses to size (or books native-as-account PnL). Latent on the current USD-majors book; lethal on cross pairs. |

## Quick wins (high value / low effort)

- Add `dailyDdBasis` to `HaltConfig` → `Backtest` → `ReplayEngine` (one field, one arg).
- Filter `TradeRecord`s to closing fills (`reducedExposure`) before `ReportBuilder`
  metrics, or exclude `realized == −cost` opening fills — fixes the win-rate/PF bug.
- Correct catalog row A7 ("~50ms" → the real 1000 ms default) and add declared rows for
  margin-floor, measured-usage, flatten-on-breach, and controller cadence.
- Fail-loud (or WARN) when `max_drawdown_pct` is set with `starting_balance <= 0`.

---

## Findings register

Legend: `[V]` verified from source (driver or 2-vote adversarial), `[P]` plausible/real
but bounded, severity in brackets. File:line are in this working tree.

### Risk-engine parity

- `ReplayEngine.kt:247` **[V][High]** — backtest builds `RiskState(pnl, strategyPnL,
  clock, bus, startingBalance)`; the 6th param `dailyDdBasis` defaults to `BALANCE`
  (`RiskState.kt:35`). Live passes `cfg.dailyDdBasis` (`LiveSession.kt:991`).
  `HaltConfig` (`BacktestContext.kt:53`) has no field to carry it. → `daily_dd_basis:
  equity` halts at different loss levels per mode. Fix: thread the basis through.
- `BacktestContext.kt:152-181` **[V][High]** — the per-strategy loop wires only
  `MaxTradesPerDay`, `CooldownAfterLoss`, `LossStreakHalt`. Live also wires
  `MaxStrategyDailyLoss`, `MaxStrategyPositionSize`, `MaxStrategyOpenPositions`,
  `MaxStrategyDrawdown`, `MaxStrategyDailyDrawdown` (`LiveSession.kt:1013-1074`). Grep
  confirms the `MaxStrategy*` rules exist in no backtest path. → a strategy that halts or
  gets rejected live trades past that point in backtest. Fix: wire the five rules in
  `BacktestContext`; pin with a cross-mode wiring test.
- `LiveSession.kt:1119-1127` **[V][High]** — `MarginFloor(broker, marginFloorPct)`
  (default 200%) is live-only; no margin model exists in `ReplayEngine`/`Backtest`, and
  `buildPortfolio` never plumbs `cfg.marginFloorPct`. Not in the catalog (A11 omits it).
  → on a loaded multi-child book, backtest fills entries live rejects for low margin.
- `LiveSession.kt:1128-1146` **[V][High]** — `MeasuredUsage` (default 24 h / 0.01 lots,
  ON) rejects above-min new-exposure orders for 24 h after every deploy/restart. No
  backtest counterpart; only a `log.warn` as declaration. → day-one-after-deploy live
  trades diverge from backtest, every restart.
- `DrawdownTracker.kt:39-46` **[V][Medium]** — `globalStaticDrawdown` returns `ZERO`
  when `initialBalance <= 0`. Live `starting_balance` defaults to `0` (Config), backtest
  CLI defaults to `10000` (`BacktestContext.kt:274`). No validation ties
  `max_drawdown_pct` to a positive balance. → an operator who sets a drawdown cap but
  forgets `starting_balance` gets a live daemon whose static total-DD halt never fires,
  while its backtest halts at the 10k basis (backtest under-reports live tail risk).
- `ReplayEngine.kt:409-414` **[V][Medium]** — the backtest `RiskEvent.Halted`
  subscription loops **every** `tradedSymbol` and cancels pendings, ignoring
  `ev.strategyId` and `ev.cancelWorkingOrders`. Live children run in separate
  `LiveSession`/`OrderManager`s, so a per-strategy halt cancels only that child. → in a
  portfolio backtest, one child's `LossStreakHalt` cancels other children's resting
  entries and protective exits. Fix: honor `ev.strategyId`/`cancelWorkingOrders`.

### Portfolio / book-risk parity

- `PortfolioDeployer.kt:112-118` **[V][Critical]** — live builds
  `BookRiskController(bookRiskConfig, bookCapital)` (2-arg → annualization defaults 252)
  and samples it from the 1 Hz supervisor heartbeat (`PortfolioRiskAggregator.evaluate`
  → `onSample`, `Thread.sleep(1000)`). Backtest builds
  `BookRiskController(it, capital, calendar.tradingPeriodsPerYear(candleWindow))`
  (`ReplayEngine.kt:260-266`) sampled at candle close. → returns/covariance fold 1-second
  deltas live vs per-bar deltas in backtest, `volTarget` saturates at `maxLeverage`, and
  "rebalance every N bars" becomes every N seconds. Every child's live size differs from
  the backtested size, silently. Fix: pass the same annualization and sample the live
  controller on child bar closes (or make the controller time-aware).
- `PortfolioRiskAggregator.kt:101-118` **[V][High]** — book-DD/daily-loss breach
  flattens every child (`c.flatten()`) then halts. Backtest breach only cancels pendings
  (`ReplayEngine.kt:412-414`); open positions ride. → post-breach PnL/max-DD/exit rows
  don't match live. Fix: flatten open positions on book-halt in backtest, or declare it.
- `ReplayEngine.kt:262-266` **[P][Medium]** — backtest controller capital falls back to
  `startingBalance` when `book_risk.capital` unset; live *requires* a capital
  (`PortfolioDeployer.kt:106`). When `book_risk.capital != CAPITAL`, live DD-ladder
  equity anchors to one, backtest snapshot to the other → de-risk engages at different
  drawdowns. Fix: mirror the live requirement; use one capital constant both sides.
- `PortfolioDeployer.kt:398-476` **[P][Medium]** — `createChild` passes neither
  `initialBalance` nor `calendar` to `LiveSession`. An unweighted portfolio (no
  `CAPITAL`/`WEIGHT`) gives each child empty `startingBalances` → equity basis falls back
  to polled venue equity (or ~0 in paper), while backtest gives each child the full
  session `startingBalance`. → `% OF EQUITY` children size off different bases; live
  children also all use the default `fxDefault()` calendar.
- `ReplayEngine.kt:343-351` / `PortfolioBacktestLiveParityTest` **[P][Medium]** — the
  only cross-mode portfolio parity test runs one multi-strategy `LiveSession`, **not**
  the `PortfolioDeployer` N-session + supervisor + aggregator topology, with no
  `bookCapital`/`bookRiskConfig`. The KDoc "sizing stays parity-exact" and catalog row
  #620 are unproven against the real live shape — which is exactly why the controller
  cadence divergence above shipped. Fix: add a `PortfolioDeployer`-vs-`Backtest` parity
  test with CAPITAL/WEIGHT, OF BOOK sizing, and `book_risk` configured.
  (Note: the sub-claim that the OF-BOOK *balance formula* diverges was **refuted** — both
  modes fold `strategyPnL.realizedFor(id)`; see dismissals.)

### Sizing parity

- `StrategyPnL.kt:120-124` / `LiveSession.kt:929-941` **[V][High]** — live standalone
  sessions feed polled venue account equity into `equityFor()` (`brokerEquity` supplier);
  backtest `StrategyPnL` has no such supplier → uses `startingBalance + realized +
  unrealized`. → `% OF EQUITY`/`RISK` sizing and equity-based rules use different bases;
  they diverge whenever the real account carries swap accrual, other positions, or
  deposits. Not declared; no cross-mode sizing-basis test.
- `StrategyHandle.kt:254` vs `BacktestContext.kt:361,669` **[V][High]** — backtest adds
  `accountingConfig.normalizedSymbols` (FX conversion pairs) to the replay feed; live
  subscribes only `ast.streams`. Grep confirms `normalizedSymbols` appears in no live
  path. → for an instrument quoted in a non-account currency needing a configured pair,
  `Accounting.quoteToAccountRate` "refuses to size" live (`Accounting.kt:216`) while
  backtest trades; open-position PnL under the default `WARN` policy can book native
  amounts as account currency. Latent on today's USD-quoted book; lethal on cross pairs.
- `LiveSession.kt:580-590` **[P][Medium]** — live reads `contractSize`/`volumeStep`/
  `volumeMin` from broker `/symbol_info`; backtest reads `instruments.yaml` over
  `StandardInstrumentRegistry`. No tool/deploy-check/test diffs the two. → a
  disagreement (the copper/XTIUSD/XAGUSD broker quirks already seen on IC Markets) makes
  every backtest quantity a silent constant multiple of the live quantity. Fix: a `qkt
  instruments verify` deploy-time diff against `/symbol_info`.
- `Backtest.kt:251-280` **[P][Medium]** — `fromStore` never forwards a `warmupSpec` to
  `fromSource` (defaults `WarmupSpec.None`), so the CLI backtest runs no pre-window
  warmup (`ReplayEngine.kt:422` skips `IndicatorWarmer`); the DSL warmup gate is credited
  by consuming the first N in-window bars. Live seeds indicators from pre-deploy history
  and trades from the start. → backtest emits no signals for the first N bars of the
  window that live would trade. (Catalog A2 is marked FIXED for live; the backtest side
  of the asymmetry has no residual row.)

### Reporting truth

- `ReportBuilder.kt:87-100` **[V][High]** — `winRate`, `profitFactor`, `avgLoss`,
  `largestLoss`, and the Monte-Carlo input all run over `trades.map{realized}` = every
  fill. The `signum() != 0` filter drops only zero-PnL entries; once costs are configured
  each opening fill has `realized = −commission` (`TradingPipeline.kt:449`;
  `TradingPipelineVenueCostsTest.kt:91` pins an entry fill at `-0.5`) and counts as a
  **losing trade**. → a genuinely 100%-win strategy reports ~50% win rate, a finite
  profit factor where it should be undefined, a phantom `largestLoss = one commission`,
  and a polluted MC drawdown. Invisible at zero cost; appears exactly when you make the
  backtest cost-realistic. Fix: compute win/loss stats over closing fills only.
- `WinLossStats.kt:39-48` **[V][Medium]** — `maxConsecutiveLosses` walks the raw fill
  list and resets on any non-negative value; the same entry-fill-as-loss records above
  corrupt the streak. Same fix.
- `ReplayEngine.kt:504` **[V][Low]** — headline `finalRealized = pnl.realizedTotal()`
  includes swap financing (`applyFinancing` writes to `pnl`); the summed `trades.csv`
  `realized` column does not. → the report's net PnL and the trade-list sum don't
  reconcile by the swap total. Fix: reconcile, or add swap as an explicit CSV column.
- `EquityCurveCollector.kt:40-46` **[P][Low]** — in the default `CANDLE_CLOSE` cadence
  (all candle strategies and every `--bars` run) equity is sampled only at bar close, so
  reported `maxDrawdown`/Sharpe come from the decimated curve — an intrabar trough
  between closes is invisible. → reported max-DD can understate the true path.

### Costs / venue behavior

- `MT5BrokerSimulator.kt:437-440` **[V][Medium]** — `quantizeVolume` rounds down to
  `volumeStep` and rejects below `volumeMin` but never caps at `InstrumentMeta.volumeMax`
  (`instruments.yaml` XAUUSD=200). Live `MT5Broker` also omits a max
  (`VenueRules`/`prepareForPlacement` check only min; `MT5Broker.kt:2104` hardcodes
  `volumeMax=null`) → sends oversize to the venue, which rejects (retcode 10014). → a
  >200-lot order books a phantom fill+PnL in backtest; live takes no trade. Bites only at
  high equity/compounding scale — unreachable for current 1%-risk sleeves, but a latent
  tail-distortion. Fix: enforce `volumeMax` in the sim and carry it in live `VenueRules`.
- `docs/parity/backtest-vs-live.md:122` **[V][Medium]** — row A7 states live tick
  sampling as "~50 ms"; the real default is `tickPollIntervalMs = 1000`
  (`MT5BrokerProfile.kt:41`, passed at `Mt5MarketSource.kt:58`). The cross-referenced
  data-parity report shows ~1.5 ticks/s (≈672 ms gap). → the catalog understates, by
  ~20×, how far a full-tick backtest's sub-second wicks (breakout entries, intrabar
  stop/limit fills) drift from what live's 1 Hz poll ever sampled — in the
  confidence-inflating direction. Fix: correct the figure; note it affects candle
  HIGH/LOW and thus entry-signal firing, not only engine-held trails.
- `ExecutionSimulation.kt:146-151` **[P][Low]** — the default `mt5-sim` preset maps to
  `MT5_BASIC` with `enforceStopsLevel=false`; only `mt5-realistic` enforces stop-distance
  and latency. → even the "faithful" sim skips venue stop-distance rejects unless you opt
  up. Declared-ish (catalog row 8 residuals) but the default is the permissive tier.
- `MT5BrokerSimulator.kt:173-191` **[P][Low]** — sim `onTick` fills any crossed trigger
  with no session/market-closed gate (the `TradingCalendar` gates strategy time, not the
  sim broker). → backtest can fill on a tick inside a window live wouldn't trade. Bounded
  by the calendar injection (catalog row 13); real at session edges.
- `PaperBroker.kt:268` / `MT5BrokerSimulator` **[P][Low]** — default backtest brokers
  never partial-fill (only the opt-in STRESS preset does). Live venues can. → a large
  order fills whole in backtest, partially live. Declared-adjacent; bounded.
- `MT5Broker.kt:2099` **[P][Low]** — live `InstrumentMeta` populates size/price fields
  but not `commissionPerLot`/`swapLong/ShortPoints`; live costs come from venue-reported
  deal costs while backtest models them from `instruments.yaml`. → cost totals can drift
  (catalog A5 declares the rate-source drift; the point stands as an input-data gap).
- `ReplayEngine.kt:441-448` **[P][Low]** — backtest accrues swap into halt inputs at
  each UTC rollover (`applyFinancing` → `evaluateHaltRules`); live books venue swap only
  at deal close. → near-threshold daily-loss/DD configs can halt on different days.
  Catalog A5 declares the rate source, not this timing. Fix: extend A5.

### Fill model / look-ahead / order lifecycle

- `PaperBroker.kt:166,221-238` **[P→Low]** — the **default** `PAPER` broker fills market
  and triggered orders at `tick.price` (the dukascopy mid), while `checkTrigger` is
  side-aware; live crosses the spread. → the default backtest is ~one spread/round-trip
  optimistic on fill price. Declared in catalog rows 4-6, but the *safe* broker
  (`mt5-sim`) is not the default (`BacktestContext.kt:660`). One verifier held this at
  Low because the optimism is declared and `--broker mt5-sim` closes it; still worth a
  report banner when PAPER runs against a real instrument registry.
- Look-ahead: **clean.** Verifiers confirmed indicator→rule→trigger→fill→risk ordering
  within a tick is single-pass, risk distance is resolved at decision time from the
  expected entry (no future data), and edge-triggering/re-arm is identical both modes.
  `BarTickFeed` O→L→H→C synthesis (short-side optimism) is the already-declared A6 and is
  RESOLVED by `--bars --tick-fills`.
- Order lifecycle: scale-out `IfTouched` legs are engine-managed (not venue-native) in
  both modes **[P][Low]**; GTD/expiry, bracket/OCO cancel, and trailing all shared. No
  High-severity lifecycle divergence survived.

### Coverage gap (from the completeness critic)

- **DSL `SCHEDULE` firing-time parity was not examined** — the `time-session` finder
  returned nothing. `ScheduleRunner` advances only from tick time in backtest
  (`TradingPipeline.kt:636`) but also from the 1 Hz live heartbeat
  (`LiveSession.kt:1685`); the catch-up loop fires all missed triggers at once at the
  next tick's price. This is the declared A8 INHERENT divergence, but **no test in
  `src/test/kotlin/com/qkt/parity/` touches `SCHEDULE`**, and `--bars` (4 synth
  ticks/bar) makes the granularity gap worse. A `SCHEDULE`-driven strategy with a
  multi-minute tick gap straddling the fire time, and a DST-boundary case, should be
  pinned. Warmup (above) is the other time-session concern and is covered.

---

## Dismissed findings (refuted under verification)

Kept as evidence — a register that shows what was refuted is more credible than one that
only lists hits.

- **"OF BOOK balance formula diverges between modes"** — REFUTED. Both modes fold
  `strategyPnL.realizedFor(id)` (backtest `ReplayEngine.kt:346`; live
  `PortfolioBookBalance` binds `pnlSnapshot(id).realized` = the same method,
  `PortfolioDeployer.kt:142`). The finder inverted the cited comment.
- **"Cost-netting could diverge between modes"** — REFUTED. Venue-cost netting into
  realized PnL and halt inputs exists in exactly one place, the shared
  `TradingPipeline` `OrderFilled` handler (`:404-496`), run identically in both modes.
- **"contractSize PnL untested cross-mode / could diverge"** — REFUTED. The
  `contractSize` multiply lives in the one shared pipeline handler; a per-mode divergence
  is architecturally impossible.
- **"Commission/swap only modeled on opt-in"** — REFUTED. `PerLotCommission` and
  `SwapFinancingBook` are wired unconditionally for every backtest (`ReplayEngine.kt:141`);
  they charge zero only when `instruments.yaml` sets zero rates.
- **"BarTickFeed O→L→H→C is an undeclared look-ahead"** — REFUTED. It is the declared A6,
  and resolved by `--bars --tick-fills` (`TickResolvedParityTest`).
- **"Determinism guard allowlists whole files, so wall-clock reads could leak into
  decisions"** — REFUTED. The current allowlisted reads (`LiveSession`, `TradingPipeline`
  latency/shutdown) are benign non-decision-path reads.
- **"A13 one-bar-lag / A6-A8-A12 bounds unpinned"** — REFUTED. The re-arm bound is pinned
  by `RuleSuppressedFireTest`; the mechanisms are enforced even if not named per row.
- **"trades.csv four realized columns are inconsistent"** — REFUTED. The duplication is
  declared and the columns carry distinct gross/net/FX values by design.
- **"mt5-sim fillMarket has no deviation bound"** — REFUTED as material: it's the
  declared latency/slippage divergence; live sets `deviation=20`.
- **"MT5 engine GTD sweep double-handles"** — REFUTED. `supportsNativeGtd=true`, so the
  engine sweep path is inert live.
- **"BacktestLiveParityTest doesn't exercise contractSize"** — REFUTED as material (same
  architectural-single-path reason), though it corroborates the real test-coverage gap
  tracked under the portfolio-parity-test finding.

---

## Tracked work

Umbrella epic: **#948**. Confirmed findings filed as:

| Issue | Sev | Finding |
|---|---|---|
| #934 | P0 | Book-risk controller annualization + sampling cadence differ |
| #935 | P1 | Report win-rate/PF/Monte-Carlo count entry fills as losses under costs |
| #936 | P1 | Five per-strategy `MaxStrategy*` caps live-only |
| #937 | P1 | `daily_dd_basis` not threaded to backtest |
| #938 | P1 | Book-drawdown breach: live flattens, backtest rides |
| #939 | P1 | `% OF EQUITY` sizing basis (venue vs modeled equity) |
| #940 | P1 | Margin-floor + measured-usage live-only, undeclared |
| #941 | P1 | FX conversion pairs not subscribed live |
| #942 | P2 | Backtest halt cancels all strategies' pendings |
| #943 | P2 | Static-DD zero trap (`starting_balance <= 0`) |
| #944 | P2 | `volumeMax` unenforced (phantom oversize fill) |
| #945 | P2 | No `instruments.yaml` vs venue `/symbol_info` reconciliation |
| #946 | P2 | No PortfolioDeployer-vs-Backtest parity test; catalog stale (A7, #627/#628) |
| #947 | P2 | CLI backtest does no pre-window warmup |

The remaining Low/declared items in the register are documented here but not filed
individually; fold them into the catalog-correction work under #946.

## The framing answer, precisely

- **Rule firing / signal counts / strategy ranking:** trustworthy. Shared pipeline,
  CI-pinned.
- **Single strategy, costs off, paper broker:** byte-identical to live-paper. Trustworthy.
- **Dollar PnL with costs on:** biased — win rate/PF/avgLoss/MC are corrupted by the
  entry-fill-as-loss bug (P1); fill price is spread-optimistic on the default broker.
- **Per-strategy risk caps:** NOT reproduced in backtest — backtest over-trades vs live.
- **Portfolio book-risk sizing:** NOT reproduced — different annualization/cadence sizes
  every child differently (P0).
- **Equity-based sizing on a live/shared/long-running account:** diverges — different
  equity basis.
- **After any risk halt or book breach:** the backtest trade tape does not represent live
  (cancel-only vs flatten; wrong halt point).

Until P0 + the P1 set close and the catalog declares the rest, the honest statement on a
backtest report is: *"faithful for edge shape and ranking; optimistic and incompletely
risk-gated for live dollar/trade replication."*
