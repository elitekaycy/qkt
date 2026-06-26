# qkt-insights — Design (Spec 1: spine + thin UI slice)

Date: 2026-06-10
Status: approved, pre-implementation
Scope of this spec: the full data spine (qkt egress → collector → store → API → live stream), auth, Docker, and a thin vertical UI slice. The rich dashboard pages are a separate Spec 2 built on the proven spine.

---

## 1. Purpose

A standalone observability dashboard for the qkt trading family. It taps the events qkt already emits, ships them out of each running instance without ever blocking trading, persists them in a queryable store, and serves a clean web UI that gives insight across one or many qkt instances (different dockers / accounts) from a single place.

This is a new repository — `qkt-insights` — not a change to qkt's core, apart from a small, opt-in egress hook on the qkt side.

Non-goals for Spec 1: the full set of analytics pages, Sharpe/metrics, equity-curve and comparison charts, logs ingestion/search, alerting. Those are enumerated in §11 and deferred to Spec 2.

---

## 2. Why a backend + store, not "React reads a websocket"

The request needs **stored, queryable history**: full-text search over trades/strategies/events, equity curves over time, filters across history, multi-instance fan-in, and a dashboard that still shows what happened while it was closed. qkt's live surfaces (`ObservabilityServer` SSE ring, in-memory) are ephemeral and per-strategy. So the system is three tiers:

1. **qkt egress** — lives in the qkt repo; a fanout subscriber on the existing `EventBus` feeding a non-blocking sink that ships events out.
2. **qkt-insights collector + store + API** — ingests from N instances, persists to SQLite, serves query/search/stream.
3. **qkt-insights web** — the React/Vite dashboard.

Tiers 2 and 3 live in **one repo** with composable subsystems and run-modes (§7), so you can run just the recorder, the recorder + API, or the whole thing including the UI, from one Docker image.

---

## 3. Architecture overview

```
qkt instance(s)                  qkt-insights (single Node process, mode-flagged)        browser
┌──────────────┐  batched POST   ┌────────────────────────────────────────────┐  WS/REST  ┌────────┐
│ EventBus tap │ ───────────────▶│ collector → store(SQLite+FTS5) → api(+WS)   │ ────────▶ │ React  │
│ InsightsSink │   /ingest        └────────────────────────────────────────────┘           │  app   │
└──────────────┘  best-effort           ▲ single writer                                     └────────┘
   N instances ───────────────────────────┘ each tagged instanceId
```

- qkt → collector: **batched HTTP POST, best-effort/lossy** (engine never blocks; §5).
- collector → web: **WebSocket** for the live feel + REST for history/search.
- One SQLite file is the single writer in the whole system.

---

## 4. Event contract (`packages/contract`)

The shared truth, authored once as **Zod** schemas. It does double duty: runtime validation at the collector's ingest boundary, and the static TS types the web app imports. A discriminated union keyed on `type`.

### Envelope

```ts
type Envelope = {
  v: 1                    // schema version, for forward evolution
  instanceId: string      // which qkt instance/account, e.g. "qkt-prod"
  id: string              // globally unique event id (uuid)
  seq: number             // qkt EventBus sequenceId — monotonic per instance, for ordering/gap detection
  ts: number              // event timestamp (epoch ms), bus-stamped on the qkt side
  strategyId?: string     // owning strategy; absent for daemon-level events
  type: EventType
  payload: <type-specific>
}
```

### Event types and payloads

Each payload mirrors the fields of the matching qkt sealed `Event`. Names are dotted for namespacing.

| `type` | source qkt event | key payload fields |
|---|---|---|
| `signal` | `SignalEvent` | symbol, side, intent fields |
| `order.submit` | `OrderEvent` | orderId, orderType, symbol, side, qty |
| `order.accepted` | `BrokerEvent.OrderAccepted` | orderId, brokerOrderId |
| `order.filled` | `BrokerEvent.OrderFilled` | orderId, brokerOrderId, symbol, price, qty, venueCosts |
| `order.partially_filled` | `BrokerEvent.OrderPartiallyFilled` | orderId, price, qty, cumulativeQty |
| `order.cancelled` | `BrokerEvent.OrderCancelled` | orderId, reason |
| `order.rejected` | `BrokerEvent.OrderRejected` | orderId, reason |
| `order.modified` | `BrokerEvent.OrderModified` | orderId, changes |
| `trade` | `TradeEvent` | orderId, symbol, side, price, qty, ts |
| `risk.rejected` | `RiskRejectedEvent` | reason, attempted order summary |
| `risk.halted` | `RiskEvent.Halted` | strategyId, reason |
| `risk.resumed` | `RiskEvent.Resumed` | strategyId |
| `position.reconciled` | `BrokerEvent.PositionReconciled` | symbol, before, after |
| `balances.updated` | `BrokerEvent.BalancesUpdated` | balances |
| `gateway.unreachable` | `BrokerEvent.GatewayUnreachable` | detail |
| `snapshot.equity` | derived (§5) | strategyId, realized, unrealized, equity, startingBalance |
| `snapshot.position` | derived (§5) | strategyId, symbol, legs[] (side, qty, entryPrice, entryTs) |

