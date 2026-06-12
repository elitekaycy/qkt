# Insights Broker State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dashboard shows broker truth — real account equity, broker-valued open positions, and full deal history — flowing through qkt's broker abstraction into in-memory live state + a small durable DB, replacing the synthetic 10,000-pinned ledger.

**Architecture:** qkt grows a `BrokerStatePoller` (own thread) that polls `Broker.accountState()/getOpenPositions()/deals()` and offers `state.account`/`state.positions`/`broker.deal` envelopes onto the existing `InsightsSink` queue. qkt-insights routes `state.*` into an in-memory `LiveStateStore` (never the DB), persists `broker.deal` into a `deals` table, and flushes a 1-minute account-equity rollup. The 5s `snapshot.equity` flatline emitter is retired.

**Tech Stack:** Kotlin (qkt), TypeScript/Fastify/better-sqlite3/React (qkt-insights), Flask (mt5-gateway).

**Deploy order is forced:** the insights contract is a strict zod discriminated union — unknown types 400 the whole batch. Gateway first (additive), insights second (accepts new types), qkt last (emits them).

**Spec:** `docs/superpowers/specs/2026-06-12-insights-broker-state-design.md`

---

## Phase A — mt5-gateway (repo: `/home/dickson/Desktop/personal/fxquant/mt5-gateway`)

### Task A1: make `position` optional on history_deals_get

**Files:**
- Modify: `app/routes/history.py:121-202` (route `history_deals_get_endpoint`)
- Modify: `VERSION` (0.1.0 → 0.2.0)

- [ ] **Step 1: Edit the handler** — require only `from_date`/`to_date`; pass `position` through only when present:

```python
        from_date = request.args.get("from_date")
        to_date = request.args.get("to_date")
        position = request.args.get("position")

        if not all([from_date, to_date]):
            return validation_error_response("from_date and to_date parameters are required")

        try:
            from_date = datetime.fromisoformat(from_date.replace("Z", "+00:00"))
            to_date = datetime.fromisoformat(to_date.replace("Z", "+00:00"))
            position = int(position) if position is not None else None
        except ValueError as e:
            return validation_error_response(f"Invalid parameter format: {str(e)}")

        if from_date >= to_date:
            return validation_error_response("from_date must be before to_date")

        from_timestamp = int(from_date.timestamp())
        to_timestamp = int(to_date.timestamp())
        if position is not None:
            deals = mt5.history_deals_get(from_timestamp, to_timestamp, position=position)
        else:
            deals = mt5.history_deals_get(from_timestamp, to_timestamp)

        if deals is None:
            return not_found_response("deals history", position if position is not None else "range")
```

Also update the swagger block: `position` → `"required": False`.

- [ ] **Step 2: Set VERSION to `0.2.0`.**
- [ ] **Step 3: Commit + push main** (subject only: `feat: allow range-only history_deals_get`). CI (`.github/workflows/docker.yml`) publishes `elitekaycy/mt5-gateway-api:0.2.0` (+ `latest`; pa-quant containers keep their already-pulled image until recreated — do NOT recreate them).
- [ ] **Step 4: Deploy to qkt-mt5 only.** On the VPS, find how qkt-mt5 is defined (Dokploy compose for qkt-prod), change image tag `0.1.0` → `0.2.0`, recreate `qkt-mt5` container only. Verify:

```bash
docker exec qkt-mt5 sh -c 'curl -s "http://127.0.0.1:5001/history_deals_get?from_date=2026-05-13&to_date=2026-06-13"' | head -c 300
```

Expected: JSON array of deals (not the validation error). Also re-verify broker login survived the recreate (the `*-config` volume holds it) by checking `/account` returns the account JSON.

---

## Phase B — qkt-insights (repo: `/home/dickson/Desktop/personal/qkt-insights`)

### Task B1: contract — new envelope types

**Files:**
- Modify: `packages/contract/src/payloads.ts`
- Test: `packages/contract/test/contract.test.ts`

- [ ] **Step 1: Failing test** — `EnvelopeSchema` accepts the three new types (mirror an existing accept-test):

