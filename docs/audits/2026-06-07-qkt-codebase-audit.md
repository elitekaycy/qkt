# qkt Codebase Audit — 2026-06-07

> Multi-agent audit: 13 code slices × 4 dimensions (production-readiness, dead-code/duplication,
> hot-path performance, Kotlin/quant/architecture standards). 48 agents, ~2.7M tokens.
> **92 findings raised → 33 high/critical confirmed by adversarial verification + 58 medium/low smells.**
> Findings marked `[V]` were confirmed by a second agent that read the real code and tried to refute them.
>
> A teaching-quality walkthrough (plain explanation + before/after example + fix per finding) follows in
> `2026-06-07-qkt-codebase-audit-explained.md`.

## Verdict (the four questions)

1. **Production-ready?** No — not for live multi-venue as-is. The engine assumes a single-threaded
   bus/OrderManager, but live mode violates that from ≥5 foreign threads (MT5 poller, Bybit WS/reconciler,
   schedule heartbeat, HTTP control plane), with confirmed paths to order/position/PnL corruption that
   backtests can't catch. Plus the GTD-wire bug, CompositeBroker no-op delegation, and broken restart
   recovery are real-money correctness defects. The deterministic backtest core is sound; the live
   integration layer is unsafe.
2. **Free of dead/duplicate code?** No, but shallow. ~20 dead surfaces + a few dangerous duplications
   (two position-averaging implementations, three copies of the PnL formula). Systemic smell: `else -> null`
   / `else -> error` catch-alls over sealed types that defeat compiler exhaustiveness.
3. **As fast as it could be?** Mostly yes on algorithms; the gaps are allocation churn, not complexity
   blowups. Two real algorithmic items: RollingHigh/Low O(period) rescans (want monotonic deque), and the
   unbounded TICK-cadence equity curve (O(N²)/OOM risk on long runs).
4. **Standards?** Largely yes, two material breaches: latch wall-clock and MT5 tick-timestamp break the
   determinism/backtest==live invariant for real strategies. The `else->`-over-sealed pattern, lone
   ConcurrentHashMap, and DECIMAL128-in-Bollinger are local violations. Standards are right; enforcement
   has leaked.

## Top priorities before production (ranked)

1. **Live-path thread safety** (7 verified findings). Fix once, structurally: a single-consumer inbound
   queue drained on `qkt-live-engine` that all off-thread events (poller fills, WS frames, heartbeat ticks,
   flatten/halt) post into. Collapses 7 findings into one design change. Decide before patching brokers.
2. **GTD dropped on the MT5 wire** (`MT5Client.encodeOrder`) — pendings rest GTC-forever, fill into
   unintended positions. ~3-line money bug.
3. **`CompositeBroker` no-op delegation** — reconciliation blind, OCO restart recovery dead, venue stops
   never mirrored; silent on every multi-venue deploy.
4. **Restart recovery** (`brokerTicket` not persisted; surviving STACK/INDEPENDENT legs dropped) —
   reintroduces the hedge-accumulation bug class and leaves unmanaged live exposure.
5. **`/status` PnL = 0** (`StrategyHandleJson`) — the `qkt observe` go/no-go gate inverts on real PnL.
6. **Latch wall-clock nondeterminism** (`ActionCompiler` defaults `SystemClock()`).

## Quick wins (high value / low effort)

MT5 GTD wire (+test) · latch clock via `ctx.clock` · thread real PnL into `/status` · `synchronized(regs)`
on ScheduleRunner · FOR EACH `opts.copy(...)` (fixes silent STACK drop) · `Mt5TickFeedSource` stamp
`brokerTimeMs` · Parser strategy try/catch · dead-code sweep (~300+ LOC, restores sealed exhaustiveness) ·
bound the leaks (trades ring, pending-intent maps, `seenExecIds`, `recentlyFilledTickets`).

---

## Confirmed findings by dimension

### Production-readiness

