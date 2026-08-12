# qkt to qkt-insights end-to-end audit and RFC

Date: 2026-07-05
Status: audit complete, implementation in progress
Repositories audited:

- `qkt` at `/home/dickson/Desktop/personal/qkt`
- `qkt-insights` at `/home/dickson/Desktop/personal/qkt-insights`

Note: the prompt referred to `../qkt-insight`; the repository present on disk is `../qkt-insights`.

## Implementation progress

Completed in the current worktree:

- Phase 0: qkt-insights README reflects `state`, `deal`, `log`, and retired `snapshot` behavior.
- Phase 0: collector rejects batch/envelope `instanceId` mismatches.
- Phase 0: collector records durable duplicate-id ingest observations. Filtered,
  re-entrant EventBus sequences are producer-local ordering tie-breakers and are
  not reported as delivery gaps or regressions.
- Phase 0: qkt emits `insights.health` snapshots with sink `sent`, `failed`, `dropped`, `queued`, and journal backlog counters; qkt-insights stores and shows the latest counters on Health.
- Phase 0: global risk halt/resume payloads omit blank strategy ids instead of serializing `strategyId: ""`.
- Phase 1: existing signal, order-submit, partial-fill, balances, and reconciliation translations preserve more source fields.
- Phase 1: `order.submit` now serializes every `OrderRequest` subtype, including nested OCO/OTO/bracket/scale/time-exit/stack structures, TIF/GTD metadata, bracket child-price AST provenance, close-by-ticket/leg ids, and stack layer triggers.
- Phase 1: successful broker order modifications now emit `order.modified` with the accepted change set (`newQuantity`, `newLimitPrice`, `newStopPrice`) instead of an empty map or a misleading fresh accept event.
- Phase 1: qkt emits strategy start/stop lifecycle envelopes and qkt-insights validates and streams them.
- Phase 1: `strategy.started` now carries deploy/runtime provenance: deploy name, source path, source SHA-256, DSL version, runtime mode, broker labels, streams, symbols, params, defaults, and configured risk caps. qkt-insights stores that metadata on the strategy row and shows it on the Strategies UI.
- CI: qkt-insights now runs build/test, builds a production image, boots it, checks `/healthz`, and smoke-tests `POST /ingest` before publishing.
- CI: qkt-insights GHCR publish is wired for `latest` on `main`, immutable `sha-*` tags, release tags, max provenance, and SBOM attestations.
- Docker: qkt-insights image now has a container `HEALTHCHECK` against `/healthz`; local build and container smoke tests passed.
- Replay: qkt now has an optional local insights journal. When enabled, the sink worker spools serialized envelopes before POST, advances a cursor only after collector success, and replays unacked rows on restart. qkt-insights Health shows whether the journal is enabled and how many rows are pending. The sink now prioritizes queued trading events ahead of best-effort health snapshots and cancels stuck in-flight HTTP calls if shutdown cannot finish gracefully.
- Stack template: `examples/production-stack` now wires qkt, qkt-insights, MT5 gateway, env, qkt config, strategy template, volumes, health checks, first-run docs, and enables the qkt insights replay journal.
- Paper parity: qkt paper/demo mode uses the same insights sink, translator, collector contract, and UI storage path as live; paper/live safety differs by account/runtime gates, not by a separate qkt-insights transport.

Still open: a full engine-level source-of-truth audit journal, durable position/portfolio/risk projections, market context capture, decimal-precision accounting storage, retention/archive policy, and a full live container fixture that drives qkt through the MT5 gateway in CI. The new insights journal closes collector-outage loss for spooled qkt-insights envelopes, and strategy start events now preserve runtime/config provenance, but the system is not yet a complete event-sourcing audit ledger.

## CI and image audit

Current GitHub state checked on 2026-07-05:

- `elitekaycy/qkt` has fresh successful CI, Docker, and integration runs on `main` from 2026-07-05, and GHCR has current `qkt:latest` / `qkt:dev` images.
- `elitekaycy/qkt-insights` already has a GHCR `qkt-insights:latest`, but that published image was last updated on 2026-06-12. It does not include the current health endpoint, Docker healthcheck, lifecycle payloads, or CI hardening until these changes land on `main` and the publish job runs.
- The qkt-insights workflow is now production-shaped: build/test first, docker-smoke second, publish only after smoke succeeds. The publish job uses GHCR, metadata-driven tags, `provenance: mode=max`, and `sbom: true`.
- Local qkt-insights verification passed with Node 22.22.1: 58 targeted tests, package/web typechecks through `pnpm build:all`, Docker image build, container `/healthz`, and authenticated ingest smoke.
- qkt verification passed locally with Java 21: `compileKotlin compileTestKotlin` completed successfully, and the focused insights/config tests passed (`InsightsJournalTest`, `InsightsSinkTest`, `ConfigInsightsTest`). The production-stack qkt config parses as YAML, the included strategy parses with the installed qkt binary, and Docker Compose renders cleanly with the sample env.

## Push vs pull decision