**Excluded by default:** `TickEvent`, `WarmupTickEvent`, `CandleEvent` — too high-frequency for a dashboard; tick analysis belongs in backtest tooling.

### Wire format (batch)

```
POST /ingest
Authorization: Bearer <INGEST_TOKEN>
{ "instanceId": "qkt-prod", "events": Envelope[] }
```

The collector validates each envelope against the Zod union and rejects the batch (4xx) only on a malformed envelope; well-formed unknown future `type`s (higher `v`) are stored raw and ignored by current queries rather than rejected, so a newer qkt can't break an older collector.

---

## 5. qkt egress (new Kotlin, mirrors existing patterns)

Lives in qkt under `com.qkt.observe.insights` (sibling to `OrderJournal`). Opt-in, off by default, zero overhead when disabled.

### Config — mirrors `NotifyConfig`

`qkt.config.yaml`:

```yaml
insights:
  enabled: true
  url: "http://insights-host:8420/ingest"
  instance_id: "qkt-prod"
  token: "${INGEST_TOKEN}"
  events: [trade, order, signal, risk, position, snapshot]   # allow-list by family
  flush_interval_ms: 250
  batch_size: 200
  queue_capacity: 10000
  snapshot_interval_ms: 5000
```

Parsed into an `InsightsConfig` data class loaded by the existing `Config` machinery. `enabled: false` (or absent) → nothing is wired, no thread, no allocation.

### `InsightsSink` — the non-blocking core

The single most important correctness property: **the engine thread never blocks and never touches the network.**

- Backed by a bounded `ArrayBlockingQueue<Envelope>` of `queue_capacity`.
- The engine (publishing) thread does only: build a lightweight `Envelope` object and `queue.offer(it)`. `offer()` is O(1) and **never waits**. On a full queue it returns `false` → **drop-oldest** (`poll()` then `offer()`) and increment a `dropped` counter. No I/O, no locks shared with the trading loop.
- A single background **daemon thread** drains the queue, batches up to `batch_size` or `flush_interval_ms` (whichever first), serializes to JSON, and POSTs via the OkHttp client qkt already ships. **JSON serialization happens on this thread, not the engine thread**, so even encoding cost is off the hot path.
- The POST is synchronous *on the drain thread*; if the collector is slow or down, only the drain thread waits, the queue fills, and we drop-oldest. Trades are unaffected. Failures log at WARN and bump a `failed` counter; the drain thread applies a short backoff and continues.
- This is the same contract as `OrderJournal`: audit control, not a trading dependency. The durable record remains the journal; egress is best-effort live.

### Wiring — `wireInsights()` in `LiveSession`

Sits alongside `wireJournal()` / `wireNotifierSubscriptions()`. For each event family in `insights.events`, subscribe on the `EventBus`, translate the event to an `Envelope` (stamping `instanceId`, `seq` from the bus `sequenceId`, `ts`, `strategyId`), and `offer()` to the sink. Empty allow-list → no subscriptions.

### Snapshots — computed on the engine thread

Equity and positions are read from `StrategyPnL` / `StrategyPositionTracker`, which are **engine-thread-only** (the engine is single-threaded by design). Therefore snapshots must be produced on the engine thread, never from the drain thread:

- **On equity-changing events** (after a `TradeEvent`/fill is applied), produce a `snapshot.equity` for the affected strategy.
- **On a low-frequency cadence** (`snapshot_interval_ms`), a scheduled task posts a snapshot job onto the engine inbound queue (the same `bindEngineLoop()` path broker pollers already use); the loop computes per-strategy `snapshot.equity` + `snapshot.position` and hands them to the sink. This keeps all PnL reads on the owning thread while bounding snapshot frequency so unrealized PnL refreshes without per-tick noise.

Snapshots flow through the same lossy sink as everything else.

---

## 6. Collector + store + API (TypeScript)

### `packages/store` — the only writer