**Critical**
- `LiveSession.kt:614-624` [V] — `qkt-schedule-heartbeat` thread drives `scheduleRunner.tick`→`emit`→`bus.publish`/`orderManager.submit` concurrently with the engine thread on a non-thread-safe bus/maps; can corrupt the order book, double-fire schedules, throw CME. Backtest never starts it (parity break). Fix: enqueue the heartbeat onto the engine's single-consumer feed queue so `tick()` only runs on `qkt-live-engine`.
- `LiveSession.kt:619` / `ScheduleRunner.kt:125-145` [V] — SCHEDULE emit/order-submit on the heartbeat thread races the engine on unsynchronized `regs`/`orders`/`siblings`/`trailingHwm`. Same fix: serialize onto the engine thread.
- `MT5Client.kt:323-347` [V] — `encodeOrder` omits `expiration`/`typeTime`; every GTD pending order is placed GTC-forever on MT5 (`supportsNativeGtd=true` suppresses the local sweep) → fills hours/days later, silent backtest≠live. Fix: serialize `expiration` (+`typeTime=TIME_SPECIFIED`) mirroring `encodeModification`; add a wire-JSON test.
- `CompositeBroker.kt:21-94` [V] — does not override `getOpenPositions`/`recoverPendingOrders`/`modifyPosition`/`supportsNativeGtd`; falls through to no-op defaults though the leaves implement them. Reconciliation sees no positions, OCO restart recovery dead (defeats a8523df), venue SL/TP never mirrored — silent on every live deploy. Fix: fan-out delegate to leaves; per-symbol `supportsNativeGtd`; ticket→broker routing for `modifyPosition`.

**High**
- `OrderManager.kt:62-100,1315-1367` [V] — tick thread + MT5 poller thread + heartbeat mutate `orders`/`liveOrderIds`/`gcQueue`/`siblings` with no lock; `liveOrderIds.map{orders[it] ?: error("live order index desync")}` throws or corrupts state mid-session, live-only. Fix: funnel poller publishes through a single-consumer queue drained on the engine thread.
- `StrategyPositionTracker.kt:339-413,275-311` [V] — `apply()` averaging/flip is a non-atomic primary()→close()→add() read-modify-write; concurrent engine-thread + poller-thread fills on the same (strategy,symbol) double-realize PnL / corrupt qty. Fix: per-LegBook lock or marshal poller events onto the engine thread.
- `BybitLinearBroker.kt:313` (+ `spot:289`, StateRecovery) [V] — WS reader / `qkt-periodic-reconciler` / `reconnect-supervisor` threads `bus.publish` into non-thread-safe subscribers concurrently with the engine. Fix: serializing inbound mailbox drained on the engine thread (cross-cutting — escalate).
- `LiveSession.kt:703-737` [V] — `flatten()` mutates OrderManager + re-enters the single-threaded bus from the HTTP worker pool concurrent with the engine. (`@Volatile` claim was a misread — halt/resume already synchronized.) Fix: route flatten/halt/resume through the engine command queue.
- `PositionLeg.kt:54` / `StatePersistor.kt:67-101` [V] — `brokerTicket` not in `PersistedLeg`; null after any restart → `closeLegByTicket` never matches → venue close falls to netting `apply()` → opens a counter-position on hedging accounts (the hedge-accumulation bug). Fix: add nullable `brokerTicket` to `PersistedLeg`, round-trip it.
- `LiveSession.kt:204-211` / `StrategyPositionTracker.kt:42-51` [V] — `preloadFromPersistor` only runs when a PRIMARY leg exists; books where PRIMARY already closed (surviving STACK/INDEPENDENT) are dropped on restart → unmanaged live exposure. Fix: use `outcome.legBook` directly.
- `IterVarSubstitution.kt:117-128` [V] — FOR EACH `subst(opts)` rebuilds `ActionOpts` via an explicit constructor omitting `stack`/`stackAts` → STACK/STACK_AT silently dropped from every expanded leg, no error. Fix: `opts.copy(...)` + subst for stack/stackAts.
- `EquityCurveCollector.kt:37-43` [V] — one `EquitySample`/`BigDecimal` per strategy per tick under default TICK cadence, no cap; full curve serialized one-object-per-sample → memory/report size scale with tick count (OOM on MT5 tick runs). Fix: fixed-stride downsample; compute Sharpe/drawdown before decimation.
- `StrategyHandleJson.kt:71-74` [V] — `buildSnapshot` hardcodes equity/balance/realized/unrealized=ZERO for every daemon `/status`; the `qkt observe` PnL gate then inverts (passes only when fills net ~0). Fix: thread a PnL provider from `strategyPnL.realizedFor/unrealizedTotalFor/equityFor`.