```ts
it("accepts state.account, state.positions, broker.deal", () => {
  const base = { v: 1 as const, instanceId: "i", id: "x", seq: 0, ts: 1 };
  expect(EnvelopeSchema.safeParse({ ...base, type: "state.account", payload: {
    broker: "EXNESS", currency: "USD", balance: 7824.05, equity: 7676.54,
    margin: 540.97, marginFree: 7135.57, openProfit: -147.51, marginLevel: 1419.03,
  }}).success).toBe(true);
  expect(EnvelopeSchema.safeParse({ ...base, type: "state.positions", payload: {
    broker: "EXNESS", positions: [{ ticket: "123", symbol: "EXNESS:XAUUSD", side: "BUY",
      qty: 0.01, entryPrice: 2300.5, currentPrice: 2310.2, profit: 9.7, swap: -0.12,
      openedAt: 1781200000000, strategyId: "hedge_straddle" }],
  }}).success).toBe(true);
  expect(EnvelopeSchema.safeParse({ ...base, type: "broker.deal", payload: {
    broker: "EXNESS", dealTicket: "456", positionTicket: "123", orderTicket: "789",
    symbol: "EXNESS:XAUUSD", side: "SELL", entry: "OUT", qty: 0.01, price: 2310.2,
    profit: 9.7, commission: -0.07, swap: -0.12, magic: 10001,
    comment: "dsl-hedge_straddle", ts: 1781201000000, strategyId: "hedge_straddle",
  }}).success).toBe(true);
});
```

- [ ] **Step 2: Run** `pnpm --filter @qkt-insights/contract test` — FAIL (invalid discriminator).
- [ ] **Step 3: Add payloads** to `payloadByType`:

```ts
  "state.account": z.object({
    broker: z.string(), currency: z.string(), balance: z.number(), equity: z.number(),
    margin: z.number().optional(), marginFree: z.number().optional(),
    openProfit: z.number().optional(), marginLevel: z.number().optional(),
  }),
  "state.positions": z.object({
    broker: z.string(),
    positions: z.array(z.object({
      ticket: z.string(), symbol: z.string(), side,
      qty: z.number(), entryPrice: z.number(), currentPrice: z.number().optional(),
      profit: z.number().optional(), swap: z.number().optional(),
      openedAt: z.number().optional(), strategyId: z.string().nullable().optional(),
    })),
  }),
  "broker.deal": z.object({
    broker: z.string(), dealTicket: z.string(), positionTicket: z.string().optional(),
    orderTicket: z.string().optional(), symbol: z.string().optional(), side: side.optional(),
    entry: z.string().optional(), qty: z.number(), price: z.number(),
    profit: z.number(), commission: z.number().optional(), swap: z.number().optional(),
    magic: z.number().optional(), comment: z.string().optional(), ts: z.number(),
    strategyId: z.string().nullable().optional(),
  }),
```

- [ ] **Step 4: Run tests — PASS.** Commit `feat: state and deal envelope types`.

### Task B2: store — migration 005 + deal/snapshot routing in write.ts

**Files:**
- Create: `packages/store/src/migrations/005_broker_state.sql`
- Modify: `packages/store/src/write.ts`
- Test: `packages/store/test/store.write.test.ts`

- [ ] **Step 1: Migration** `005_broker_state.sql`:

```sql
CREATE TABLE deals (
  id TEXT PRIMARY KEY,
  instance_id TEXT NOT NULL,
  broker TEXT NOT NULL,
  deal_ticket TEXT NOT NULL,
  position_ticket TEXT,
  order_ticket TEXT,
  symbol TEXT,
  side TEXT,
  entry TEXT,
  qty REAL,
  price REAL,
  profit REAL,
  commission REAL,
  swap REAL,
  magic INTEGER,
  comment TEXT,
  strategy_id TEXT,
  ts INTEGER NOT NULL
);
CREATE INDEX idx_deals_lookup ON deals (instance_id, ts);
CREATE INDEX idx_deals_strategy ON deals (instance_id, strategy_id, ts);

CREATE TABLE account_equity (
  instance_id TEXT NOT NULL,
  broker TEXT NOT NULL,
  minute_ts INTEGER NOT NULL,
  balance REAL,
  equity REAL,
  open_profit REAL,
  PRIMARY KEY (instance_id, broker, minute_ts)
);
```

- [ ] **Step 2: Failing tests** in `store.write.test.ts`:

```ts
it("broker.deal persists to deals, skips events and FTS, dedupes by id", () => { ... ingest two copies of one deal envelope; expect deals count 1, events count 0, events_fts count 0 ... });
it("snapshot.equity no longer writes events or FTS rows", () => { ... ingest a snapshot.equity envelope; expect equity_snapshots 1, strategies equity updated, events 0 ... });
it("snapshot.position is dropped entirely", () => { ... expect events 0, accepted 0 ... });
```

- [ ] **Step 3: Implement in `ingestEvents`** (follow the existing log-branch pattern at write.ts:52-63):
  - `broker.deal` branch: `INSERT OR IGNORE INTO deals (...)`, bump `upInstance`, `upStrategy` when strategyId present, `continue` (no events/FTS row).
  - `snapshot.equity` branch: keep `insEquity` + `setEquity` + `upInstance` + `upStrategy`, but skip `insEvent`/`insFts`.
  - `snapshot.position` branch: `upInstance` only, then `continue`.
  - `state.*` types: `continue` immediately (memory-only; the collector routes them before ingest, this is defense in depth).
- [ ] **Step 4: Tests pass; full store suite passes** (`pnpm --filter @qkt-insights/store test` — `equityCurve`/analytics tests must still pass since `equity_snapshots` writes are unchanged). Commit `feat: deals table and snapshot ingest hygiene`.

### Task B3: store — LiveStateStore + rollup

**Files:**
- Create: `packages/store/src/liveState.ts` (export from `index.ts`)
- Test: `packages/store/test/store.liveState.test.ts`

- [ ] **Step 1: Failing tests:**

```ts
it("upserts account state keyed by instance:broker and reports staleness", () => { ... });
it("replaces the full position list per state.positions envelope", () => { ... two envelopes; second has 1 position; snapshot shows 1 ... });
it("upsert returns false when nothing changed", () => { ... same account payload twice; second upsert returns false ... });
it("flushRollup writes one account_equity row per instance:broker minute", () => { ... });
```

- [ ] **Step 2: Implement:**

```ts
export interface AccountState { broker: string; currency: string; balance: number; equity: number;
  margin?: number; marginFree?: number; openProfit?: number; marginLevel?: number; lastSeen: number; }
export interface LivePosition { ticket: string; symbol: string; side: string; qty: number;
  entryPrice: number; currentPrice?: number; profit?: number; swap?: number;
  openedAt?: number; strategyId?: string | null; }

export class LiveStateStore {
  private accounts = new Map<string, AccountState>();
  private positions = new Map<string, { at: number; list: LivePosition[] }>();

  /** Returns true when the visible state changed (drives WS broadcasts). */
  upsert(instanceId: string, e: Envelope): boolean { /* key `${instanceId}:${payload.broker}`;
    state.account → shallow-compare then set; state.positions → replace list */ }

  snapshot(now: number, staleAfterMs = 30_000): LiveStateSnapshot { /* arrays + stale flags */ }

  /** Last value per (instance, broker) into account_equity for the current minute. */
  flushRollup(db: Db, now: number): void {
    const minute = Math.floor(now / 60_000) * 60_000;
    const up = db.prepare(`INSERT INTO account_equity (instance_id, broker, minute_ts, balance, equity, open_profit)
      VALUES (?,?,?,?,?,?) ON CONFLICT(instance_id, broker, minute_ts)
      DO UPDATE SET balance=excluded.balance, equity=excluded.equity, open_profit=excluded.open_profit`);
    for (const [key, a] of this.accounts) { const [instanceId, broker] = splitKey(key); up.run(instanceId, broker, minute, a.balance, a.equity, a.openProfit ?? null); }
  }
}
```

- [ ] **Step 3: Tests pass. Commit** `feat: in-memory live state store with equity rollup`.

### Task B4: collector + server — route state.*, start rollup timer

**Files:**
- Modify: `packages/collector/src/index.ts` (deps gain `liveState: LiveStateStore`)
- Modify: `src/server.ts`
- Test: `packages/collector/test/collector.test.ts`

- [ ] **Step 1: Failing test** — POST /ingest with a `state.account` envelope: 200, liveState has it, db `events` empty; `broker.deal` envelope: 200, lands in `deals`.
- [ ] **Step 2: Implement** in the ingest route, before `ingestEvents`:

```ts
const stateEvents = events.filter((e) => e.type.startsWith("state."));
const rest = events.filter((e) => !e.type.startsWith("state."));
const accepted = ingestEvents(deps.db, instanceId, rest);
for (const e of stateEvents) deps.liveState.upsert(instanceId, e);
for (const e of events) deps.bus.publish(e);
return reply.code(200).send({ accepted: accepted + stateEvents.length });
```

- [ ] **Step 3: server.ts** — construct `const liveState = new LiveStateStore()`, pass to collector and API deps; start rollup timer in `run`/`serve` modes:

```ts
const rollup = setInterval(() => liveState.flushRollup(db, Date.now()), 60_000);
rollup.unref();
```

- [ ] **Step 4: Tests pass. Commit** `feat: collector routes live state to memory`.

### Task B5: api — /live/state, /account/equity, /deals

**Files:**
- Modify: `packages/api/src/rest.ts` (+ deps gain `liveState`)
- Modify: `packages/store/src/queries.ts`
- Test: `packages/api/test/api.rest.test.ts`, `packages/store/test/store.queries.test.ts`

- [ ] **Step 1: queries.ts** — add `listDeals(db, {instanceId, strategyId?, limit, before?})` (from `deals`, ts DESC) and `accountEquity(db, {instanceId, from?, to?})` (from `account_equity`, minute_ts ASC). Tests first.
- [ ] **Step 2: rest.ts** — `GET /live/state` → `liveState.snapshot(Date.now())`; `GET /deals` → `listDeals`; `GET /account/equity` → `accountEquity`. All behind `requireSession`. Tests: 401 without session, 200 with.
- [ ] **Step 3: analytics** — in `strategyStats`, when the instance has deals for the strategy (`SELECT COUNT(*) FROM deals WHERE instance_id=? AND strategy_id=? AND entry IN ('OUT','INOUT','OUT_BY')`), compute realizedPnl/winRate/tradeCount from deal rows (profit + commission + swap per deal) instead of `trade_closes`; otherwise unchanged. Test with seeded deals.
- [ ] **Step 4: Full suite passes. Commit** `feat: live state, deals, and account equity endpoints`.

### Task B6: web — account panel, open positions, equity + trades views

**Files:**
- Create: `apps/web/src/useLiveState.ts` (react-query on `/live/state`, refetch 10s, plus WS `state.*` envelopes from `useLiveStream` trigger refetch)
- Modify: `apps/web/src/pages/Overview.tsx` (Account panel: balance, equity, margin level, open P&L, currency, stale badge; open-positions table with strategy chip, `unattributed` label for null)
- Modify: `apps/web/src/pages/Equity.tsx` (account curve from `/account/equity` as the primary chart; per-strategy ledger curves demoted under a "strategy ledgers" heading)
- Modify: `apps/web/src/pages/Trades.tsx` (deals view: symbol, side, entry, qty, price, profit+costs, strategy, time; falls back to trade_closes view when no deals)
- [ ] **Step 1: Implement hook + panels** following existing `useQuery`/`Card`/`Stat`/`Table` patterns; stale → `Pill` "stale Xs".
- [ ] **Step 2:** `pnpm build` green; vitest suite green. Commit `feat: broker-truth dashboard panels`.

### Task B7: deploy insights to prod

- [ ] **Step 1:** PR → main, CI green, merge (repo flow: single main).
- [ ] **Step 2:** Build/pull image, ship via the GHCR-private workaround (`gh auth token | docker login ghcr.io ... && docker save | gzip | ssh docker load`), recreate `qkt-insights` with the same env/volume.
- [ ] **Step 3: Compat check (critical):** old qkt v0.42.0 is still POSTing `snapshot.equity` + logs — verify `lastSeen` advances and no 400s in insights logs. The dashboard account panel will be empty until Phase C ships; it must render as "no data yet", not error.

---

## Phase C — qkt (repo: `/home/dickson/Desktop/personal/qkt`, branch `feat-insights-broker-state`, PR → dev)

### Task C1: MT5 wire — MT5Deal + getDeals(from, to)

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt` (existing MockWebServer pattern)

- [ ] **Step 1: Failing test** — MockWebServer returns a 2-deal JSON array for `/history_deals_get?from_date=...&to_date=...` (no position param); `getDeals` parses tickets, entry, profit/commission/swap, time_msc, magic, comment.
- [ ] **Step 2: Implement:**

```kotlin
data class MT5Deal(
    val ticket: Long,
    val orderTicket: Long,
    val positionTicket: Long,
    val symbol: String,
    val type: Int,          // 0=BUY, 1=SELL
    val entry: Int,         // 0=IN, 1=OUT, 2=INOUT, 3=OUT_BY
    val volume: BigDecimal,
    val price: BigDecimal,
    val profit: BigDecimal,
    val commission: BigDecimal,
    val swap: BigDecimal,
    val fee: BigDecimal,
    val magic: Int,
    val comment: String?,
    val timeMs: Long,
)

