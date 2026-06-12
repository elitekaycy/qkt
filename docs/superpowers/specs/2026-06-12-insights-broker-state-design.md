# Insights Broker State — Design

**Date:** 2026-06-12
**Repos:** qkt (emitter), qkt-insights (collector/UI)
**Status:** draft

## Problem

The dashboard shows a synthetic per-strategy ledger (`startingBalance + engine realized + mid-priced unrealized`), not the broker account. Measured on prod 2026-06-12: real account balance 7,824.05 / equity 7,676.54 / open P&L −147.51, while insights shows two strategies pinned at 10,000 with realized 0 and zero stored trades. Three causes:

1. qkt never reads broker account state for insights — equity snapshots come from `StrategyPnL`, a paper ledger seeded from config (default 10,000).
2. Trade history before insights existed (and before realized-P&L persistence in v0.42.0) lives only in MT5 deal history, which nothing reads.
3. Mid-price valuation of hedge pairs cancels to ~0, hiding the bid/ask + swap + commission drag the broker actually charges.

Storage is also wasteful: 99.8% of stored events are 5s equity snapshots, each written three times (events table, FTS index, equity_snapshots) — ~10 of 13 MB after one day, growing ~6 MB/day with no retention.

## Decision

Broker truth flows **through qkt** (not insights→gateway directly), because:

- qkt owns ticket→strategy attribution (position tracker / order journal). The broker side can't attribute reliably: one shared magic (10001), comments truncated at 31 chars.
- qkt's `Broker` interface is the multi-venue abstraction. A future Bybit/other venue plugs in as a `Broker` impl; insights and the envelope contract never change.
- All egress rides the existing `InsightsSink` queue: engine thread untouched, one pipeline, one auth, one collector.

In qkt-insights, **live state lives in memory, history lives in the DB** — a hard split:

- Last-value data (account state, open positions) → in-memory maps keyed by instance/broker/strategy, updated in place, pushed over the existing WS. Never written to the events table. Keyed maps make duplicate rows structurally impossible.
- Durable data (deals, behavioral events, logs, a 1-minute equity rollup) → SQLite as today.

## Event contract (new families)

Two new `InsightsEventFamily` entries:

- `STATE("state")` — live, last-value semantics. Collector routes to memory, never to DB.
- `DEAL("deal")` — durable broker deal history. Collector persists, idempotent by id.

### `state.account`

Emitted per poll (~10s). Id `acct-<broker>-<ts>` (collector keeps latest per `(instance, broker)`).

```json
{
  "broker": "EXNESS", "currency": "USD",
  "balance": 7824.05, "equity": 7676.54,
  "margin": 540.97, "marginFree": 7135.57,
  "openProfit": -147.51, "marginLevel": 1419.03
}
```

### `state.positions`

Emitted per poll. **Full snapshot, full-replace semantics** — the collector swaps the whole position list for `(instance, broker)`, so closed positions disappear without tombstone events. Id `posn-<broker>-<ts>`.

```json
{
  "broker": "EXNESS",
  "positions": [
    { "ticket": "123", "symbol": "EXNESS:XAUUSD", "side": "BUY",
      "qty": 0.01, "entryPrice": 2300.5, "currentPrice": 2310.2,
      "profit": 9.7, "swap": -0.12, "openedAt": 1781200000000,
      "strategyId": "hedge_straddle" }
  ]
}
```

`strategyId` is attributed by qkt (ticket map, below); `null` = orphan/unattributed, and the UI must show it as such, not hide it.

### `broker.deal`

One envelope per broker deal (a deal = one executed in/out leg in MT5 terms). Deterministic id `deal-<broker>-<dealTicket>` so re-sent batches and re-backfills dedupe at the collector. Used both for **startup backfill** (full history fetch) and **ongoing sync** (incremental fetch each poll).

```json
{
  "broker": "EXNESS", "dealTicket": "456", "positionTicket": "123",
  "orderTicket": "789", "symbol": "EXNESS:XAUUSD", "side": "SELL",
  "entry": "OUT", "qty": 0.01, "price": 2310.2,
  "profit": 9.7, "commission": -0.07, "swap": -0.12,
  "magic": 10001, "comment": "dsl-hedge_straddle", "ts": 1781201000000,
  "strategyId": "hedge_straddle"
}
```