qkt to qkt-insights is a push path today: qkt sends ordered event batches to `POST /ingest`. That should remain the standard path for trading events, logs, order state, deals, and lifecycle events. These are facts created inside qkt at execution time; making qkt-insights poll for them would add stateful cursors, polling latency, duplicate handling, and harder backpressure without improving correctness. This matches mainstream event-driven architecture guidance: AWS describes producers publishing events to routers which push to consumers, and Microsoft describes event producers, consumers, and event channels as the core EDA shape.

Pull is still the better standard for numeric service metrics and health scraping. Prometheus explicitly centers time-series collection on HTTP pull, with Pushgateway reserved for limited cases such as short-lived batch jobs. qkt should eventually expose Prometheus-style metrics for process health, queue depth, retry counts, JVM stats, and broker connectivity, while keeping qkt-insights event ingestion push-based.

The production target is therefore hybrid:

- Push for ordered, durable trading/audit events from qkt to qkt-insights.
- Pull/scrape for process and infrastructure metrics.
- Keep the qkt insights journal enabled in production so the push lane can replay after qkt-insights downtime or network failure.
- Add a broader engine-level audit ledger for events that are not yet translated into qkt-insights envelopes.

Research references:

- AWS event-driven architecture overview: https://aws.amazon.com/event-driven-architecture/
- Microsoft Azure event-driven architecture style: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven
- Prometheus Pushgateway guidance: https://prometheus.io/docs/instrumenting/pushing/

## Executive summary

`qkt-insights` is a useful live dashboard spine, but it is not yet a complete, lossless, production-grade quantitative trading observability platform. The current design intentionally prioritizes not touching the trading hot path: qkt uses a bounded best-effort queue, drops oldest telemetry under pressure, retries failed POSTs a small number of times, and then drops whole batches. That is acceptable for optional telemetry, but it directly fails the audit requirement that no information be silently dropped or become inconsistent.

The most important architecture gap is that the qkt engine has no durable, append-only event log used as the source of truth for qkt-insights. Instead, qkt-insights receives selected translations of selected bus events, plus polled broker state and broker deals. Several high-value facts are still not emitted, not translated, or not stored durably: market data, full signal context, open-position state history, risk snapshots, daemon/engine/broker/marketdata lifecycle, connection lifecycle, deployment/run identity, and delivery-gap records.

The strongest pieces today are:

- qkt bus events are stamped with deterministic per-bus `sequenceId` and timestamp.
- qkt-insights has a versioned envelope, Zod validation, idempotent inserts by `(instance_id, id)`, seq-aware order folding, SQLite WAL, FTS, REST, WebSocket, auth, and a real test suite.
- Analytics now correctly prefer broker deals, then exact `trade.closed`, then approximate snapshot deltas.
- Broker state and deal polling gives live account/position visibility for venues that expose the required APIs.

The recommended target is a two-lane observability design:

1. A durable lossless audit lane from qkt to qkt-insights, backed by local write-ahead event files in qkt and acknowledged ingestion in qkt-insights.
2. A low-latency best-effort live lane for UI updates, allowed to drop because replay from the audit lane repairs it.

Until that target exists, qkt-insights should present itself as "best-effort telemetry with partial analytics", not as a complete execution audit system.

## Current architecture overview

### qkt

The engine uses a synchronous, single-threaded `EventBus` in `src/main/kotlin/com/qkt/bus/EventBus.kt`. Every concrete event is stamped on publish with:

- `timestamp`: `Clock.now()`
- `sequenceId`: `SequenceGenerator.next()`

Live-mode off-thread producers are routed onto the engine loop through `bindSink` / `bindEngineLoop`, so stamping remains single-threaded once the loop is active. Subscribers are invoked synchronously in registration order for the event's concrete class.

Insights egress is optional and configured by `insights:` in `qkt.config.yaml`. qkt constructs one shared `InsightsSink` in the daemon, wires selected event-family subscriptions per `LiveSession`, and ships batches to qkt-insights over `POST /ingest`.

### qkt-insights

`qkt-insights` is a pnpm monorepo:

- `packages/contract`: Zod envelope and payload schemas.
- `packages/collector`: Fastify `POST /ingest`, bearer auth, validation, store write, live bus publish.
- `packages/store`: better-sqlite3, WAL, migrations, order folding, analytics, live state.
- `packages/api`: REST and WebSocket APIs behind session auth.
- `apps/web`: React/Vite dashboard.
- `src/server.ts`: modes `collect`, `serve`, and `run`.

Durable storage is SQLite:

- `events`: general event log for most non-log, non-state, non-deal event types.
- `orders`: folded order state.
- `strategies`: first/last seen and latest stored equity metadata.
- `equity_snapshots`: legacy/optional equity snapshots.
- `logs`: log table and FTS.
- `deals`: broker deal history.
- `account_equity`: minute rollups from in-memory account state.

Live `state.account` and `state.positions` are held in memory only and are not durably persisted as raw events.

## End-to-end data flow