fun getDeals(fromUtcMs: Long, toUtcMs: Long): List<MT5Deal>? {
    val url = "$gatewayUrl/history_deals_get?from_date=${venueIso(fromUtcMs)}&to_date=${venueIso(toUtcMs)}"
    val raw = getWithRetry(url) ?: return null
    val arr = json.parseToJsonElement(raw) as? JsonArray ?: return null
    return arr.map { el -> /* parse fields as in getClosingDeal, time from time_msc */ }
}
```

Also extend `MT5AccountInfo` with `margin: BigDecimal?` and `profit: BigDecimal?` (gateway `/account` fields `margin`, `profit`) and parse them in `getAccount()`.

- [ ] **Step 3: Tests pass. Commit** `feat(mt5): deal-history range fetch and account margin fields`.

### Task C2: Broker interface — BrokerAccountState/BrokerDeal + MT5 impls

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/BrokerState.kt`
- Modify: `src/main/kotlin/com/qkt/broker/Broker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerTest.kt`

- [ ] **Step 1: Types** (BrokerState.kt):

```kotlin
/** Venue account snapshot for observability — what the broker says the whole account is worth. */
data class BrokerAccountState(
    val broker: String,
    val currency: String,
    val balance: BigDecimal,
    val equity: BigDecimal,
    val margin: BigDecimal?,
    val marginFree: BigDecimal?,
    val openProfit: BigDecimal?,
    val marginLevel: BigDecimal?,
)

/** One executed venue deal (an in/out leg in MT5 terms), used for insights history. */
data class BrokerDeal(
    val broker: String,
    val dealTicket: String,
    val positionTicket: String?,
    val orderTicket: String?,
    val symbol: String,
    val side: Side,
    val entry: String,       // IN | OUT | INOUT | OUT_BY
    val qty: BigDecimal,
    val price: BigDecimal,
    val profit: BigDecimal,
    val commission: BigDecimal,
    val swap: BigDecimal,
    val magic: Int?,
    val comment: String?,
    val ts: Long,
)
```

- [ ] **Step 2: Broker.kt defaults:**

```kotlin
/** Venue account snapshot for observability; null when the venue has none (paper). */
fun accountState(): BrokerAccountState? = null

/** Venue deals in [from, to] (epoch ms), oldest first; empty when unsupported. */
fun deals(from: Long, to: Long): List<BrokerDeal> = emptyList()
```

- [ ] **Step 3: MT5Broker impls** — `accountState()` from `client.getAccount()` (broker = profile.name.uppercase()); `deals()` from `client.getDeals()` mapping symbol via `mt5Symbol.toQkt` + profile prefix, entry int → name, type → Side. Tests with MockWebServer gateway.
- [ ] **Step 4: Commit** `feat(broker): account state and deal history surfaces`.

### Task C3: insights egress — translate, families, config

**Files:**
- Modify: `src/main/kotlin/com/qkt/observe/insights/InsightsConfig.kt` (families `STATE("state")`, `DEAL("deal")`; config keys `state_poll_ms` default 10_000, `deal_backfill_days` default 30)
- Modify: `src/main/kotlin/com/qkt/observe/insights/InsightsTranslate.kt`
- Test: `src/test/kotlin/com/qkt/observe/insights/InsightsTranslateTest.kt`, config test

- [ ] **Step 1: Translate builders** (failing tests first — id determinism + payload shape matching the contract in Task B1):

```kotlin
fun stateAccount(ts: Long, s: BrokerAccountState): InsightsEnvelope     // id "acct-${s.broker}-$ts"
fun statePositions(ts: Long, broker: String, positions: List<StatePosition>): InsightsEnvelope  // id "posn-$broker-$ts"
fun brokerDeal(d: BrokerDeal, strategyId: String?): InsightsEnvelope    // id "deal-${d.broker}-${d.dealTicket}", ts = d.ts
```