- `better-sqlite3` (synchronous, in-process), **WAL mode** (concurrent readers + one writer).
- Tables:
  - `instances` — id, display name, first_seen, last_seen, last_seq.
  - `events` — id, instance_id, type, strategy_id, seq, ts, payload (JSON). The raw append-only log; everything else is derivable.
  - `orders` — materialized current order state folded from `order.*` events (id, instance_id, strategy_id, symbol, side, type, state, qty, cum_qty, avg_price, created_ts, updated_ts) for the orderflow view without replaying events each query.
  - `strategies` — per (instance_id, strategy_id): first_seen, last_seen, last known equity/starting balance.
  - `equity_snapshots` — (instance_id, strategy_id, ts, realized, unrealized, equity) for curves.
- `events_fts` — FTS5 virtual table over a text projection of each event (type, symbol, strategy, reason, side, ids) for full-text search.
- A simple forward-only migration runner (numbered SQL migrations applied at boot).

### `packages/collector` — ingest

- `POST /ingest` — authenticates the `INGEST_TOKEN`, Zod-validates the batch, then in a single transaction: insert into `events`, fold `order.*` into `orders`, upsert `instances`/`strategies`, append `equity_snapshots` from `snapshot.equity`, index into `events_fts`.
- After commit, emits each accepted envelope to an in-process `EventEmitter` that the API's WS hub subscribes to, so live push and durable write share one path.
- Detects sequence gaps per instance (`seq` discontinuity) and records them for the health view.

### `packages/api` — query + live

- **Auth** (§8) middleware guards everything below.
- REST:
  - `GET /instances`
  - `GET /strategies?instance=`
  - `GET /strategies/:instance/:id`
  - `GET /orders?instance=&strategy=&symbol=&state=&from=&to=&limit=&cursor=`
  - `GET /trades?…same filters…`
  - `GET /events?type=&…&limit=&cursor=`
  - `GET /search?q=&type=&instance=` — FTS5-backed
  - `GET /equity?instance=&strategy=&from=&to=`
  - `GET /health/instances` — connection/last-seen/gap/dropped summary
- WS `/live` — client subscribes by `{instance?, strategy?, types?}`; server pushes matching envelopes as the collector ingests them.

Collector and API share the in-process store + emitter, so in `serve`/`run` they are **one process** — no IPC, no second SQLite writer.

Framework: Fastify (REST + WS via `@fastify/websocket`) or Hono; decided at plan time. Either is fine; the design assumes one HTTP server multiplexing `/ingest`, REST, WS, and (in `run`) the web `dist/` on a single port.

---

## 7. Repo layout, run-modes, tooling

```
qkt-insights/
  packages/
    contract/     # Zod event schema — types + validation, imported everywhere
    store/        # SQLite access, FTS5, migrations (only writer)
    collector/    # ingest endpoint → store
    api/          # REST + WS query layer over store
  apps/
    web/          # React + Vite + TS
  server.ts       # single entry; boots subsystems by mode
  Dockerfile
  docker-compose.yml
  pnpm-workspace.yaml
```

**Run-modes** (one entry, CLI flag — no duplicated wiring):

- `collect` → store + collector ingest only. Headless recorder.
- `serve` → store + collector + API (REST/WS). No UI bundle served.
- `run` → `serve` + serves the built web `dist/`. The full thing in one process.

The web app is always a static Vite build; `run` just has the API serve its `dist/`. One image, mode via CMD.

Tooling: pnpm workspace, TypeScript strict, Vitest, ESLint + Prettier. qkt's `CLAUDE.md` engineering conventions are ported into the new repo (naming, comments, smallest-change, no-mocks-in-e2e, ask-before-commit).

---

## 8. Auth

- **Dashboard**: single admin. `ADMIN_PASSWORD_HASH` (argon2) provided at boot. `POST /auth/login` checks the password, sets a **signed httpOnly session cookie**; all REST + WS reject requests without a valid cookie. No user table, no roles.
- **Ingest**: a separate shared `INGEST_TOKEN` (bearer) that every qkt instance presents on `/ingest`. v1 is one shared secret across trusted instances; **per-instance tokens are deferred to Spec 2** (promote earlier if untrusted/many instances are expected).

---

## 9. Docker

Multi-stage image; mode chosen at `docker run`.