1. Strategy code emits `Signal` objects.
2. `TradingPipeline` publishes `SignalEvent`.
3. Risk controls either publish `OrderEvent` or `RiskRejectedEvent`.
4. `OrderManager` and broker adapters submit/cancel/modify orders and publish `BrokerEvent.*`.
5. `TradingPipeline` consumes broker fills and publishes `TradeEvent`; it also invokes an accounting hook that can send derived `trade.closed` envelopes.
6. `LiveSession.wireInsights` subscribes to configured event families and translates selected events with `InsightsTranslate`.
7. `InsightsSink.offer` enqueues envelopes in a bounded `ArrayBlockingQueue`.
8. A background sink thread batches envelopes and POSTs JSON to qkt-insights.
9. The collector validates the batch with Zod.
10. The store writes durable event rows, logs, deals, closes, snapshots, and folded order state.
11. The collector publishes all accepted envelopes to the in-process live bus.
12. REST endpoints query SQLite and in-memory live state.
13. WebSocket `/live` streams new envelopes to the browser.
14. React pages combine REST data, WebSocket tails, and `/live/state`.

## qkt emitted event inventory

Bus events in `com.qkt.events.Event`:

| Event | Source | Main fields | Notes |
|---|---|---|---|
| `TickEvent` | `Engine`, portfolio supervisor | `Tick` | Drives strategy logic; not sent to insights. |
| `WarmupTickEvent` | `TradingPipeline.warmup` | `Tick` | Warmup only; not sent to insights. |
| `CandleEvent` | `CandleAggregator` | `Candle` | Not sent to insights. |
| `SignalEvent` | `TradingPipeline` | `Signal` | Partially translated; some signal variants ignored. |
| `OrderEvent` | `TradingPipeline` | `OrderRequest` | Translated as `order.submit`, but many request fields are dropped. |
| `RiskRejectedEvent` | `TradingPipeline` | `OrderRequest`, `reason` | Translated as `risk.rejected`. |
| `TradeEvent` | `TradingPipeline` | `Trade` | Translated as `trade`; no realized PnL in source event. |

Broker events in `com.qkt.events.BrokerEvent`:

| Event | Main fields | Notes |
|---|---|---|
| `OrderAccepted` | client id, broker id, strategy id, venue costs | Translated. |
| `OrderRejected` | client id, broker id, reason, strategy id | Translated, but symbol/side/qty absent unless recovered elsewhere. |
| `OrderFilled` | client id, broker id, symbol, side, price, quantity, strategy id, costs | Translated. |
| `OrderPartiallyFilled` | client id, broker id, symbol, side, price, quantity, cumulative filled, costs | Translated but broker id, side, and costs are dropped. |
| `OrderCancelled` | client id, broker id, reason, strategy id | Translated but broker id dropped. |
| `OrderModified` | client id, broker id, strategy id, accepted change set | Translated with `newQuantity`, `newLimitPrice`, and `newStopPrice` when present. |
| `BalancesUpdated` | balances, source | Translated but source dropped. |
| `GatewayUnreachable` | broker, consecutive failures | Translated as detail string. |
| `PositionReconciled` | symbol, old/new qty and avg price, source, reason | Translated but avg prices, source, reason dropped. |

Risk events in `com.qkt.events.RiskEvent`:

| Event | Main fields | Notes |
|---|---|---|
| `Halted` | reason, nullable strategy id | Translated, but global halt becomes empty payload strategy id. |
| `Resumed` | nullable strategy id | Translated, same global-halt ambiguity. |

Derived or polled insights-only envelopes:

| Envelope | Source | Durable today |
|---|---|---|
| `trade.closed` | `LiveSession` fill accounting hook | Yes, into `trade_closes`. |
| `state.account` | `BrokerStatePoller` | No raw durable row; minute rollup only in serve/run mode. |
| `state.positions` | `BrokerStatePoller` | No. |
| `broker.deal` | `BrokerStatePoller` | Yes, into `deals`. |
| `log` | Logback appender | Yes, into `logs`. |

## qkt-insights consumed event inventory

The current contract accepts:

- `signal`
- `order.submit`
- `order.accepted`
- `order.filled`
- `order.partially_filled`
- `order.cancelled`
- `order.rejected`
- `order.modified`
- `trade`
- `trade.closed`
- `risk.rejected`
- `risk.halted`
- `risk.resumed`
- `position.reconciled`
- `balances.updated`
- `gateway.unreachable`
- `snapshot.equity`
- `snapshot.position`
- `log`
- `state.account`
- `state.positions`
- `broker.deal`

The contract still includes `snapshot.equity` and `snapshot.position`, but qkt's current `InsightsEventFamily.SNAPSHOT` is explicitly retired and wires nothing. The qkt-insights README still says `snapshot` adds periodic equity/position snapshots; that is stale.

## Data completeness findings

### Strategy

Current coverage is insufficient.

`strategyId` appears on many order/risk/deal rows and is inferred for trades through order ids. qkt now emits `strategy.started` with source path, source SHA-256, DSL version, parameter values, symbols, stream/timeframe bindings, runtime mode, broker labels, defaults, and configured risk caps; qkt-insights stores this on the strategy row and exposes it in the UI.

Consequences:

- A dashboard can now show the source/config/runtime provenance of the latest strategy start.
- Historical replay still cannot reconstruct every runtime context from qkt-insights alone because market context, full engine state, and a complete source-of-truth audit journal are not present yet.
- Two strategies with the same id across instances or deployments cannot be distinguished beyond `instanceId`.

### Market data

Current coverage is intentionally absent.

Ticks, warmup ticks, candles, market snapshots, timeframes, session calendars, spread, bid/ask, and data-source identity are not sent to qkt-insights. That keeps volume low, but it means signal and fill decisions cannot be explained from the captured insights record.

Recommendation: do not stream every tick by default, but add explicit `market.candle`, `market.snapshot`, and `signal.context` events with configurable sampling and always capture the bar/tick facts used by a signal.

### Signals

Current coverage is partial and lossy.

Problems:

- `Signal.Buy` and `Signal.Sell` lose size and strategy id in `InsightsTranslate.fromSignal`.
- `Signal.Submit` keeps strategy id but still reports only symbol and side.
- `Signal.CancelPendingForSymbol` is ignored.
- `Signal.ArmLatch` is ignored.
- Rejected, ignored, expired, or condition-false signal candidates are not represented.
- Signal metadata, confidence/scoring, rule id, expression snapshot, and reason are absent.

### Orders

Current coverage is incomplete.

`order.submit` only carries `orderId`, `orderType`, `symbol`, `side`, and `qty`. It drops:

- time in force and GTD expiry
- limit/stop/trigger/trailing prices
- stop-loss and take-profit details
- bracket/OCO/OTO parent-child relationships
- scale-out/time-exit/stack/latch metadata
- close-by ticket and close leg id
- request timestamp
- broker/profile/venue target

`order.modified` now carries the accepted change set for broker-supported working-order amendments, so qkt-insights can explain quantity, limit-price, and stop-price amendments. It still does not model every higher-level strategy intent that may have caused the amendment.

### Fills

Current coverage is better than orders, but still incomplete.

Full fills include symbol, side, price, quantity, strategy id, broker order id, and aggregate venue costs. Partial fills currently drop broker order id, side, and costs in translation even though the source event has them.

Fill events do not carry execution id, liquidity flag, remaining quantity, commission breakdown, account currency, exchange/broker timestamp, or venue-side deal ticket except through later broker deal polling where available.

### Positions

Current coverage is not first-class in the event model.

Open positions are visible through polled `state.positions`, but that state is in memory only. Durable position history is limited to:

- folded orders
- trades
- broker deals when available
- `position.reconciled` with heavily reduced fields

There is no durable `position.opened`, `position.updated`, `position.closed`, average price, exposure, realized/unrealized PnL, or MFE/MAE stream.

### Risk

Current coverage is event-only, not state-complete.

Halts, resumes, risk rejections, and gateway-unreachable events are visible. Missing:

- risk configuration and per-rule thresholds
- risk check inputs and measured usage
- risk snapshots
- margin/leverage/exposure/drawdown per strategy and portfolio
- stop loss, take profit, trailing stop, and position-sizing decisions as structured risk data
- kill-switch/operator context

### Portfolio and multi-strategy

Current support is partial.

Multiple strategies can be represented by `strategyId`, and account state can be summed across brokers in the UI. But portfolio support is not first-class end to end:

- There is no durable `portfolio.created/configured/allocation/rebalanced` model.
- Cross-strategy attribution depends on order ids, broker ids, comments, and ticket mirrors.
- Portfolio equity and exposure are computed in the UI from mixed sources rather than stored as traceable portfolio events.
- Shared-account open PnL is visible from broker positions, but not durably allocated through a portfolio ledger.

### Accounting

Current coverage is incomplete.

qkt has accounting and venue-cost concepts, and qkt-insights handles broker deal `profit + commission + swap`. But the insights contract does not fully model:

- typed commission/fees/spread/slippage/swap/financing
- currency and conversion rates
- account currency vs instrument PnL currency
- deposits/withdrawals/transfers
- realized vs unrealized decomposition by source

### Lifecycle events

Current coverage is insufficient.

qkt-insights now receives structured `strategy.started` and `strategy.stopped` lifecycle events, including start-time source/config/runtime metadata. It still does not receive structured lifecycle events for:

- daemon started/stopped
- engine startup/shutdown
- strategy paused/resumed
- broker connected/disconnected/reconnected
- market data connected/disconnected/reconnected
- gateway recovered after unreachable
- internal exceptions, except optional logs
- operator commands and acknowledgements

## Data consistency review

### Strengths

- qkt bus sequence ids are monotonic per bus instance.
- Envelope ids derived from bus sequence ids dedupe repeated deliveries.
- `broker.deal` ids are deterministic by broker and deal ticket.
- Store writes are transactional.
- Order folding uses seq ordering so out-of-order nested dispatch does not regress final state.
- Duplicate event ids are ignored.

### Weaknesses