`StatePosition` is a small data class (ticket, symbol, side, qty, entryPrice, currentPrice?, profit?, swap?, openedAt?, strategyId?) defined next to the translator — the poller builds it from `MT5Position` + the ticket map.

- [ ] **Step 2: Config parse** for the two new keys + family names; tests. Commit `feat(insights): state and deal envelope translation`.

### Task C4: ticket→strategy attribution map

**Files:**
- Create: `src/main/kotlin/com/qkt/observe/insights/TicketAttribution.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` (fill handling writes; recovery seeding writes)
- Test: `src/test/kotlin/com/qkt/observe/insights/TicketAttributionTest.kt`

- [ ] **Step 1:** `TicketAttribution` is a thin concurrent map:

```kotlin
/**
 * Broker-ticket → strategy-id mirror, maintained on the engine thread and read by the
 * insights state poller (which must not touch engine-thread-only trackers).
 * e.g. fill for ticket 2832831596 on hedge_straddle → map["2832831596"] = "hedge_straddle".
 */
class TicketAttribution {
    private val owners = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun record(ticket: String?, strategyId: String?) { if (!ticket.isNullOrBlank() && !strategyId.isNullOrBlank()) owners[ticket] = strategyId }
    fun ownerOf(ticket: String): String? = owners[ticket]
    /** Drop tickets the broker no longer reports — keeps the map bounded by open positions. */
    fun retainAll(liveTickets: Set<String>) { owners.keys.retainAll(liveTickets) }
    /** Fallback: match a truncated MT5 comment ("dsl-hedge_stradd") against deployed ids. */
    fun fromComment(comment: String?, deployedIds: Collection<String>): String? {
        val c = comment?.removePrefix("dsl-") ?: return null
        if (c.isBlank()) return null
        val hits = deployedIds.filter { it.startsWith(c) || c.startsWith(it) }
        return hits.singleOrNull()
    }
}
```

