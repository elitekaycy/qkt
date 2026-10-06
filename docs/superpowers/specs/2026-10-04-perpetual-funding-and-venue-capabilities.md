# Perpetual funding and venue capabilities — design

**Status:** design for #1294 (funding in P&L) and the foundation of #1295 (venue capabilities). Amends
`2026-10-01-vgp-v1-wire.md` (additive; the gateway's copy follows it).

## 1. Problem

A perpetual future never expires. Its price is held near the underlying by **funding**: a cash flow
between longs and shorts that the venue charges or credits while a position is held. qkt trades
perpetuals in backtests and live on a gateway account, but books no funding in either, so a perpetual
held for days drifts from the venue's balance by the cumulative funding, silently.

Venues differ in how funding works, and qkt must not know which venue it is on:

| | interval | what the venue reports to an account |
|---|---|---|
| Deribit | accrues continuously, published as an hourly rate | realized funding per instrument in its transaction log |
| Binance USDⓈ-M | every 8 h (some symbols 4 h or 1 h) | one income record per payment |
| Bybit | every 8 h (some 1–4 h) | one funding execution per payment |
| CME and other dated-only venues | none (no perpetuals) | nothing |

## 2. The rule: venue-neutral, declared, replayable

1. **Adapters declare capabilities.** A gateway reports the optional services its adapter serves in
   `/v1/health` `capabilities`. A capability not declared is not served: its endpoint answers
   `501 unsupported`, and the host never asks the adapter for it.
2. **Missing capability is a loud refusal, never a silent zero.** qkt refuses to trade a perpetual
   through a gateway that does not declare `funding`, and a backtest refuses to hold a perpetual
   without a funding series covering its window (unless `--funding off` says so, which the report
   records).
3. **One shape per kind of data.** Funding reaches qkt as one record however the venue computes it;
   venue words stay in the adapter.
4. **Live and backtest read the same kind of data.** The rate history a backtest replays is served
   by the same gateway (`/v1/funding-rates`) or a public source, stored once under `data_root`.
5. **The contract kit proves an adapter.** `AdapterContractTest` checks that every declared
   capability answers in shape and every undeclared one is refused as unsupported.

## 3. Wire additions (VGP v1, additive)

- `/v1/health` gains `"capabilities": ["bars", "quotes", "settlements", "funding", "funding_rates"]`.
  Absent means a gateway that predates capabilities: the client treats it as none declared.
- Error `501 unsupported`: the adapter does not serve what was asked.
- Event `funding`:
  `{"funding_id", "symbol", "amount", "currency", "position", "time"}`. `amount` is positive when the
  venue charged the account and negative when it credited it (the sign of `costs[].amount`).
  `position` is the account's signed quantity the venue charged on (null when the venue does not say).
  `funding_id` is unique per venue record, so a record seen twice is one record.
- `GET /v1/funding?from=<ms>&to=<ms>` → `{"funding": [<Funding>]}`, oldest first, from the journal.
- `GET /v1/funding-rates?symbol=<code>&from=<ms>&to=<ms>` → `{"rates": [{"time", "rate", "price"}],
  "next"}`: the venue's public funding history of one perpetual, at most 1000 per page. A unit long
  held through `time` pays `rate × price` per unit of the underlying (× `contract_size`); a negative
  rate pays the short. `price` is the price the venue applied (null when it publishes none).
- A client ignores an event type it does not know (logging it), so later additions need no new
  protocol. Clients from before this rule (qkt 0.55) stop on the first `funding` event: upgrade qkt
  with the gateway.

## 4. Gateway

- `adapter-api`: `Capability`, `VenueAdapter.capabilities` (default: bars, quotes, settlements, as
  every v1 adapter served), `funding(from, to)`, `fundingRates(code, from, to)` (default: refused as
  unsupported), `AdapterListener.funding`, `VenueFunding`, `VenueFundingRate`,
  `VenueUnsupportedException`.
- `host`: a `funding` table keyed by `funding_id`, its event, `/v1/funding`, `/v1/funding-rates`,
  health `capabilities`; the reconciler reads `funding()` since the newest journaled record (and
  `settlements()` only when declared).
- `deribit`: funding from the transaction log (`interest_pl` of perpetual rows), rates from
  `public/get_funding_rate_history` (hourly `interest_1h` at `index_price`). Settlements stay
  undeclared until a delivery is recorded.
- `paper`: charges each held perpetual at every hour boundary `quantity × price × rate` from Deribit's
  public history, and serves the same history as its rates.

## 5. qkt

- Live: `GatewaySession` checks `capabilities`; a perpetual order on a gateway without `funding` is
  refused naming the gateway. A `funding` record becomes `FundingCharged` for each attached session,
  its share being the session's holding over the record's `position` (by holding across sessions
  when the venue gives no position), so a foreign position on the same account keeps its own funding.
  The pipeline splits it across its strategies by holding and books it as `FINANCING`, as the
  backtest books swap and funding. A per-session cursor (time and ids) persists what was booked, so a
  restart replays `/v1/funding` since the cursor without booking anything twice; a first start books
  nothing from before it.
- Backtest: `qkt fetch <VENUE:PERP> --funding` stores the rate history in
  `funding/<VENUE>/<SYMBOL>.csv` (`time,rate,price`), from the account's gateway or, for
  `BINANCE_UM`, Binance's public funding API. The replay accrues each record in `(last, now]` on every
  strategy's legs of that perpetual: `−quantity × contract_size × price × rate` (the symbol's last
  price when `price` is empty), through the financing fold swap uses. `result.json` reports
  `fundingPaid` beside `swapPaid`; `pnl_components.csv` carries it in `adjustment`.
- Parity row A58 states what is exact and what is approximated.

## 6. Out of scope (tracked separately)

The rest of #1295 (book depth, mark/index and per-contract Greeks as rule inputs, open interest,
liquidations, aggressor side) each becomes its own capability later. The Bybit direct connector's
funding (outside VGP) was added by #1305: its `Funding` executions become the same `FundingCharged`.