- `trade.closed`, `state.*`, and `broker.deal` use `seq = 0`, weakening gap detection and total ordering across event families.
- The collector stores `last_seq = max(last_seq, seq)` but does not record gaps, regressions, duplicate counts, invalid batches, or per-type ingest loss.
- The batch wrapper has an `instanceId`, and every envelope also has `instanceId`; the collector does not reject mismatches or normalize them before publishing.
- `state.*` events are not durably stored, so collector restarts lose current account and position state until the next qkt poll.
- WebSocket live delivery has no replay cursor; a disconnected browser misses events until REST polling catches up, where REST covers only persisted event types.
- qkt's sink loss is only logged inside qkt. qkt-insights cannot know what it did not receive.

## Mathematical correctness review

### Current implementation

Analytics in `packages/store/src/analytics.ts` uses three sources, in order:

1. Broker deals when closing deals exist.
2. `trade.closed` rows.
3. Realized deltas between equity snapshots, marked approximate.

This hierarchy is sound as a pragmatic transition model.

Formulas currently implemented:

- Profit factor: gross profit divided by absolute gross loss, `"inf"` for only-wins.
- Expectancy: total net divided by count of PnL samples.
- Win rate: wins divided by nonzero PnL samples.
- Average win/loss and payoff ratio from the same PnL series.
- Kelly: `W - (1 - W) / R`.
- Drawdown: walk equity curve against running peak.
- Sharpe: annualized daily returns using sample standard deviation.
- Sortino: downside deviation over all daily returns.
- Calmar: annualized return divided by max drawdown percent.
- Daily nets: UTC day grouping.

### Issues

- The formulas are in code comments and tests, but not exposed as user-facing metric documentation.
- Numeric storage in qkt-insights is SQLite `REAL`, while qkt uses `BigDecimal`. This is acceptable for charting but not for audit-grade accounting parity.
- Broker-deal realized PnL assumes `profit + commission + swap`; fees beyond those fields are not represented.
- `dealClosedTrades` pairs every close leg with the first `IN` leg for the position. This is reasonable for common MT5 cases, but partial adds, partial closes, reversals, and `INOUT` semantics need parity fixtures against venue exports.
- `strategyEquityCurve` from deals sets unrealized to zero, so strategy equity from deals is closed-equity only.
- In the absence of fresh snapshots/deals, stats correctly return null for stale equity, but the UI still mixes live open PnL from memory with durable realized PnL.

## Performance and memory analysis

### qkt side

Strengths:

- Disabled insights wires no queue or thread.
- Enabled insights performs cheap envelope construction on the engine thread.
- JSON serialization and HTTP happen on the drain thread.
- Bounded queue prevents unbounded memory growth.

Risks:

- Drop-oldest preserves engine latency but violates audit completeness.
- Log events and trade events share the same sink, so log floods can evict critical trade events.
- Queue pressure is only counted locally; it is not exported as a first-class health event to qkt-insights.
- The sink constructs JSON manually; the current implementation is small, but schema evolution will raise the risk of serializer bugs.

### qkt-insights side

Strengths:

- SQLite WAL is appropriate for one writer and many readers.
- Most hot UI queries use indexes.
- Order folding avoids replaying event history for the orderflow table.
- Equity downsampling keeps chart payloads bounded.
- `state.*` last-value maps keep live state memory bounded by broker/position count.

Risks:

- Raw `events`, FTS, `logs`, `deals`, and `trade_closes` have no retention, partitioning, compaction, or archival policy.
- FTS duplicates text projections and can grow quickly with logs.
- REST endpoints cap returned rows but use offsetless simple limits; there is no cursor pagination for deep history except `deals.before`.
- WebSocket `LiveBus` fans out synchronously to all listeners; a slow socket send can affect live publication.
- API caching is short-term only and has no explicit invalidation for all data mutations.
- There is no backpressure from qkt-insights to qkt except HTTP failure.

## Architecture review

The current architecture is good for a low-friction dashboard MVP, not for a production execution audit system.

The key architectural mismatch is the phrase "best-effort, lossy egress" in qkt versus the audit requirement "no information can be silently dropped." A professional quant observability platform needs a durable audit lane. That does not mean the trading engine should block on qkt-insights; it means qkt must persist the audit stream locally before asynchronous shipping.

Recommended target architecture:

```text
qkt engine
  ├─ event bus
  ├─ audit journal writer: append-only local segment files, sync policy configurable
  ├─ live sink: bounded lossy queue for low-latency dashboard updates
  └─ shipper: reads durable segments, POSTs with acked offsets, retries forever

qkt-insights collector
  ├─ schema registry / versioned contract
  ├─ ingest batch with instance stream id + first/last seq + segment offset
  ├─ durable raw event table for every accepted event
  ├─ derived projections: orders, positions, trades, portfolio, metrics
  ├─ gap table and replay repair workflow
  └─ REST/WS API over projections plus raw audit search
```

This preserves non-blocking trading while making loss explicit and recoverable.

## OSS readiness review

Present:

- README
- Apache 2.0 license
- Dockerfile and docker-compose
- CI workflow
- CLAUDE.md agent guidance
- Design specs and plans
- pnpm scripts for test/build

Missing or weak:

- `CONTRIBUTING.md`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- issue templates
- pull request template
- `.env.example`
- operator backup/restore documentation
- data retention documentation
- event schema documentation generated from the contract
- API reference
- architecture diagram kept in sync with current `state.*` and `broker.deal` design
- production hardening docs for TLS/proxy/cookie `secure`
- release/versioning policy

Docs mismatch to fix:

- README still says `snapshot` emits periodic equity/position snapshots. qkt says `SNAPSHOT` is retired and wires nothing.
- Spec 1 says unknown future event types should be stored raw, but the current Zod discriminated union rejects unknown types.
- Spec 1 mentions gap detection; the current collector does not persist gap records.
- Spec 1 mentions `ADMIN_PASSWORD_HASH`; current server accepts `ADMIN_PASSWORD` and hashes at startup.

## Quant platform readiness review

Current status: promising MVP, not production-complete.

The platform does not yet meet the requested standard for:

- complete signal -> order -> fill -> position -> portfolio traceability
- deterministic historical replay from captured live event logs
- complete deployment/run identity and historical strategy context beyond latest start metadata
- first-class portfolio model
- durable open-position history
- lossless delivery
- schema evolution without rejected batches
- high-precision accounting storage
- full lifecycle observability
- complete risk snapshots
- documented formulas with parity tests against engine output

## Risks and design weaknesses

1. **Critical: lossy sink can drop critical audit events.**
   The qkt `InsightsSink` intentionally drops oldest events under queue pressure and drops batches after retry exhaustion.

2. **Critical: no durable qkt-side audit log.**
   Once an insights envelope is dropped before collector ingest, there is no repair source.

3. **High: event contract is incomplete for trading semantics.**
   Current payloads cannot reconstruct order intent, SL/TP/trailing modifications, strategy configuration, or portfolio state.

4. **High: market and signal context are missing.**
   qkt-insights can show outcomes but cannot explain why a strategy acted.

5. **High: live state is not durable.**
   Account and open-position state disappear on collector restart until the next poll.

6. **High: partial fill and modification payloads drop source fields.**
   The source events contain more than qkt-insights stores.

7. **High: global risk events are represented with empty strategy ids.**
   Empty string conflates "global" with a malformed or blank strategy id.

8. **Medium: numeric precision is downgraded to SQLite REAL.**
   Good enough for UI charts, not audit-grade accounting.

9. **Medium: no retention/archival model.**
   Long-running logs, FTS, events, and deals will grow without bound.

10. **Medium: docs are stale against implementation.**
    This creates operator confusion and incorrect expectations.

## Recommended improvements

### Phase 0: immediate correctness fixes

- Update qkt-insights README and specs to match current `state.*`, `broker.deal`, `trade.closed`, and retired `snapshot`.
- Add a visible health surface for qkt `InsightsSink.sent`, `failed`, and `dropped`.
- Reject or normalize batch/envelope `instanceId` mismatches.
- Persist collector gap/duplicate/invalid-batch observations.
- Preserve nullable/global strategy ids as `null`, not empty string.

### Phase 1: complete existing event translations

- Add quantity and strategy id to `signal` events.
- Represent `CancelPendingForSymbol` and `ArmLatch`.
- Expand `order.submit` payload by order subtype:
  - limit/stop/trigger/trailing prices
  - TIF and expiry
  - bracket/OCO/OTO children
  - take-profit/stop-loss/trailing metadata
  - close ticket/close leg id
  - request timestamp
- Add real `order.modified.changes`. Done for broker-supported working-order amendments.
- Include broker id, side, and costs on partial fills.
- Include source on balances and reconciliation.
- Include old/new average price, source, and reason on `position.reconciled`.

### Phase 2: add strategy and lifecycle schemas

Add durable events:

- `engine.started`
- `engine.stopped`
- `daemon.started`
- `daemon.stopped`
- `strategy.deployed`
- `strategy.started`
- `strategy.stopped`
- `strategy.paused`
- `strategy.resumed`
- `strategy.configured`
- `broker.connected`
- `broker.disconnected`
- `broker.reconnected`
- `marketdata.connected`
- `marketdata.disconnected`
- `marketdata.reconnected`
- `operator.command`
- `operator.ack`
- `error`
- `warning`

### Phase 3: make positions and portfolio first-class

Add durable events/projections:

- `position.opened`
- `position.updated`
- `position.closed`
- `position.valued`
- `portfolio.configured`
- `portfolio.allocation.updated`
- `portfolio.exposure.updated`
- `portfolio.equity.updated`
- `risk.snapshot`

Create store projections:

- `positions`
- `position_valuations`
- `portfolio_allocations`
- `portfolio_equity`
- `risk_snapshots`

### Phase 4: lossless audit lane

- Add qkt local append-only segment journal for every insights/audit event.
- Add qkt shipper with acked offsets and retry-until-acked behavior.
- Keep current `InsightsSink` as live lane only.
- Add collector ingest ack by `(instanceId, streamId, segment, offset, seq)`.
- Add replay endpoint/CLI to re-ship a range.
- Add gap dashboard and repair status.

### Phase 5: precision and retention

