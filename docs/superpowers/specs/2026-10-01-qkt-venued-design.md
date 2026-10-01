# qkt-venued — the VGP v1 gateway host and its adapters — design

**Status:** design for review, no code yet. **Builds on:** the wire spec
`2026-10-01-vgp-v1-wire.md` (what any gateway must do), the qkt client in `connector/gateway` (what it
relies on), and `docs/research/2026-09-18-venue-plugin-architecture.md` §2 (why adapters run in a
separate host: the kill switch must be a choke point outside qkt).

## 1. What it is

One `qkt-venued` process serves **one account at one venue** over VGP v1. qkt, guardrails and insights
talk to it; it talks to the venue through one **adapter**. Everything a venue does not decide lives in
the host and is written once: auth, idempotent submits, the event journal (stream/seq/replay), the
kill switch, reconciliation with the venue, quote refresh, health and identity. An adapter only
translates between the venue's API and a small venue-neutral interface.

```
qkt daemon ──┐                 ┌──────────────────── qkt-venued (one per account) ───────────────────┐
guardrails ──┼── VGP v1 ──────▶│ HTTP/WS server ─ auth ─ kill switch ─ idempotency ─ event journal    │
insights ────┘  (bearer)       │        │                        ▲             ▲                       │
                               │        ▼                        │             │                       │
                               │   order router ──────▶ adapter ─┴─ reconciler ┴─ quote hub (refresh)  │
                               └──────────────────────────────│───────────────────────────────────────┘
                                                              ▼
                                                    venue API (Deribit JSON-RPC/WS, …)
```

**Lives in its own repository** (`qkt-venued/` in the workspace), never inside qkt: qkt stays a
consumer of VGP, and a gateway can be replaced, written in another language, or certified on its own.

## 2. Goals and non-goals

- **Goals:** every guarantee the wire spec states (decimal strings, idempotent POST, by-id lookup with
  dead ids, exclusive `since`, `reset`, settlements as events, kill switch with `reduce_only`, quotes
  refreshed while they hold); a restart of the gateway, of qkt, or of the venue connection loses or
  doubles nothing; adding a venue is writing one adapter.
- **Non-goals (v1):** more than one account per process; order types beyond market/limit/stop/stop-limit
  with `reduce_only` and GTC/IOC/GTD; venue-native combos; liquidation events (the wire spec defines
  none); HFT latency (holding periods are minutes to weeks).

## 3. The adapter interface

The host compiles against this; each adapter is one module implementing it. Every value is the
venue-neutral VGP shape (decimal strings become `BigDecimal` at this boundary, never `Double`).

```kotlin
interface VenueAdapter : AutoCloseable {
    val id: String                                     // "deribit" — reported as health.adapter
    val version: String
    fun connect(listener: AdapterListener)             // opens the venue link; listener gets pushes
    fun identity(): VenueIdentity                      // account login, trade mode (demo|real), currency
    fun instruments(): List<Instrument>                // codes, kind, sizes, expiry/strike/right/underlying
    fun account(): AccountSnapshot
    fun positions(): Positions                         // netting|hedging + per-symbol/ticket rows
    fun openOrders(): List<VenueOrder>
    fun place(order: NewOrder): VenueOrder             // order carries its client_order_id as the venue label
    fun cancel(clientOrderId: String): VenueOrder?
    fun modify(clientOrderId: String, change: OrderChange): VenueOrder?
    fun orderByLabel(clientOrderId: String): VenueOrder?   // venue lookup by our id; null = venue never saw it
    fun fills(fromMs: Long, toMs: Long): List<VenueFill>   // executions with venue fill ids and costs
    fun settlements(fromMs: Long, toMs: Long): List<VenueSettlement>
    fun subscribeQuotes(codes: Set<String>, roots: Set<String>)  // pushes arrive on the listener
}

interface AdapterListener {
    fun order(o: VenueOrder); fun fill(f: VenueFill); fun settlement(s: VenueSettlement)
    fun quote(q: VenueQuote); fun connection(up: Boolean, reason: String)
}
```

Rules every adapter keeps, checked by the conformance suite (§9):
- **Our id travels with the order.** `place` puts `client_order_id` in the venue's label/client-id field
  so `orderByLabel` can find it after any crash. A venue without such a field cannot be adapted.
- **Fills carry the venue's own execution id**, so a fill seen twice (push and backfill) is one fill.
- **Pushes may arrive late, twice or out of order;** the host orders and dedupes them. An adapter never
  drops a push it cannot classify; it reports it as an error.
- **No threads, clocks, env or HTTP clients of its own:** the host hands them in (`HostServices`, as the
  research doc §5.4), so adapters are testable and secrets stay in one place.

## 4. The event journal (stream, seq, replay)

The journal is the gateway's memory and the source of the event stream.

- **Storage:** SQLite in WAL mode under the state directory, one file per account, written with
  `BEGIN IMMEDIATE` (the shared-DB locking lesson from the research DB). Tables: `meta` (stream id,
  created at), `events` (seq INTEGER PRIMARY KEY, type, time, data JSON), `orders` (client_order_id
  PRIMARY KEY, body hash, status, venue order id, last order JSON), `fills` (venue fill id PRIMARY KEY),
  `settlements` (symbol, time PRIMARY KEY), `dead_ids`.
- **Stream identity:** a random `stream` id is created with the journal. Losing the journal file means a
  new stream id, which tells every client to resynchronize from REST (`reset`), exactly as the wire spec
  defines.
- **Appending:** each order change, new fill or settlement is written in one transaction with its
  dedupe row (a fill whose venue id exists is not appended again), then published to open streams.
  `seq` increases by exactly one per event.