`strategyId` from the ticket map when the position is known to this daemon lifetime; otherwise best-effort from the comment prefix (`dsl-<id>`, truncated-prefix match against deployed ids); otherwise null.

### Retired

- `snapshot.equity` / `snapshot.position` emission stops (the `SNAPSHOT` family stays in the enum for config compat but the heartbeat emitter is removed). The flatline spam and its triple storage die.
- `trade.closed` **stays**: it is the only realized-P&L source for paper/backtest instances and carries engine-side attribution. For live broker instances the UI prefers `deals`; analytics fall back to `trade_closes` when an instance has no deals.

## qkt design

### Broker interface additions

```kotlin
/** Venue account snapshot for observability; null when the venue has none (paper). */
fun accountState(): BrokerAccountState? = null

/** Venue deals in [from, to] (epoch ms), oldest first; empty when unsupported. */
fun deals(from: Long, to: Long): List<BrokerDeal> = emptyList()
```

`BrokerAccountState` and `BrokerDeal` are new data classes in `com.qkt.broker` mirroring the payloads above. `getOpenPositions()` already exists and already returns per-ticket entries.

`MT5Broker` implements both via existing gateway routes: `GET /account` (`MT5Client.getAccount`), `GET /get_positions?magic=` (`MT5Client.getPositions`), `GET /history_deals_get?from_date=&to_date=`. Verified against the prod gateway 2026-06-12: `history_deals_get` currently **requires** the `position` parameter, so the one gateway change in scope is making it optional (range-only fetch returns all deals in the window). Release as a pinned tag bump on qkt-mt5 only — pa-quant runs `:latest` and is untouched.

### Ticket→strategy attribution map