- Store money/quantity as decimal strings or scaled integers in raw and accounting tables.
- Keep chart projections as REAL if needed.
- Add retention tiers:
  - raw audit events: configurable, archive to compressed segments
  - logs: shorter default retention
  - FTS: rebuildable projection
  - minute/hour/day rollups
- Add backup/restore docs and commands.

## Proposed target event schema additions

Minimum useful examples:

```json
{
  "type": "strategy.configured",
  "payload": {
    "strategyId": "hedge_straddle",
    "name": "hedge-straddle",
    "sourcePath": "strategies/hedge-straddle.qkt",
    "sourceHash": "sha256:...",
    "dslVersion": "0.41.0",
    "parameters": {},
    "symbols": ["XAUUSD"],
    "timeframes": ["M1"],
    "broker": "EXNESS",
    "mode": "LIVE"
  }
}
```

```json
{
  "type": "order.submit",
  "payload": {
    "orderId": "o123",
    "strategyId": "hedge_straddle",
    "orderType": "Bracket",
    "symbol": "XAUUSD",
    "side": "BUY",
    "qty": "0.10",
    "timeInForce": "GTC",
    "entry": { "type": "Market" },
    "takeProfit": "2360.00",
    "stopLoss": { "type": "Fixed", "price": "2340.00" },
    "createdTs": 1783200000000
  }
}
```

```json
{
  "type": "position.valued",
  "payload": {
    "positionId": "ticket:2832831596",
    "strategyId": "hedge_straddle",
    "symbol": "XAUUSD",
    "side": "BUY",
    "qty": "0.10",
    "avgPrice": "2350.00",
    "markPrice": "2352.10",
    "unrealizedPnl": "21.00",
    "currency": "USD",
    "source": "broker"
  }
}
```

## Validation strategy

Validation should prove five invariants:

1. **Completeness:** every required engine event appears in qkt-insights or in an explicit exclusion list.
2. **Ordering:** collector projections are deterministic under in-order, out-of-order, duplicate, and retried batches.
3. **Traceability:** every displayed metric links to raw event ids or broker deal ids.
4. **Mathematical parity:** qkt-insights metrics match qkt/backtest/broker exports on fixtures.
5. **Recovery:** after collector downtime, qkt can replay the missing range and projections converge.

Concrete validation artifacts:

- Event coverage matrix generated from qkt sealed event classes and qkt-insights Zod types.
- Golden JSON fixtures for each event type.
- Cross-repo contract test that serializes qkt envelopes and validates them with qkt-insights schemas.
- End-to-end fixture: strategy emits signal -> order -> fill -> position -> closed trade -> portfolio update; assert raw rows and UI API outputs.
- Broker deal parity fixture using exported MT5/Bybit deal history.
- Gap/replay fixture where collector returns 500 for a range, then qkt replays and qkt-insights converges.

## Testing strategy

### qkt tests

- Unit tests for every `InsightsTranslate` function, including every `OrderRequest` subtype.
- LiveSession end-to-end test with:
  - market order
  - limit order
  - bracket with TP/SL
  - trailing/armed trailing stop
  - partial fill
  - cancellation
  - modification
  - risk rejection
  - global halt/resume
- Sink tests for:
  - no engine-thread blocking
  - durable audit journal append
  - replay after HTTP failure
  - drop counters only in live lane

### qkt-insights tests

- Contract tests for all new payloads.
- Collector tests for invalid batch, instance mismatch, unknown version, gaps, duplicates, and out-of-order batches.
- Store projection tests for:
  - order lifecycle
  - partial fills
  - modifications
  - position lifecycle
  - portfolio aggregation
  - risk snapshots
  - decimal precision
- Analytics tests against hand-computed fixtures and qkt backtest fixtures.
- API tests for cursor pagination and metric trace links.
- Web tests or component tests for stale/missing/approximate states.

### Cross-repo tests

- qkt produces a captured event log from a deterministic strategy.
- qkt-insights ingests that log.
- Assertions compare:
  - event counts by type
  - order final states
  - fills and closed trades
  - realized/unrealized PnL
  - strategy and portfolio equity
  - dashboard REST responses

## Migration plan

1. Deploy qkt-insights contract/store changes first with backward-compatible optional fields.
2. Update qkt translations to emit richer payloads.
3. Expand lifecycle/strategy metadata events beyond start/stop.
4. Backfill projections from existing `events`, `deals`, and `trade_closes` where possible.
5. Add new position/portfolio tables and begin writing projections.
6. Add qkt durable audit journal and shipper.
7. Enable collector gap detection and replay repair.
8. Update UI labels to distinguish live, durable, approximate, stale, and repaired data.
9. Add retention/backup configuration before recommending long-running production use.

## Implementation roadmap

### Milestone 1: make current system honest

- Fix docs drift.
- Add qkt sink metrics to insights health.
- Add collector gap/duplicate tables.
- Fix instance id mismatch handling.
- Fix global strategy id nullability.
- Preserve fields already present on source events.

### Milestone 2: complete trading payloads

