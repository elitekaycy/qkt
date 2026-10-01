# VGP v1 wire format — design

**Status:** design for phase 44 (amends `2026-09-30-futures-options-design.md` §7). The endpoint list
and semantics come from `docs/research/2026-09-18-venue-plugin-architecture.md` §6; this document
fixes what that list left to implementations: the JSON, the event stream, errors, resume and the
kill switch. The qkt client (`connector/gateway`) and any gateway (`qkt-venue-gateway`) implement exactly
this; the client's fake gateway in tests is built from it.

## 1. Conventions

- **Transport:** HTTPS (HTTP in tests) under a base URL; every path starts `/v1/`. JSON bodies,
  UTF-8, `Content-Type: application/json`.
- **Auth:** `Authorization: Bearer <token>` on every request, the stream included. A missing or
  wrong token is `401 unauthorized`.
- **Money and quantities are decimal strings** (`"0.10"`, `"84042.83"`), never JSON numbers: qkt
  books exact `BigDecimal`, and a float on the wire would round. Integers (sequence numbers, times)
  are JSON numbers.
- **Time:** UTC epoch milliseconds, JSON numbers. Broker-time offsets are the gateway's concern.
- **Symbols** are the gateway's venue codes (`BTC_USDC-25DEC26-92000-C`, `BTCUSDT_241227`); the
  client maps them to qkt symbols with the account's `symbolPrefix` (`DERIBIT:` + code, `-` written
  `_`, as `OptionSymbols` does).
- **Enums** are lowercase strings. Unknown fields are ignored by both sides; an unknown enum value
  is an error the receiver reports, never a guess.

## 2. Errors

Every non-2xx response has the body `{"error": {"code": "<code>", "message": "<text>"}}`.

| HTTP | code | Meaning |
|---|---|---|
| 400 | `invalid_request` | malformed body or field; never retried |
| 401 | `unauthorized` | bad or missing token |
| 404 | `not_found` | no such order, instrument or root |
| 409 | `conflict` | `client_order_id` reused with a different body |
| 422 | `venue_rejected` | the venue refused the request; `message` is its reason |
| 423 | `kill_switch` | the request adds or changes risk while a kill scope covers its symbol |
| 503 | `venue_unavailable` | the gateway cannot reach the venue; safe to retry reads, and submits (idempotent on `client_order_id`) |

## 3. Endpoints

### `GET /v1/health`
```json
{"protocol": "vgp1", "adapter": "deribit", "adapter_version": "0.1.0",
 "account_login": "12345", "trade_mode": "demo", "venue_connected": true,
 "kill_switch": {"all": false, "symbols": []}, "server_time": 1790835316271,
 "stream": "4f1c...", "seq": 1041}
```
`stream` and `seq` are the event log's identity and its latest sequence number: a client that reads
them before reconciling from REST, then opens the stream with `since=<seq>`, loses nothing that
happened in between.
`trade_mode` is `demo` or `real`. The client checks `protocol`, `adapter`, `account_login` and
`trade_mode` against the account's `expected_*` settings before trading.

### `GET /v1/account`
```json
{"currency": "USDC", "balance": "10000", "equity": "10012.5", "margin_used": "265",
 "margin_available": "9747.5", "initial_margin": "265", "maintenance_margin": "190"}
```