**Medium**
- `OrderManager.kt:100-116` [V] — `riskByClientOrderId` only drained by `ReplayEngine` (backtest); live daemon leaks ~2 entries/bracket forever. (Fix needs the single drain point moved, not duplicated.)
- `LiveSession.kt:483` [V] — `trades = CopyOnWriteArrayList`, never trimmed despite a "bounded ring" contract → unbounded + O(N²) array-copy per fill. Fix: bounded ArrayDeque + snapshot-on-read.
- `StrategyPositionTracker.kt:79-163` [V] — `pendingStackOpens/Closes/IndependentOpens` drained only by a matching fill; the OCO-cancelled sibling leaks on every normal bracket round-trip. Fix: `forgetPending` on OrderCancelled/OrderRejected.
- `FileStatePersistor.kt:485` [V] — `fromDomain` `else->null` silently drops StopLimit/TrailingStop/TrailingStopLimit from `savePendingOrders`. Fix: exhaustive `when`, drop the catch-all.
- `EquityTracker.kt:35` [V] — per-strategy equity/peak frozen between fills (`updateStrategy` only on fill). Fix: drive `updateStrategy` from `onTick` (peak = running max).
- `StrategyHandle.kt:40` [V] — `tradeCount = ring.size()` counts signals too and caps at ring capacity (1000) → Prometheus `qkt_strategy_trades_total` inflated + plateaus. Fix: dedicated AtomicLong fill counter in `onTrade`.
- `ControlRoutes.kt:407-437` [V] — `/logs?follow=true` holds an HttpServer handler thread in `while(true){sleep}`; 8 followers starve the 8-thread control pool (blocks /health,/status,/stop). Fix: dedicated streaming executor or 503 cap.
- `IterVarSubstitution.kt:113` [V] — FOR EACH renames `Latch.stream` but doesn't substitute the iter-var inside latch sensor/entry exprs → `FROM s.high` keeps literal `s` → "Unknown stream alias". Fix: walk `BreakOffset.reference/offset` + entry DirRel dists.
- `Parser.kt:328-341` [V] — `parseStrategy` lets `StrategyAst.init` `IllegalArgumentException` (dup PARAM / PARAM-LET collision) escape instead of `ParseResult.Failure`. Fix: wrap in try/catch like `parsePortfolio`.
- `MT5Broker.kt:734,793-817` — `recentlyFilledTickets` reaped only via `onPendingDisappeared`; on a gateway without `/orders` it never fires → unbounded growth. Fix: sweep TTL in `onPendingPositionOpened`/`lookupClosedTicketMeta` too.
- `BybitSpotBroker.kt:47` / `BybitLinearBroker.kt:49` — `seenExecIds` add-only, no cap → slow unbounded leak. Fix: bounded LRU sized to the recovery window.
- `VWAP.kt:45-47` — `error()` on type-legal null `Tick.volume` on the per-tick path → uncaught exception can kill the live session. Fix: skip update on null volume, or reject at bind time.
- `OrderManager.kt:1163-1181` — `persistAll` labels every sibling pair a BracketPair via `-sl` substring match, including OCO/stack exit pairs → corrupt recovery state. Fix: tag sibling kind at creation.

**Low**
- `MT5Client.kt:304-321` — `getWithRetry` returns null with no log on persistent 5xx → poller mis-diffs silently. Fix: record non-2xx into `lastError`; warn on exhaustion.
- `ControlRoutes.kt:426` — `input.skip` return ignored in `/logs` follow → can re-send/shift log bytes. Fix: `skipNBytes` or loop.
- `NyseCalendar.kt:80-82` — singleton `holidayCache` plain mutableMap; latent CME. Fix: ConcurrentHashMap + computeIfAbsent.
- `BybitPublicWsClient.kt:30` — `state`/`hasDisconnected` no memory barrier across reconnect reader threads. Fix: ConcurrentHashMap + @Volatile.