`StrategyPositionTracker` is engine-thread-only, so the poller cannot read it. Instead the engine maintains a `ConcurrentHashMap<String /*ticket*/, String /*strategyId*/>` mirror, written on fill, recovery-seed, and close (removal). The poller only reads. The map joins `isReferenced` reachability like every other order-id structure (invariant from #255).

### BrokerStatePoller

New class `com.qkt.observe.insights.BrokerStatePoller`:

- Own daemon thread (same pattern as the reconciler), interval `state_poll_ms` (default 10_000), started by `LiveSession` when `insightsSink != null` and `STATE` family is enabled.
- Per cycle: `broker.accountState()` → offer `state.account`; `broker.getOpenPositions()` + ticket map → offer `state.positions`; `broker.deals(lastDealTs + 1, now)` → offer `broker.deal` per deal, advance `lastDealTs`.
- On start: backfill `broker.deals(backfillFrom, now)` where `backfillFrom` default = 30 days back, configurable. Dedupe is the collector's job (deterministic ids), so re-backfilling on every restart is safe and is the simplest correctness story.
- **Never touches the engine thread.** Gateway calls happen on the poller thread; results go straight to `sink.offer()` (O(1), non-blocking, drop-oldest). A slow or dead gateway delays only this thread.
- Two sessions sharing one account will both emit `state.account`; last-value keying makes that harmless (documented, not prevented).

### Config

```yaml
insights:
  events: [signal, risk, log, trade, order, state, deal]
  state_poll_ms: 10000
  deal_backfill_days: 30
```

## qkt-insights design

### LiveStateStore (in-memory)

New module `packages/store/src/liveState.ts`:

```ts
type AccountState = { broker, currency, balance, equity, margin, marginFree, openProfit, marginLevel, lastSeen }
type LivePosition = { ticket, symbol, side, qty, entryPrice, currentPrice, profit, swap, openedAt, strategyId, lastSeen }
class LiveStateStore {
  accounts: Map<string /*instance:broker*/, AccountState>
  positions: Map<string /*instance:broker*/, LivePosition[]>   // full-replace per state.positions
  upsert(instanceId, envelope): boolean   // returns true if state changed
  snapshot(instanceId?): { accounts, positions, staleness }
}
```

- Ingest path: `state.*` envelopes are consumed by the store and **never reach `ingestEvents`** — no events row, no FTS row.
- Staleness: `lastSeen` older than 3× poll interval (30s) → the API marks the entry `stale: true`; the UI greys it out instead of showing a confident dead number.
- Restart: memory starts empty, repopulates within one poll cycle. Acceptable, self-healing.

### Persistence (DB)

Migration 005:

```sql
CREATE TABLE deals (
  id TEXT PRIMARY KEY, instance_id TEXT NOT NULL, broker TEXT NOT NULL,
  deal_ticket TEXT NOT NULL, position_ticket TEXT, order_ticket TEXT,
  symbol TEXT, side TEXT, entry TEXT, qty REAL, price REAL,
  profit REAL, commission REAL, swap REAL, magic INTEGER, comment TEXT,
  strategy_id TEXT, ts INTEGER NOT NULL
);
CREATE INDEX idx_deals_lookup ON deals (instance_id, ts);
CREATE INDEX idx_deals_strategy ON deals (instance_id, strategy_id, ts);

CREATE TABLE account_equity (
  instance_id TEXT NOT NULL, broker TEXT NOT NULL,
  minute_ts INTEGER NOT NULL, balance REAL, equity REAL, open_profit REAL,
  PRIMARY KEY (instance_id, broker, minute_ts)
);
```

- `broker.deal` envelopes upsert into `deals` (`INSERT OR IGNORE` by id). They also skip the events table — `deals` is their home, same rule as logs.
- **Equity rollup:** once per minute the collector flushes the in-memory account state into `account_equity` (last value wins per minute). ~1.4k rows/day for the whole account vs 27k/day of per-strategy flatline today. This is the only sampled time series we keep, because a historical curve cannot be charted from a last-value map.
- `equity_snapshots` stops growing (no more `snapshot.equity`); existing rows are kept for old charts until a later cleanup. Defensively, ingest also stops writing any `snapshot.*` type into `events`/FTS.

### API / WS

- `GET /live/state` → `LiveStateStore.snapshot()` (auth as existing routes).
- WS: on `upsert() === true`, broadcast `{ kind: "state", ... }` on the existing live channel — the dashboard account header and open-positions table tick in real time without polling.
- `GET /account/equity?from&to` → `account_equity` rows (small enough to skip downsampling at 1/min).
- Trades endpoints gain a `source=deals` view; win-rate/realized analytics for live instances compute from `deals` (per-strategy via `strategy_id`), falling back to `trade_closes` for instances with no deals.

### UI

- **Account panel** (Overview header): real balance, equity, margin level, open P&L, currency — from `/live/state` + WS. Stale → greyed with "last seen Xs ago".
- **Open positions table**: live, per-ticket, with broker-valued profit and the strategy chip (orphans labeled `unattributed`, not hidden).
- **Equity page**: account curve from `account_equity`; per-strategy ledger curves demoted to a clearly-labeled "strategy ledgers" section.
- Per-strategy cards relabeled as **ledger allocations** (they share one account); per-strategy realized now from attributed deals.

## What this fixes, explicitly

| Symptom today | After |
|---|---|
| Equity pinned at 10,000 | Real account equity, live + 1-min history |
| Unrealized 0 vs real −147.51 | Broker-valued open P&L per ticket and account-wide |
| 0 trades in dashboard | Full deal history backfilled from MT5, idempotent |
| Pre-v0.42.0 realized lost | Recovered from deal history |
| 27k flatline rows/day, triple-stored | `state.*` never persisted; ~1.4k rollup rows/day |
| Duplicate strategy rows | Keyed in-memory maps; duplicates structurally impossible |

## Out of scope

- Gateway changes beyond relaxing `history_deals_get`'s required position filter.
- Retention/rollup for old `equity_snapshots` rows and the events table (parked).
- Per-strategy magic numbers (nice-to-have for broker-side attribution of *other tools'* trades; qkt's ticket map + comment fallback covers our own).
- Prop-firm monitor panels (Spec 4).

## Build order

1. **gateway:** make `position` optional on `history_deals_get`; release pinned tag, bump qkt-mt5 only.
2. **qkt:** `BrokerAccountState`/`BrokerDeal` types, `Broker` default methods, MT5 impls.
3. **qkt:** ticket→strategy mirror map + `BrokerStatePoller` + config + retire snapshot emitter.
4. **insights:** `LiveStateStore`, ingest routing (`state.*` → memory, `broker.deal` → `deals`), migration 005, rollup flusher.
5. **insights:** REST/WS endpoints + UI (account panel, open positions, equity page, deals-backed trades).

Each step ships independently; the dashboard turns truthful at step 3/4 even if later polish waits.