```dockerfile
FROM node:22-slim AS build
RUN corepack enable
WORKDIR /app
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
COPY packages ./packages
COPY apps ./apps
RUN pnpm install --frozen-lockfile && pnpm -r build

FROM node:22-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/dist ./dist
COPY --from=build /app/node_modules ./node_modules
EXPOSE 8420
VOLUME /data
ENTRYPOINT ["node", "dist/server.js"]
CMD ["run"]            # override: "collect" or "serve"
```

- One port `8420` serves `/ingest`, REST, WS `/live`, and the web app in `run`.
- SQLite on a mounted volume (`/data`) so history survives restarts.
- `better-sqlite3` ships prebuilt binaries for node:22-slim → no compiler in the runtime stage.

`docker-compose.yml`:

```yaml
services:
  insights:
    build: .
    command: run                 # or: collect / serve
    ports: ["8420:8420"]
    volumes: ["insights-data:/data"]
    environment:
      ADMIN_PASSWORD_HASH: ${ADMIN_PASSWORD_HASH}
      INGEST_TOKEN: ${INGEST_TOKEN}
      INSIGHTS_DB: /data/insights.db
volumes: { insights-data: {} }
```

qkt connects by pointing `insights.url` at `http://insights-host:8420/ingest` (§5 config). `collect`-only lets a headless recorder run on a different box from the UI.

---

## 10. Web — thin vertical slice (React + Vite + TS)

Just enough UI to prove the whole pipe with real data; the rich pages are Spec 2.

- **App shell + login.** Login page posts to `/auth/login`. Authenticated shell with a left sidebar.
- **Sidebar:** instance switcher at the top (the multi-qkt "account switch"); nav items: **Health**, **Orderflow**. Remaining items (Strategies, Trades, Logs, Search, Equity) appear as disabled stubs so the shape is visible.
- **Health page** (per selected instance): connected?, strategies up, last-event age, sequence-gap indicator, dropped-event counter.
- **Live Orderflow page:** a live WS feed of orders/trades modeled as orderflow (state transitions visible), with filters by strategy / type / symbol, backed by `/orders` + `/trades` for history and `/live` for the tail.
- Data layer: TanStack Query for REST, a small `useLiveStream` hook over WS `/live`. Styling: Tailwind, clean minimal components. Charts/analytics deferred.

---

## 11. Explicitly deferred to Spec 2

- Per-strategy analytics pages (trades, trade logs, positions, stats).
- **Sharpe and other metrics**, equity-curve and comparison charts.
- **Logs ingestion and log search** — a different source (logback files, high volume) than the EventBus. Approach: a custom logback appender (mirroring the existing `SiftingAppender`) that ships log lines to the collector, opt-in; FTS5 over a `logs` table; log search UI. Flagged as a meaningful add if wanted earlier.
- Search UI surfaces across all pages; rich cross-cutting filters.
- Daily summaries / digests.
- **Gap-free durable egress** (qkt persists an outbound cursor and replays across collector downtime) — the v2 transport option; v1 is lossy best-effort.
- **Per-instance ingest tokens** replacing the shared secret.
- Alerting / notifications from the dashboard.

---

## 12. Testing

Per qkt conventions: real dependencies, no mocks in e2e.

- **contract**: schema validation tests — accept valid envelopes per type, reject malformed, tolerate unknown future types.
- **store**: real SQLite (temp file), migration apply, order-state folding, FTS5 search correctness, equity append.
- **collector**: integration — POST a real batch over real HTTP → assert rows, materialized `orders`, FTS index, and that the live emitter fired; bad token → 401; malformed batch → 4xx.
- **api**: real store + real HTTP — REST filters/pagination, search, equity range, auth gate on REST + WS, WS receives a live event after ingest.
- **qkt `InsightsSink`** (Kotlin): against a real local stub HTTP server — batching by size/interval, drop-oldest on a full bounded queue with the counter incrementing, engine-thread `offer()` never blocks, drain thread survives collector-down with backoff.
- **e2e (TS)**: boot `serve`, POST real envelopes through `/ingest`, query them back through the API and WS — full spine with real SQLite and real HTTP, no mocks.

---

## 13. Sequencing

1. `contract` (schemas + types) — everything depends on it.
2. `store` (tables, migrations, FTS, folding) — the spine's foundation.
3. `collector` (`/ingest` + validation + folding + emitter).
4. qkt egress (`InsightsConfig`, `InsightsSink`, `wireInsights`, snapshots) — can proceed in parallel once `contract` shape is fixed.
5. `api` (REST + WS + auth).
6. `server.ts` run-modes + Docker.
7. `apps/web` thin slice.
8. e2e spine test.

Spec 2 (rich UI + logs + metrics) is brainstormed separately on the proven spine.