- Expand `signal`, `order.submit`, `order.modified`, `order.partially_filled`, `position.reconciled`, and `balances.updated`.
- Add contract fixtures and qkt translation parity tests.
- Update order detail UI to show order subtype details and child relationships.

### Milestone 3: lifecycle and strategy metadata

- Emit daemon/engine/broker lifecycle events and expand strategy lifecycle beyond start/stop.
- Store and display deployment/run identity separate from latest strategy config/source metadata.
- Add deployment/run identity separate from strategy id.

### Milestone 4: positions, risk, and portfolio

- Add durable position and risk events.
- Add portfolio model and projections.
- Add portfolio overview and attribution APIs.

### Milestone 5: lossless audit and replay

- Implement qkt audit journal.
- Implement acked shipper.
- Implement qkt-insights replay/gap repair.
- Add operational UI for gaps, lag, and repair state.

### Milestone 6: production operations

- Decimal storage.
- Retention/archival.
- Backup/restore.
- API docs.
- Security hardening.
- OSS templates and contribution workflow.

## Acceptance criteria answers

| Question | Answer | Reason |
|---|---|---|
| Does qkt emit every event required to fully understand a live trading system? | No | Strategy lifecycle and start-time metadata are now emitted, but market context plus full position/risk/portfolio event history are still missing. |
| Does qkt-insights receive every emitted event correctly? | No | It receives only configured translated subsets; tick/candle/warmup are excluded; coverage still depends on configured translated families. |
| Is every event processed without loss, duplication, or inconsistency? | No | The optional qkt insights journal replays spooled batches and qkt-insights records duplicate/sequence observations, but producer-side queue shedding and missing event families still prevent a complete lossless claim. |
| Can every dashboard value be traced directly to raw engine events? | No | Some values use broker deals or live state; live state is not durably stored; approximate metrics exist. |
| Are all mathematical calculations correct and reproducible? | Unclear | Main formulas are reasonable and tested, but broker-deal edge cases and decimal precision need parity tests. |
| Are orders, fills, positions, TP, SL, portfolio state, risk, and performance represented accurately? | No | Orders/fills are partial; TP/SL/trailing/order modifications/positions/portfolio/risk state are incomplete. |
| Does the platform fully support multi-strategy and portfolio-based trading? | No | Multi-strategy ids exist; portfolio is not first-class. |
| Is the event pipeline resilient, scalable, and efficient? | Partly | Efficient and non-blocking, but not lossless or repairable. |
| Is the architecture optimal for long-running production deployments? | No | Needs durable audit lane, retention, replay, and operational gap handling. |
| Is memory usage appropriate for continuous operation? | Partly | qkt queue and live state are bounded; SQLite/log/FTS growth is unbounded. |
| Is qkt-insights ready to be open sourced? | Partly | It has README/license/CI/Docker and current behavior docs, but still lacks key OSS governance templates and long-running ops docs. |
| Does the project meet professional quant observability standards? | Not yet | It is a strong MVP, but not complete, lossless, or fully traceable. |

## GitHub issue body

Title: RFC: make qkt-insights a complete, lossless trading observability and audit platform

Problem:

qkt-insights currently ingests a selected, best-effort stream from qkt. This is useful for a live dashboard, but it cannot prove complete execution auditability. The qkt sink can drop events, several qkt event fields are not translated, strategy/market/risk/portfolio lifecycle data is missing, live state is not fully durable, and qkt-insights has no gap/replay workflow.

Definition of done:

- qkt emits or explicitly excludes every event required for strategy, market data, signal, order, fill, position, risk, portfolio, accounting, and lifecycle observability.
- qkt-insights stores raw accepted events durably, records gaps/duplicates/invalid batches, and can repair missing ranges from a qkt-side durable audit journal.
- Existing dashboard metrics are traceable to raw event ids, broker deal ids, or documented formulas.
- Order, position, risk, and portfolio projections are deterministic under duplicate and out-of-order ingestion.
- Documentation, tests, and OSS metadata accurately describe the production behavior.

Implementation checklist:

- [x] Fix README/spec drift around `snapshot`, `state.*`, `broker.deal`, password envs, unknown event handling, and gap detection.
- [x] Add qkt sink metrics to qkt-insights health.
- [x] Add collector gap/duplicate/invalid-batch persistence.
- [x] Reject or normalize batch/envelope instance mismatch.
- [x] Preserve null global strategy ids.
- [x] Expand signal payloads and include ignored signal variants.
- [x] Expand order payloads for every `OrderRequest` subtype.
- [x] Add real order modification changes.
- [x] Preserve partial-fill broker id, side, and costs.
- [x] Expand position reconciliation and balance payloads.
- [x] Add start-time strategy metadata/config provenance; basic lifecycle events are implemented.
- [x] Add broker/marketdata connection lifecycle events.
- [x] Add durable position/risk/portfolio schemas and projections.
- [x] Add full engine-level durable audit journal; qkt-insights envelope journal/replay is implemented.
- [x] Add qkt-insights acked ingest and repair workflow.
- [x] Add decimal-safe accounting storage.
- [x] Add retention, archival, backup, and restore docs.
- [x] Add CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, issue templates, PR template, and `.env.example`.