### Dead-code / duplication

**Medium**
- `ManagedOrder.kt:21` / `OrderManager.kt:979,991` — `groupId` written in submitOco, never read (OCO uses `siblings`). Remove.
- `PortfolioStrategy.kt:118-122` — `makeChildEmit` returns identity lambda `{sig->upstream(sig)}`, allocated per child per tick. Pass `upstream` directly.
- `OrderManager.kt:88` (+1316,775,1041) — `lastObservedPrice` duplicates `priceProvider`. Delete, use `priceProvider.lastPrice`.
- `PnLProvider.kt:42-51` / `StrategyPnL.kt:40-52` — unrealized-PnL formula byte-identical (3rd copy at `TradingPipeline.kt:238-276`). Extract `unrealizedPnl(pos,price,contractSize)`.
- `PositionProvider.kt:31-75` / `StrategyPositionTracker.kt:313-414` — averaging/flip state machine duplicated (signed-qty vs leg model). Shared helper; risk of global-vs-per-strategy divergence.
- `MT5Client.kt:120-135` — `getAccount`/`isHedging`/`marginMode` docstring claims it drives close-routing; no prod reader. Wire or delete.
- `LocalMarketSource.kt:98` — `aggregateFromTicks` reimplements `CandleAggregator` OHLC bucketing; the copy drops bid/ask. Drive `CandleAggregator.standalone(window)`.
- `Rule.kt:13-109` [V] — sealed `Rule` + gt/lt/eq infix dead; DSL uses its own Cmp. Delete.
- `IndicatorMap.kt:8-24` — unused in prod; docstring claims a consumer that doesn't exist. Delete or wire.
- `HtmlReportConfig.kt:16-20` — `monteCarloSimulations/Seed`, `drawdownThresholdPct` never read (ReportBuilder hardcodes 1000/42L/-0.01). Thread through or remove.
- `RunCommand.kt:260-261` — `trades.fold(ZERO){acc,_->acc}` always yields ZERO. Inline or sum real values.
- `PortfolioSupervisor.kt:118-149` — `onCandle` allocates StrategyContext + a clock + always-run baseline every candle. Hoist to init.
- `StrategyAst.constants` — write-only; grammar always emits emptyList. Remove or document builder-only.

**Low (delete / fix-doc, batchable)**
- `OrderManager.kt:328` [V] — unreachable `else->error(... "7d-b")`; defeats sealed exhaustiveness + temporal-comment rule. Delete.
- `ExprCompiler.kt:65`, `PaperBroker.kt:175-176`, `MT5BrokerSimulator.kt:286-287` — redundant dead `when` arms over sealed types. Remove.
- `Reductions.kt:8` [V] — 9 extensions, zero prod callers. Delete file + test.
- `TimeWindow.kt:40` (`windowEndFor`), `SvgChart.kt:27-51` (`lineChart`), `StrategyPositionTracker.kt:435-518` (`closeLeg`/`driftFor`), `TradeHistory.kt:207-210` (`MONEY_ZERO`), `PaperBroker.kt:32` (unused `log`) — dead-except-tests. Delete.
- `ProfitFactor.kt:10-13` — both arms of the zero-loss branch return null. Collapse.
- Dead doc-links: `LocalBarStore.kt:22`, `MT5InstrumentRegistry.kt:11`, `RiskState.kt:25`, `AuditTicksCommand.kt:180-185`. Fix references / drop fields.
- `DaemonCommand.kt:277-306` — teardown sequence duplicated (hook lacks runCatching). Extract `shutdownDaemon()`.

### Performance