- [ ] **Step 2: Wire writes** in LiveSession: on `BrokerEvent.OrderFilled` (where the bus subscription already exists for insights ORDER family) `record(e.brokerOrderId, e.strategyId)`; on recovery orphan seeding (`MT5StateRecovery` path) record ticket→strategyId. The poller calls `retainAll` each cycle from the live position set — that, not event-driven removal, is the GC story (#255 invariant satisfied: bounded by broker-open tickets).
- [ ] **Step 3: Tests pass. Commit** `feat(insights): broker ticket attribution map`.

### Task C5: BrokerStatePoller + retire snapshot emitter

**Files:**
- Create: `src/main/kotlin/com/qkt/observe/insights/BrokerStatePoller.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` (start/stop poller; delete `emitInsightsSnapshots` + heartbeat branch + `lastInsightsSnapshotMs`)
- Test: `src/test/kotlin/com/qkt/observe/insights/BrokerStatePollerTest.kt`; update `LiveSessionInsightsTest.kt`

- [ ] **Step 1: Poller:**

```kotlin
/**
 * Polls broker account/positions/deals on its own daemon thread and offers
 * state/deal envelopes to the insights sink. Never runs on the engine thread;
 * a slow gateway delays only this poller. Restart-safe: deal ids are
 * deterministic, so re-backfilling after a restart dedupes at the collector.
 */
class BrokerStatePoller(
    private val brokers: List<Broker>,
    private val sink: InsightsSink,
    private val attribution: TicketAttribution,
    private val deployedIds: () -> Collection<String>,
    private val pollIntervalMs: Long = 10_000L,
    private val backfillDays: Long = 30L,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    // thread main loop:
    //   on start: lastDealTs = now - backfillDays*86_400_000
    //   each cycle, per broker with accountState() != null:
    //     accountState()  -> sink.offer(InsightsTranslate.stateAccount(now, state))
    //     getOpenPositions() raw tickets via brokerPositions() helper -> attribution.retainAll + statePositions envelope
    //     deals(lastDealTs + 1, now) -> per deal: strategyId = attribution.ownerOf(d.positionTicket ?: d.dealTicket)
    //         ?: attribution.fromComment(d.comment, deployedIds()); offer brokerDeal; lastDealTs = max(lastDealTs, d.ts)
    //   sleep(pollIntervalMs) interruptibly; close() interrupts + joins
}
```

Position detail: `Broker.getOpenPositions()` returns aggregated `Position` without tickets — the poller needs tickets, so add to `Broker`:

```kotlin
/** Per-ticket open positions for observability; default empty. */
fun openTickets(): List<BrokerDeal> = emptyList()   // NO — see below
```

**Correction (locked here so implementation doesn't drift):** do not overload `BrokerDeal`. Add a dedicated type in BrokerState.kt:

```kotlin
/** One venue position ticket, broker-valued. */
data class BrokerPositionTicket(
    val ticket: String, val symbol: String, val side: Side, val qty: BigDecimal,
    val entryPrice: BigDecimal, val currentPrice: BigDecimal?, val profit: BigDecimal?,
    val swap: BigDecimal?, val openedAt: Long?, val comment: String?,
)
```

with `Broker.positionTickets(): List<BrokerPositionTicket> = emptyList()` and an MT5Broker impl from `client.getPositions(profile.magic)` (it already returns ticket, profit, comment, openTime). The poller uses `positionTickets()`, attributing each via `attribution.ownerOf(ticket) ?: fromComment(comment, deployedIds())`.

- [ ] **Step 2: Poller tests** with a fake Broker + recording sink: backfill emits all deals once; second cycle emits only new ones; positions envelope is full-replace; account envelope every cycle; attribution fallback works; close() stops the thread.
- [ ] **Step 3: LiveSession wiring** — in `start()`, when `insightsSink != null && STATE in insightsEvents`, build poller from the session's built brokers (the `buildBroker()` instances), start it; stop in `stop()`. Delete `emitInsightsSnapshots`, the heartbeat call, and the SNAPSHOT family wiring; keep the `SNAPSHOT` enum entry (config compat) but it now wires nothing. Update `LiveSessionInsightsTest` accordingly (snapshot tests become poller-wiring tests).
- [ ] **Step 4: Full suite** `./gradlew test` green locally is NOT the gate — push and let CI verify (project convention). Commit `feat(insights): broker state poller replaces ledger snapshots`.

### Task C6: PR, release, deploy

- [ ] **Step 1:** PR `feat-insights-broker-state` → dev; CI green; merge. (Body: Refs the spec; no Claude footer.)
- [ ] **Step 2:** Release pipeline: check.yml auto-FF → testing → integration.yml → promote-to-main dispatch → tag v0.43.0 → docker.yml publishes `ghcr.io/elitekaycy/qkt:v0.43.0`.
- [ ] **Step 3:** qkt-prod repo: update `qkt.config.yaml` insights block — `events: [signal, risk, log, trade, order, state, deal]`, add `state_poll_ms: 10000`, `deal_backfill_days: 30`; bump image tag (three-way sync: .env + Dokploy pg compose env + container recreate).
- [ ] **Step 4:** Recreate qkt container; watch logs for `[insights]` warns; confirm no engine-latency regressions in latency snapshot.

---

## Phase D — live verification (the goal gate)

- [ ] Dashboard Account panel shows balance ≈ 7,8xx / equity ≈ 7,6xx (live numbers), open P&L negative, margin level present, currency USD.
- [ ] Open positions table lists the hedge tickets with broker-valued profit; attribution shows `hedge_straddle` (or `unattributed` for pre-restart orphans — visible, not hidden).
- [ ] `deals` table populated with ~30 days of history; Trades page shows them; per-strategy realized in Strategies derives from deals.
- [ ] `account_equity` accruing ~1 row/min; `events` table NOT growing from snapshots (`SELECT COUNT(*) FROM events WHERE type LIKE 'snapshot.%'` static).
- [ ] No `[insights]` 400s in qkt logs; `lastSeen` advancing; WS pushes state to an open dashboard session.

## Self-review notes

- Spec coverage: contract (B1), memory/DB split (B2-B4), endpoints/UI (B5-B6), poller/attribution/retirement (C3-C5), gateway (A1), deploy order honored (A → B7 → C6), staleness (B3/B6), trade.closed retained as paper fallback (B5 step 3 fallback path). Equity rollup (B3/B4). Backfill + ongoing deals (C5).
- Type consistency: `BrokerPositionTicket` corrected inline in C5; translate builders in C3 take it via `StatePosition` mapping in the poller.
- The plan deliberately does not delete `equity_snapshots` or old UI ledger curves — spec keeps them as labeled ledgers.