- **Replay:** `GET /v1/stream?since=<seq>` first sends every retained event with `seq > since`, then
  live ones. Retention keeps at least 7 days; a `since` older than the oldest retained event, or from
  another stream, gets a `reset` first.
- **Health anchor:** `GET /v1/health` reads `stream` and the latest `seq` in the same transaction the
  client's REST reconcile follows, so nothing falls between them.

## 5. Orders: idempotency, by-id lookup, dead ids

- **POST /v1/orders** with a known `client_order_id` and the same body hash returns the stored order;
  a different body is `409 conflict`; an id in `dead_ids` is `409` for ever. A new id is first written
  to `orders` as `pending` (the write-ahead record), then sent to the adapter, then updated.
- **A crash between the write-ahead record and the venue's answer** is resolved on restart by
  `orderByLabel`: found means the venue has it (journal its state and fills); not found after the venue
  link is up means it was never placed (mark it rejected, journal the event).
- **GET /v1/orders/{id}:** the stored order, or the venue's by label when the journal lost it; `404`
  only when neither knows it, and that id is then written to `dead_ids` so a late POST of it can never
  place an order (the client's resolve-by-id relies on this).
- **503** while the venue link is down: reads are served from the journal where they can be, submits
  are refused before the write-ahead record so a retry is clean.

## 6. Reconciliation with the venue

Runs at start, after every venue reconnect, and every 60 seconds:
1. Venue open orders vs journal open orders: a journal order the venue no longer lists is looked up by
   label and its final state and fills journaled.
2. Venue fills since the last reconciled fill time (minus a 5-minute overlap) are journaled through the
   fill dedupe, so a push lost during a disconnect becomes an event late but exactly once.
3. Venue settlements since the last reconciled settlement are journaled the same way.
4. Positions are not journaled; they are served live from the venue (the client checks holdings).

## 7. Kill switch

- Scopes: `all`, or a set of symbols, held in the journal (`meta`), so a restart keeps the switch.
- `POST /v1/kill` and `POST /v1/kill/release` (operator token or guardrails token).
- While a scope covers a symbol, `POST /v1/orders` and `PATCH` are `423 kill_switch` unless the order is
  `reduce_only` and reduces the account's position at the venue (checked against live positions);
  cancels always pass. Health reports the switch.
- The switch is enforced in the gateway, never in qkt: the guardian can flip it while qkt is down.

## 8. Quotes

- One quote hub per process: it merges every client's `GET /v1/quotes` subscription into one adapter
  subscription (codes plus roots; a root expands to every listed option of it, including ones listed
  later, by refreshing the instrument list every 10 minutes).
- Each subscribed quote is pushed on change and **re-sent with `time` advanced at least every 5 seconds
  while it holds** (wire spec §4a), so a quiet book stays fresh and a stopped venue feed goes stale.
- Quotes are never journaled and never replayed.

## 9. Testing

- **Conformance suite** (black-box, against a URL): every statement of the wire spec as a test, run in
  CI against `qkt-venued` with the paper adapter, and runnable against any VGP gateway. qkt's own
  client tests keep their `FakeGateway`; both are built from the one spec.
- **Adapter contract tests:** each adapter against recorded venue sessions (request/response fixtures),
  plus a testnet soak (Deribit testnet) before it may serve a `real` account.
- **Chaos tests:** kill the process between the write-ahead record and the venue answer; drop the venue
  socket mid-fill; restart with a deleted journal; each must end with every order and fill exactly once
  at the client.

## 10. Adapters, in order

1. **paper** — a venue-free adapter for forward testing through the real gateway path: it takes quotes
   from another adapter's public data (Deribit public feed, no account), fills market orders at the
   touch and limits when the opposite side reaches them, keeps positions and settles expiries at the
   venue's published delivery price. It runs the conformance suite in CI.
2. **deribit** — USDC-linear options and futures first (qkt models linear contracts), over Deribit's API
   v2 (JSON-RPC over WebSocket). The venue calls it will use, each to be verified against Deribit's
   current API documentation and testnet before code: `public/auth` (client credentials),
   `public/get_instruments`, `private/get_account_summary`, `private/get_positions`, `private/buy` /
   `private/sell` with `label`, `private/cancel_by_label`, `private/get_order_state_by_label`,
   `private/get_open_orders_by_currency`, `private/get_user_trades_by_currency`,
   `private/get_settlement_history_by_currency`, and subscriptions to the user's orders and trades and
   to instrument tickers. Testnet is `test.deribit.com` (`trade_mode: demo`).
3. Later: a futures venue for CME products (Rithmic or a bridge, see the prop-automation findings), and
   `mt5-gateway` speaking VGP so MT5 accounts share the same client.

## 11. Deployment and operations

- One container per account, configured by a file (venue, adapter settings, secret references, state
  directory, listen address, tokens). Secrets are `env:` or `file:` references, never inline.
- TLS terminates at the reverse proxy already in front of the fleet; the gateway listens on a private
  address. Tokens: one for qkt, one for guardrails (kill switch), one read-only for insights.
- Logs are structured; health is the readiness probe; a venue link down for longer than 2 minutes alerts.

## 12. Decisions for the reviewer

1. **Language and server:** Kotlin on the JVM with Ktor (one toolchain with qkt and the adapters' SPI),
   or Python (matching `mt5-gateway`). This design assumes Kotlin/Ktor.
2. **Journal store:** SQLite (proposed) or an append-only file per day.
3. **First venue:** Deribit USDC-linear (proposed), matching the options data qkt already backtests.
4. **Paper adapter as the CI venue** (proposed) instead of mocking the venue in the host's tests.