**Medium**
- `OrderManager.kt:1144-1207` [V] — `persistAll` rebuilds 3 per-strategy maps over all orders + siblings×2 on every track/update (per fill), sync JSON+disk. Fix: dirty-flag + end-of-tick coalesced flush.
- `EventBus.kt:63` — `forEach` allocates an iterator per publish (every tick + `stamp()` copy). Fix: index-based loop.
- `LatchCompiler.kt:118` — `exprCompiler.compile(rel.dist)` re-compiled at every fire for compile-time-constant distances. Fix: hoist compile into `compileEntry`.
- `IndicatorBinding.kt:254` — `tickFedForAlias` filters the full binding list per stream per tick. Fix: precompute `Map<alias,List>` at compile time.
- `EquityCurveCollector.kt:38` — full unrealized recompute + fresh list every tick under TICK. Fix: fold without intermediate list; coarser sampling.
- `MonteCarlo.kt:23,48-59` — materializes simulations×trades BigDecimal matrix + per-column re-sort (~1M transient BigDecimals). Fix: build/reduce per column, free as you go.
- `StackTracker.kt:39` — `all()` copies to a new list per call, incl. per-id inside runGc. Fix: expose a read-only view.

**Low**
- `OrderManager.kt:1319,1357-1361` [V] — per-tick `liveOrderIds.map` + 3-filter chain. (Inline single-pass is NOT safe — the `triggered` snapshot is load-bearing; firing mutates `liveOrderIds` mid-tick.) Fix: symbol-keyed index, keep snapshot semantics.
- `PaperBroker.kt` / `MT5BrokerSimulator.kt:78-140` — cancel() double-scans, onTick filter-allocates + O(n²) remove (duplicated). Fix: single-pass iterator-remove; symbol-bucket working orders.
- `RollingHigh.kt:35-40` / `RollingLow.kt:31-37` — O(period) rescan per `value()` call (Donchian 50-200). Fix: monotonic deque for O(1).
- `MaxOpenPositions.kt:26` — `allPositions().size` copies the whole map for a count. Fix: add `positionCount(): Int`.
- `AggregateBinding.kt:32` — `bindingsForAlias` filter+alloc multiple times per candle. Fix: precompute keyed map.
- `PnLProvider.kt:53-59` [V] — defensive `toMap()`+`.keys`+`.map` per tick (small: 0-2 open symbols). Fix: in-provider fold.

### Standards

**High**
- `ActionCompiler.kt:43` [V] — `clock` defaults to `SystemClock()`, AstCompiler omits it → LatchCompiler stamps latch order timestamp + GTD `expiresAt` from wall-clock while the engine runs FixedClock → backtest non-reproducible, GTD never expires. Fix: read `ec.strategyContext.clock.now()` inside the latch builder lambda; drop the ctor clock param.
- `ScheduleRunner.kt:52` [V] — `regs` mutated from 2 threads; unsynchronized RMW of `reg.nextFireMs` + `fired` guard → double-emit (fresh order id, no dedup) + concurrent bus entry. (Headline CME claim was a misread.) Fix: `synchronized(regs)` around both tick entry points.

**Medium**
- `Mt5TickFeedSource.kt:78` [V] — live MT5 tick stamped `clock.now()`, discarding `brokerTimeMs` → candle boundaries diverge from MT5 backtest replay + Bybit convention. Fix: stamp `tick.brokerTimeMs`.
- `FileStatePersistor.kt:416` — `fromDomain` non-exhaustive `when` + `else->null` over sealed `OrderRequest` (root cause of the StopLimit drop). Fix: explicit cases, drop catch-all so the build breaks on new variants.
- `LocalBarStore.kt:104` (+ Bybit/MT5 clients) — candle BigDecimal scale divergence: TV uses Money.SCALE, others raw → Candle equals/hashCode scale-sensitive. Fix: normalize all to Money.SCALE at construction.
- `SessionHigh.kt:11` — docstring claims intra-session refresh; computes once per session. Fix doc or refresh trigger.

**Low**
- `Position.kt:11-13` / `RangeAggregateIndicator.kt:50-71` / `BollingerBands.kt:56-68` / `Parser.kt:429-430` / `OrderManager.kt:100` — temporal "legacy" comment; side-effecting `currentRefreshKey()` query; DECIMAL128 vs Money.CONTEXT(DECIMAL64); bare `=` silently parsed as equality; lone ConcurrentHashMap implying nonexistent concurrency. Fix: rephrase / rename to `advanceRefreshKey` / use Money.CONTEXT / reject `=` in cmp position / use mutableMapOf.