### `GET /v1/instruments` and `GET /v1/instruments/{code}`
A list (or one) of:
```json
{"code": "BTC_USDC-25DEC26-92000-C", "kind": "option", "currency": "USDC",
 "contract_size": "1", "tick_size": "5", "volume_step": "0.01", "volume_min": "0.01",
 "expiry": 1798185600000, "strike": "92000", "right": "call", "underlying": "BTC_USDC"}
```
`kind` is `spot`, `perpetual`, `future` or `option`; `expiry` is absent for spot and perpetuals;
`strike`, `right` and `underlying` only for options.
`contract_size` is how many units of the underlying one unit of `quantity` is, the multiplier of
P&L (`quantity × contract_size × price change`); `volume_step` is the increment a quantity moves in and
`volume_min` the smallest. A venue that takes amounts in the base coin (Deribit's linear contracts) lists
`contract_size` 1 and its own contract size as `volume_step`.
A dated contract stays listed for at least **30 days after its expiry**, so a client restarting after
a weekend still maps the contracts it held to their settlements. (The qkt client also maps an unlisted
option by its name, which is exact; a dated future has no such rule.)

### `GET /v1/bars?symbol=<code>&window_ms=<ms>&from=<ms>&to=<ms>`
Closed bars of one instrument, oldest first, each `start` in `[from, to)` and aligned to UTC:
```json
{"bars": [{"start": 1790812800000, "open": "84010.5", "high": "84102", "low": "83990", "close": "84050.5",
  "volume": "12.31"}], "next": 1790872800000}
```
`window_ms` is a whole number of minutes that divides a day (`60000`, `300000`, `3600000`, `86400000`…).
A response holds at most 1000 bars; `next` is the `from` of the following page and is absent on the
last one. The gateway serves the venue's own klines where it has them (aggregated from 1-minute ones
for other windows), else builds them from the venue's trades; a window with no trade has no bar. The
client reads them for live warmup, and to measure a continuous stream's roll live by the same rule as
`qkt fetch --rolls` (each contract's last 1-minute close at or before the roll).

### Futures chains (not in v1)
A dated future carries no root on the wire (`underlying` is for options only), so v1 serves no futures
chain. The chain endpoint is designed with its first consumer, live continuous futures (qkt phase 46),
together with how a future names its root.

### `GET /v1/positions`
`{"accounting": "netting", "positions": [<Position>]}` with
```json
{"symbol": "BTC_USDC-25DEC26-92000-C", "quantity": "-0.1", "avg_price": "646",
 "ticket": null, "opened_at": 1790835377133}
```
`accounting` is `netting` (one signed position per symbol, `ticket` null) or `hedging` (one per
ticket).

### `GET /v1/orders`
`{"orders": [<Order>]}`, the working orders. An `Order` is:
```json
{"client_order_id": "dsl-s-1", "venue_order_id": "8812", "symbol": "BTC_USDC-25DEC26-92000-C",
 "side": "sell", "type": "limit", "quantity": "0.1", "limit_price": "650", "stop_price": null,
 "time_in_force": "gtc", "reduce_only": false, "status": "working", "filled_quantity": "0",
 "avg_fill_price": null, "reject_reason": null, "created_at": 1790835316271, "updated_at": 1790835316271}
```
`type`: `market`, `limit`, `stop`, `stop_limit`. `time_in_force`: `gtc`, `ioc`, `fok`, `day`.
`status`: `working`, `filled`, `cancelled`, `rejected`. `filled_quantity` is cumulative.

### `GET /v1/orders/{client_order_id}`
The `Order` in whatever state it is now (`working`, `filled`, `cancelled`, `rejected`), or `404
not_found` when the gateway never placed it. After answering `404` for an id, the gateway refuses
that id for ever (`409 conflict`), so a submit delayed in the network can never place an order the
client has already written off. The gateway keeps an order for at least 30 days after it
ends. A client resolves an order whose fate it missed (a submit it never heard back from, an order
that ended while it was away) by asking for it here.

### `POST /v1/orders`
Body: the `Order` fields a client sets (`client_order_id`, `symbol`, `side`, `type`, `quantity`,
`limit_price`, `stop_price`, `time_in_force`, `reduce_only`). Response `201` with the `Order`.

**Idempotent on `client_order_id`:** a second POST with the same id and the same body returns `200`
with the existing `Order` in its current state and never places a second order; the same id with a
different body is `409 conflict`. A client that saw a timeout resubmits the same body.

A `client_order_id` is at most 64 characters (it travels in the venue's order label) and the gateway
remembers it for ever, so a client never sends one twice for different orders. The qkt client sends
its engine order id with the order's submit time appended (`dsl-s--4.mg8q2kv1`): an engine may hand
an id out again after a restart, the submit time never repeats, and a restored order's id is rebuilt
from its persisted request.

### `PATCH /v1/orders/{client_order_id}`
Body: any of `quantity`, `limit_price`, `stop_price`. Response `200` with the `Order`. Gated by the
kill switch unless it only reduces `quantity`.

### `DELETE /v1/orders/{client_order_id}`
Response `200` with the `Order` (`cancelled`, or `filled` if the fill won the race: the client keeps
the fill). Never gated.

### `POST /v1/positions/close`
Body `{"symbol": "<code>", "quantity": "<decimal>"}` (quantity optional: the whole position) or
`{"ticket": "<ticket>"}`. Response `200` with the closing `Order`. Never gated: flatten is always
allowed.

### `GET /v1/deals?from=<ms>&to=<ms>` and `GET /v1/deals?client_order_id=<id>`
`{"deals": [<Fill>]}`, oldest first (§4 `fill`): the executions in the window, or every execution of
one order (complete for as long as the gateway keeps the order).

### `GET /v1/settlements?from=<ms>&to=<ms>` and `GET /v1/settlements?symbol=<code>`
`{"settlements": [<Settlement>]}`, oldest first (§4 `settlement`): the settlements in the window, or
the settlement of one contract (empty until it expires). A client reconciling after an outage reads
the ones it missed here; for contracts its strategies still hold, it asks by symbol.

### `POST /v1/kill`, `POST /v1/kill/release`
Body `{"scope": "all"}` or `{"scope": "symbols", "symbols": ["<code>", ...]}`. Response `200` with the
`kill_switch` object of `/v1/health`. While a scope covers a symbol, `POST /v1/orders` is
`423 kill_switch` unless it is `reduce_only: true` and no larger than the account's position on the
opposite side (checked by the gateway, whatever the venue enforces), and a `PATCH` that is not a pure
reduction is `423` too. Cancels and `positions/close` always pass.

## 4. Event stream: `GET /v1/stream?since=<seq>` (WebSocket)

Each message is one event:
```json
{"stream": "4f1c...", "seq": 1042, "type": "fill", "time": 1790835377133, "data": {...}}
```

- `stream` identifies the gateway's event log; it changes when that log restarts (and `seq` with it).
- `seq` increases by exactly 1 per event within a `stream`.
- `since` is **exclusive**: the gateway first replays every retained event with `seq > since`, in
  order, then streams live. With no `since`, it streams live only.
- If `since` is older than the oldest retained event, or the client's `stream` is not the current
  one, the gateway sends a single `{"type": "reset", "stream": "<current>", "seq": <latest>}` and
  then streams live from there. The client must then resynchronize from REST (§5).

Event `type` and `data`:

| type | data |
|---|---|
| `order` | an `Order` (§3): every status change |
| `fill` | `{"client_order_id", "venue_order_id", "fill_id", "symbol", "side", "quantity", "price", "time", "costs": [{"kind", "amount", "currency"}]}` |
| `settlement` | `{"symbol", "price", "time", "costs": [{"kind", "amount", "currency"}]}` |
| `position` | a `Position` (§3) after a change, `quantity` `"0"` when flat |
| `account` | the `/v1/account` object |
| `kill` | the `kill_switch` object |

- `fill.quantity` is that fill's quantity; an order's cumulative quantity is in its `order` event.
- `fill_id` is unique per venue execution, so a replayed fill is recognized.
- `costs[].kind` is `commission`, `exchange_fee`, `delivery_fee`, `funding` or `swap`; an adapter
  that cannot report a cost omits it rather than reporting zero. `costs[].amount` is positive when the
  venue charged the account and negative when it credited it (a maker rebate).
- A `fill` is always an execution of an order the client sent. Expiry is not a fill: when a contract
  the account holds or has traded expires, the gateway sends one `settlement` with the price every
  holder settles at (an option's intrinsic value, a future's delivery price) and the costs the venue
  charged the account. It is sent even when the account's net position is zero, so a client that
  runs several strategies on one account settles each one's own holding.
- v1 defines no liquidation event (qkt has no liquidation semantics yet).

## 4a. Quotes: `GET /v1/quotes?symbols=<code>,<code>&roots=<root>,<root>` (WebSocket)

Market data, apart from the event log: quotes are latest-wins and high volume, so they carry no
`seq`, are never replayed, and a reconnect simply subscribes again. `roots` subscribes every listed
option of a root (an instrument's `underlying`), including options listed after the subscription; a
code subscribed both ways is sent once. Each message is one quote:
```json
{"symbol": "BTC_USDC-25DEC26-92000-C", "bid": "640", "ask": "655", "bid_size": "1.2", "ask_size": "0.8",
 "mark": "648.5", "mark_iv": "52.3", "underlying": "84437.55", "time": 1790835377133}
```
Any field but `symbol` and `time` may be null (no bid, no mark yet). `mark_iv` is in volatility points.

`time` is the instant the gateway last knew the quote to hold, not when it last changed. While a
subscribed quote holds, the gateway sends it again with `time` advanced at least every **5 seconds**:
venues push options only when their book changes, and a quiet book must stay distinguishable from a
stopped feed. The client's market-data gate judges each contract by these refreshes, so a contract
whose refreshes stop is stale and new orders on it wait.

## 5. Client behaviour

- **Start:** `GET /v1/health` (identity checks, and the stream's `stream`/`seq` as the anchor), then
  `/v1/account`, `/v1/positions`, `/v1/orders`, `/v1/deals` and `/v1/settlements` to reconcile, then
  open the stream with `since=<anchor seq>`; what REST already reported is recognized and dropped.
- **Reconnect:** reopen with `since=<last seq>`; events with `seq <=` the last processed one are
  dropped, and so is a `fill` whose `fill_id` was already booked.
- **Reset:** on a `reset` event, or a `stream` change, reconcile from `/v1/orders`, `/v1/positions`,
  `/v1/deals` and `/v1/settlements` since the last processed fill, book missing fills by `fill_id` and
  missing settlements by symbol and time, then continue from the reset's `seq`.
- **Submit:** POST once; on a timeout or `503`, resubmit the same body (idempotent); on `422` or
  `423` the order is rejected with the gateway's message.
- **Several strategies on one account:** one stream and one client per account. Fills are attributed
  by `client_order_id`; a `settlement` reaches every strategy, each settling its own holding, and its
  costs are shared once, by holding, across them.
- **Restart:** every order the client restores is resolved by id (`GET /v1/orders/{id}` and its
  deals); fills it booked before the restart are matched against its booked quantity, oldest first,
  in that complete history. Settlements of contracts still held are read by symbol.
- **Unanswered submit:** resend the same body until a deadline, then resolve it by id: `404` means
  it was never placed.
