# Futures/options data pipeline — full mental model (Databento vs Deribit)

Date: 2026-10-04

## Thesis

For CFDs, one broker socket gives a synthesized price and that's the whole data story. For
exchange-native futures/options the exchange unbundles the problem into separate products —
**reference/definition data**, **market data** (book/quotes/trades), and **statistics**
(settlement/open interest) — that a vendor either re-packages (Databento) or that you consume
directly off the venue (Deribit, via `qkt-venue-gateway`). Backtest/live parity is not automatic:
it depends on whether your *historical* source and your *live* source are the same wire format
from the same pipeline, or two different products stitched together after the fact. This doc
builds the mental model end to end using one vendor-aggregated example (Databento, CME/OPRA) and
one exchange-native example (Deribit, already proven live in `qkt-venue-gateway`), then states
where the parity risk actually lives in each.

Builds on [2026-09-30-futures-data-vs-execution-flow.md](2026-09-30-futures-data-vs-execution-flow.md)
(qkt's data/execution split) and [2026-10-01-deribit-options-free-data.md](2026-10-01-deribit-options-free-data.md)
(qkt's actual Deribit fetcher findings).

## 1. The five data products every futures/options venue exposes

These are separate concerns even when one vendor bundles them under one API key and one SDK call
style. Confusing them is the main source of "where do I even get X" gaps.

1. **Reference / instrument definition** — static-ish, not a tick feed. What contracts exist:
   tick size, contract multiplier, expiry/strike grid, settlement currency, trading hours, state
   (open/halted/expired). You pull this *before* you can subscribe to anything, and you re-pull it
   periodically because contracts get added/delisted/modified (new expiries list weekly for
   crypto options, quarterly for index futures).
2. **Order book / quotes** — live (or replayed) order-level or price-level book state. Comes in
   depths: full order-by-order (L3/MBO), top-of-book (L1), N-level aggregated (L2, "MBP-10" /
   "grouped book"). This is the thing that changes every message.
3. **Trades / time & sales** — actual executed prints, separate channel/schema from the book even
   though both update constantly. A trade does not necessarily move the book by the same tuple —
   they're reported independently and you reconcile them by timestamp, not by assuming one implies
   the other.
4. **Statistics (settlement, open interest, limits)** — end-of-day or event-driven, **not** part of
   the tick stream. Daily settlement price is a *computed/published* value (often a VWAP in a
   settlement window or a committee figure), not just "last trade of the day." This is what margin
   and MTM are computed against — conflating it with "last tick" is a real backtest-fidelity bug.
5. **Derived analytics (greeks/IV)** — only relevant for options. Some venues compute and publish
   this themselves (Deribit does, directly in the ticker message); vendors that aggregate raw
   exchange data (Databento/OPRA) do not — you compute greeks yourself from the raw quote + a
   pricing model.

Options add one more wrinkle on top of all five: **every strike × expiry is its own instrument**,
so "subscribe to the options chain" is really "enumerate N instruments via reference data, then
subscribe to N separate quote/trade streams" — unless the vendor offers a chain-level convenience
subscription that expands this server-side (Databento's parent symbology does; Deribit's
`get_instruments` per-currency call effectively does the same for reference data, though book/ticker
subscriptions are still per-instrument).

## 2. How the pieces compose into "one rich backtest-capable source"

The pipeline you actually want looks like this, regardless of vendor:

```
reference/definition  ──>  gives you the instrument_id universe (which strikes/expiries exist, when)
        │
        ▼
quotes/book stream     ──>  gives you the tradable price at any instant (what you'd have filled at)
        │
        ▼
trades stream          ──>  gives you realized prints (what actually happened — validates your fill model)
        │
        ▼
statistics stream      ──>  gives you settlement/OI (what MTM and margin are computed against)
        │
        ▼
(options only) greeks  ──>  either pulled precomputed (Deribit) or computed from book + model (Databento)
```

A backtest that only replays trades (no book) cannot model slippage or spread cost honestly — it
can only replay what prints happened, with an assumed spread. A backtest that only replays OHLCV
bars cannot see intrabar book state at all. **The depth of backtest fidelity you can claim is
capped by the shallowest of these five products you actually captured historically** — this is
exactly the shape of the gap `2026-10-01-deribit-options-free-data.md` found empirically: Deribit's
own historical trade history is sparse (marks only when an instrument trades — hours-old marks are
normal), so a chain snapshot built from trades alone needs a declared staleness cutoff and a spread
model around the mark, because true bid/ask history isn't in the trade tape at all.

**The parity question, precisely stated**: "do I get the same data in backtest as live" really
means — for each of the five products above, is the *historical* copy and the *live* copy the same
schema, same field semantics, same capture pipeline? If yes (Databento's documented design intent),
your backtest code can run unmodified against the live feed. If no (Deribit: live WS gives L2 book
deltas + full ticker with greeks; Deribit's own historical REST gives only OHLCV candles,
settlement records, and paginated trades — **no historical L2 book endpoint exists on Deribit at
all**), you need a third party that captured the *actual wire messages* historically, or you accept
a lower-fidelity backtest than what your live gateway actually sees.

## 3. Databento — vendor-aggregated, CME futures + OPRA options

Databento normalizes multiple exchanges' native feeds into one binary format (DBN) and serves it
identically whether you're pulling history or streaming live.

### Reference data
Schema name `definition`, delivered as a normal schema (not a separate metadata-only call) — one
record per instrument, same request mechanism as any tick schema. Key fields (verbatim from the
`databento/dbn` source, not paraphrased): `min_price_increment` (tick size), `contract_multiplier`,
`expiration`, `strike_price`, `instrument_class` (`F`=Future, `C`=Call, `P`=Put, `S`=FutureSpread,
`T`=OptionSpread…), `raw_symbol`, `underlying`, `security_update_action` (`A`/`M`/`D` — add/modify/
delete, i.e. how listings/delistings are communicated point-in-time).

Options chain enumeration uses **parent symbology**: subscribing to `stype_in="parent"`,
symbol `ES.OPT` or `TSLA.OPT` expands server-side to every current strike/expiry/spread under that
root — you don't hand-enumerate instrument_ids yourself for live use; for historical backfill you
still pull the `definition` schema over your date range to get the strike/expiry grid as it existed
at each point in time.

### Market data schemas (all share one `RecordHeader {rtype, publisher_id, instrument_id, ts_event}`)

| Schema | What it is | Shape |
|---|---|---|
| `mbo` | full order-by-order (L3) | `{order_id, price, size, action, side, ts_recv, sequence}` per order event |
| `mbp-1` / `trades` | top-of-book + prints | `Mbp1Msg`; `TradeMsg` is literally a type-alias of `Mbp1Msg` — same wire shape, different schema selector |
| `mbp-10` | 10-level book | same shape, `levels: [BidAskPair; 10]`, each level carries `bid_ct`/`ask_ct` (order count, not just price/size) |
| `ohlcv-1s/1m/1h/1d` | bars | `{open, high, low, close, volume}` — Databento computes these from its own normalized tick stream (derived, not exchange pass-through) |
| `statistics` | settlement/OI/limits | one `StatMsg` struct, disambiguated by `stat_type` enum: `SettlementPrice=3`, `OpenInterest=9`, `Vwap=13`, etc. — **venue-sourced pass-through, not Databento-computed**, confirming settlement is a distinct message type from trades, not "last trade of the day" |
| `imbalance` | auction imbalance | exists, full field list not independently re-verified |
| `status` | trading halts/state | `{action, reason, trading_event, is_trading, is_quoting, is_short_sell_restricted}` |

Representative `definition` record is schema-verified from source but I could not get a verbatim
JSON response body from the docs (fetch truncation) — treat the field *names* above as ground
truth, and the exact JSON rendering as something to pull directly from a live `databento` Python
session before coding against it.

### Encoding/transport
**DBN (Databento Binary Encoding)** is the one native wire format across historical batch
download, historical streaming, and live streaming — not three different formats. CSV/JSON are
export conveniences re-rendered from the same DBN records and are billed identically to binary
(confirms they're not a separate product, just a view). The Live API uses a raw low-latency TCP
binary protocol carrying DBN records directly, authenticated via a CRAM challenge-response
handshake — not WebSocket/protobuf/msgpack in the current production path (a WebSocket+JSON
transport is a roadmap item per their docs, not confirmed shipped).

### Backtest/live parity
This is Databento's explicit product design, not incidental: "the live API uses the same
interfaces and data structures as historical market replay" — same DBN schemas, same struct shapes,
so a backtest written against `Mbp1Msg`/`TradeMsg` records runs unmodified against the live stream
for the same instrument. Documented caveats worth carrying into any ingestion code:
- `ts_event` (matching-engine time) vs `ts_recv` (Databento capture-server receipt time) are both
  present on every record; downstream integrations (e.g. NautilusTrader) prefer `ts_recv` as the
  monotonic one.
- Every MBO/MBP record carries explicit data-quality flags: `BAD_TS_RECV` (clock/reorder issue) and
  `MAYBE_BAD_BOOK` (unrecoverable channel gap detected) — gaps are surfaced in-band, not silently
  dropped.
- One consolidated dataset (`EQUS.MINI`) has a documented live-only `ts_recv` backward-jump issue
  from cross-feed jitter, explicitly **not** present in historical data for that dataset — i.e. even
  within one vendor, "same schema" doesn't always mean "zero live-only anomalies."
- Trade-bust/correction message semantics were not confirmed from primary docs — don't assume a
  specific mechanism without checking directly.

### Options greeks/IV
Not sent. Databento gives raw OPRA quotes/trades only; computing IV/greeks (e.g. Black-76) is left
to the consumer — they publish a tutorial for exactly this.

### Cost (snapshot, 2026-10-04 pricing page — expect drift)
Historical: pay-as-you-go, billed per GB of **uncompressed DBN size** regardless of output encoding
(CSV/JSON priced the same as binary); $125 free credit for new accounts. Live: bundled into
subscription tiers — Standard $199/mo (16+ yrs L0 history, 1 yr L1, 1 mo L2/L3, live included), Plus
$1,750/mo (annual, 16+ yrs L1, external distribution rights), Unlimited $4,500/mo (annual, 16+ yrs
all schemas).

### Venue coverage
Futures: CME Globex, dataset `GLBX.MDP3`. Options: OPRA (`OPRA.PILLAR`), consolidated across all 17
US options exchanges. Plus 40+ other venues per their marketing page (Cboe, IEX, MIAX, Eurex, ICE…),
not independently itemized here.

## 4. Deribit — exchange-native, crypto futures + options (already proven in qkt-venue-gateway)

No vendor layer: you talk to the exchange's own JSON-RPC 2.0 API directly, over WebSocket.

### Reference data
`public/get_instruments` — **one call per currency** returns the *entire* chain (all futures +
every strike/expiry of every option) in one response array; not paginated per expiry. Cached ~1
minute server-side, so it's a discovery call, not a live-state source. Verbatim option example:

```json
{
  "instrument_name": "BTC-13JAN23-16000-P",
  "kind": "option", "strike": 16000, "option_type": "put",
  "settlement_currency": "BTC", "settlement_period": "week",
  "expiration_timestamp": 1673596800000,
  "tick_size": 0.0005, "contract_size": 1,
  "is_active": true, "state": "open"
}
```

`get_instruments` is rate-limited to effectively ~1 req/s sustained (10,000-credit cost against a
10,000-credit/sec refill pool) — confirms it as a discovery/periodic-refresh call, not something to
poll per strike.

### Market data channels

- **`ticker.{instrument}.{interval}`** — best bid/ask, mark/index price, open interest, and for
  options: `bid_iv`/`ask_iv`/`mark_iv` **and a full `greeks` object (delta/gamma/rho/theta/vega)
  precomputed by Deribit** — the exchange does the pricing-model work for you, unlike Databento.
  Perpetual-only fields: `current_funding`, `funding_8h`. Verbatim (BTC-PERPETUAL, not an option so
  no greeks shown):
  ```json
  {
    "best_ask_price": 36443, "best_bid_price": 36442.5,
    "current_funding": 0, "funding_8h": 0.0000211,
    "index_price": 36441.64, "mark_price": 36446.51,
    "open_interest": 502097590, "settlement_price": 36169.49,
    "instrument_name": "BTC-PERPETUAL", "state": "open",
    "timestamp": 1623060194301
  }
  ```
- **`book.{instrument}.{interval}`** — incremental L2: each message is `type: "snapshot"` (full
  book, first message) or `type: "change"` (delta only, carries `prev_change_id` that must chain to
  the previous `change_id` — a mismatch means a missed update and the client must resync). Entries
  are 3-tuples `[action, price, amount]` with `action ∈ {new, change, delete}`.
- **`book.{instrument}.{group}.{depth}.{interval}`** — grouped book, **not incremental**: every
  message is a full snapshot truncated to `depth` (1/10/20), price-grouped. Simpler to consume, less
  precise, no chaining needed.
- **`trades.{instrument}.{interval}`** — prints with `direction`, `tick_direction`, `iv` (options
  only), `liquidation` flag (`M`/`T`/`MT`). Verbatim:
  ```json
  {"trade_id": "48079270", "timestamp": 1590484513004, "price": 8960,
   "mark_price": 8948.9, "direction": "buy", "amount": 20, "contracts": 2,
   "liquidation": "T"}
  ```
- **`deribit_price_index.{name}`** — index price push, e.g. `{"price": 6521.17, "index_name":
  "btc_usd"}`.
- No dedicated settlement/delivery push channel was found — settlement surfaces via the `ticker`
  channel's `delivery_price`/`settlement_price` fields as state transitions, plus REST
  `get_delivery_prices` for history.

### REST historical endpoints — and the real gap
`get_tradingview_chart_data` (OHLCV bars), `get_last_trades_by_instrument[_and_time]` (capped at
1000 trades/call — bulk backfill means paginating by `trade_seq`), `get_delivery_prices` (one
settlement record per event, not tick granularity), `get_funding_rate_history`. **There is no
historical order-book endpoint on Deribit at all** — `get_order_book` is a live snapshot only, "as
of now," never "as of time T." This matches what `2026-10-01-deribit-options-free-data.md` already
found empirically from the qkt fetcher: trade-tape history exists and is usable, but sparse (a mark
only when an instrument trades — median mark age of 7 hours was observed on live BTC_USDC chain
snapshots), and 21 of 1,228 live rows had the mark price sitting *outside* the live bid/ask — i.e.
even the "official" mark isn't always inside the book, which the qkt option fill model has to
handle explicitly rather than trusting the mark blindly.

### The parity problem, concretely
Live WS gives you L2 deltas + full greeks-bearing ticker + liquidation-flagged trades. Deribit's own
historical REST gives you candles + settlement records + funding history + (with pagination effort)
trade prints — **no book history whatsoever**. So "backtest vs live, both from Deribit" is actually
comparing candles/trades against full order book — not the same data shape, independent of vendor
quality. To get book-depth-accurate historical replay that matches what the live gateway actually
saw, you need a third party capturing Deribit's raw wire messages — Tardis.dev has an explicit
partnership with Deribit and stores tick-by-tick trades, L2 snapshots+increments, funding, OI, and
index data back to 2019-03-30 (free first-day-of-month samples). This narrows the gap versus a
fully independent re-normalizer, since Tardis captures the same channels rather than re-deriving
them, but it's still a separate pipeline with its own gap/outage handling — not guaranteed
byte-identical to what your own WS session saw at a given moment.

### Encoding
Plain **JSON-RPC 2.0 over WebSocket, UTF-8 text frames** — no binary/protobuf anywhere. Rate limits
are credit-based (50,000 credit bucket, 10,000 credit/sec refill, `get_instruments` costs 10,000,
`subscribe` costs 3,000) — using WS subscriptions instead of polling REST is the documented way to
stay under the budget; bulk historical backfill via `get_last_trades_by_instrument` across many
option strikes will bottleneck against this pool and needs to run slowly or spread across
sub-accounts.

### Cost
Deribit's own live API and most historical REST endpoints (candles, trades, settlement, funding)
are free/public, no API key required for `public/*` calls — this is why
`2026-10-01-deribit-options-free-data.md` could build a real catalog anonymously. The cost shows up
only if you need L2 book history, which means paying a third party (Tardis) rather than Deribit
itself.

## 5. Side-by-side

| | Databento (CME/OPRA) | Deribit (native) |
|---|---|---|
| Who normalizes the data | Databento (vendor) | nobody — it's the exchange's own wire format |
| Historical transport | DBN (binary), same schemas as live | REST JSON, different shape than live WS |
| Live transport | raw binary over TCP, DBN records | JSON-RPC 2.0 over WebSocket, text frames |
| Backtest/live parity | explicit product design — same struct, same code | **not possible beyond OHLCV/trades** without a 3rd-party capture (Tardis) |
| Options greeks/IV | not provided — compute yourself | provided precomputed in the ticker |
| Historical L2 book | yes (MBP-10/MBO) | **no** — live snapshot only, no history |
| Cost | metered per GB + subscription tiers ($199–$4,500/mo) | public REST free; book-history needs Tardis (paid) |
| Settlement price | distinct `statistics` schema, venue-sourced | `ticker.settlement_price`/`delivery_price` fields + `get_delivery_prices` REST, no push channel found |

## 6. What this means for qkt / qkt-venue-gateway today

- The crypto path (Deribit, via `qkt-venue-gateway`) is live-proven but its backtest fidelity is
  capped at "trades + candles + settlement," never true book depth, unless Tardis is integrated —
  this is a gap worth flagging explicitly in any options-backtest claim, per the "claims about
  parity, fidelity, or production readiness need linked tests or should be explicitly marked
  unproven" rule in `qkt/AGENTS.md`.
- The traditional-futures path (CME via Databento or similar) doesn't exist in qkt yet;
  `2026-09-30-futures-data-vs-execution-flow.md` already flagged that `InstrumentMeta` lacks
  multiplier/expiry/first-last-trade-date fields needed before this is even wireable, and that MT5
  gateway plumbing doesn't extend to native CME — a new `Broker`+`MarketSource` pair (same shape as
  the Deribit VGP adapter) would be required.
- If a CME/OPRA integration is ever built, Databento's documented backtest/live parity (same DBN
  schema both ends) is the stronger guarantee of the two vendors studied here — worth preferring
  over a "vendor A for history, vendor B for live" split if cost allows.

## Open gaps (explicitly unverified — confirm before relying on these)

- Databento: exact JSON response body for a `definition` record (only the struct field names are
  source-verified); full `imbalance` schema field list; exact trade-bust/correction representation;
  whether WebSocket+JSON live transport has actually shipped or is still roadmap.
- Deribit: verbatim options-ticker JSON with populated greeks/IV; `perpetual.*` channel's exact
  field list; confirmation that no settlement *push* channel exists at all (only checked one docs
  page); hard historical depth limit (if any) on `get_tradingview_chart_data`; WS message-size
  limits.

## Sources

- https://github.com/databento/dbn (record/schema structs, `enums.rs`, `record.rs`)
- https://databento.com/pricing
- https://databento.com/live
- https://docs.databento.com (symbology/parent-subscription docs, schema overview — several pages
  truncated on fetch, see inline flags above)
- https://docs.deribit.com/api-reference/market-data/public-get_instruments.md
- https://docs.deribit.com/api-reference/market-data/public-get_instrument.md
- https://docs.deribit.com/subscriptions/market-data/tickerinstrument_nameinterval
- https://docs.deribit.com/subscriptions/orderbook/
- https://docs.deribit.com/subscriptions/trades/tradesinstrument_nameinterval
- https://docs.deribit.com/articles/json-rpc-overview.md
- https://docs.deribit.com/articles/rate-limits.md
- https://insights.deribit.com/exchange-updates/celebrating-our-tardis-dev-partnership-get-free-historical-data/
- https://docs.tardis.dev/historical-data-details/deribit
- [2026-09-30-futures-data-vs-execution-flow.md](2026-09-30-futures-data-vs-execution-flow.md)
- [2026-10-01-deribit-options-free-data.md](2026-10-01-deribit-options-free-data.md)
