# Order-book depth as a declared capability and stored series — design

**Status:** design for #1302, part of #1295, on the capability foundation of
`2026-10-04-perpetual-funding-and-venue-capabilities.md` and the pattern of `2026-10-04-open-interest.md`.
Amends `2026-10-01-vgp-v1-wire.md` (additive; the gateway's copy follows it).

## 1. Problem

No priced level beyond the best bid and ask reaches a rule, and none is stored. A strategy cannot ask whether
the book leans to one side, or whether enough rests on the offer to fill it. The issue was deferred until a
strategy needs it because the storage and replay cost is the highest of #1295's series; this is the smallest
version a strategy can use live and in a backtest.

## 2. What the venue offers (Deribit, measured 2026-10-05)

- `public/get_order_book?depth=10`: the book as it stands, `bids` and `asks` best first, each
  `[price, amount]` (amount in the base coin on a USDC-linear contract, the order quantity), stamped with
  `timestamp`; about 0.17 s a call from Europe. Prices may come in exponent form (`8.62e4`).
- `book.<instrument>.none.10.100ms`: the same snapshot pushed up to ten times a second (2.8 a second,
  534 bytes each, on testnet BTC_USDC-PERPETUAL).
- No history: `public/get_order_book_history` is `Method not found` on testnet and mainnet. No other venue
  qkt reaches publishes one either. CFD depth is broker-sliced and not served.

## 3. Decisions

1. **What a strategy reads is three numbers, not the book.** `<alias>.bid_depth` and `.ask_depth` are the
   quantity resting on the ten best levels of each side; `.book_imbalance` is
   `(bid − ask) / (bid + ask)`, from −1 to 1 (0 for an empty book). The wire and the store keep the ten
   levels, so a later field (depth within a price band, the issue's `depth_at`) needs no new data.
2. **Sampled, not streamed.** qkt reads each contract's book every 10 seconds, the three fields from one
   snapshot. A strategy evaluates on its bars, so a 10-second sample is enough to read the book at each close,
   and it keeps the book off the tick path entirely: no field on `Tick` or `Candle`, no per-update work.
3. **Recorded going forward, by the gateway, on read.** No venue has history, so the backtest needs what
   live saw. As for Deribit's open interest, a read whose window reaches the present takes the book and
   records it; the gateway serves what it recorded, and `qkt fetch --depth` stores it. A stored series
   therefore holds exactly the snapshots a live poll saw, at the venue's stamps. The Deribit adapter reads
   the book by REST at each read rather than holding the `book.` channel: a channel pushing up to ten
   snapshots a second would be conflated to one every 10 seconds anyway and would need its own staleness
   guard for a dropped socket.
4. **A rule reading depth runs on its contract's closes.** One snapshot ticks the bid, ask and imbalance
   streams one after another; a rule one of them triggered (a `LOG`, or a rule acting on no stream) would read
   one snapshot's bid beside the previous one's ask. `RuleTriggers` runs such a rule on the contract's stream
   instead, where all three are the same newest snapshot. A rule acting on a stream (`BUY perp`) already ran
   only on that stream's closes.

## 4. The rules (those of #1294)

1. **Declared:** a gateway adapter serves depth only by declaring `depth` in `/v1/health` `capabilities`;
   undeclared, `/v1/depth` answers `501 unsupported`, and `AdapterContractTest` checks both ways.
2. **Refused, never missing:** a strategy reading depth on an account whose gateway does not declare it fails
   at start, naming the account; a backtest without stored snapshots covering the run (the three-intervals
   rule of funding and open interest) is refused naming the fetch.
3. **One shape:** `{time, bids, asks}`, ten levels a side at most, on the wire and in the store, whatever
   the venue.
4. **Replayable:** `qkt fetch --depth` stores what live read under `data_root`; the backtest replays each
   snapshot from its `time` on.

## 5. Wire (VGP v1, additive)

- Capability `depth`.
- `GET /v1/depth?symbol=<code>&from=<ms>&to=<ms>` → `{"depth": [{"time", "bids": [[price, amount]], "asks"}],
  "next"}`, oldest first, each `time` in `[from, min(to, now)]`, at most 1000 snapshots over at most 1000
  minutes a page; `next` is the next page's `from`, absent on the last. Each side holds at most its best ten
  levels (the host cuts a longer one).
- A read whose window reaches the present (within a minute) first records the book as it stands.

## 6. Gateway

- `adapter-api`: `Capability.DEPTH`, `VenueAdapter.depth(code, from, to)` (default refused as unsupported),
  `VenueDepth(timeMs, bids, asks)` and `VenueLevel(price, amount)`.
- `host`: `/v1/depth`, paged as above (`DepthRoutes`); the testkit's `DepthChecks.depth` (declared: the
  present book at least, ascending, ten levels a side at most, positive prices and amounts, bids falling,
  asks rising, best bid under best ask; undeclared: refused).
- `deribit`: `DeribitDepth` records `public/get_order_book` at Deribit's stamp, one file a UTC day,
  `depth/<code>/<yyyy-MM-dd>.csv`, in the state volume across restarts; a torn last line is cut off.
  About 270 bytes a snapshot, some 2.3 MB a contract a day at qkt's 10-second poll, never pruned; qkt's
  gzipped day file holds 36 bytes a snapshot (measured on testnet BTC_USDC-PERPETUAL).
- `paper`: the same, over the Deribit public market it quotes.

## 7. qkt

- DSL: `<alias>.bid_depth`, `.ask_depth`, `.book_imbalance` on a venue stream. `BookDepthFieldExpansion`
  (run with the open-interest and hub expansions, at parse and in the compiler; the walk they share is
  `VenueFieldExpansion`) rewrites each into a hidden observation stream
  `<alias>/<field> = DEPTH:<BID|ASK|IMBALANCE>:<VENUE>:<NAME>`, so indicators, lookback and warmup work on it.
  `DslVocabulary` lists the fields; on an alias with no venue series the compiler refuses them by name.
- Store: `depth/<VENUE>/<NAME>/<yyyy-MM-dd>.csv.gz` (`BookDepthStore`), read a day at a time.
- Fetch: `qkt fetch <VENUE:NAME> --depth --from --to` from the `type: gateway` account named after the venue.
- Backtest: `DEPTH:` streams route to the store (`StoreMarketSource`); `BookDepthCoverage` refuses a run the
  stored snapshots do not cover.
- Live: every account directory routes `DEPTH:` streams to the account serving the contract
  (`TradingAccount.bookDepth`); a gateway account's source checks `depth` in `/v1/health` and refuses naming
  the account. The feed reads each contract once at start (so a refusal fails the deploy) and then every
  10 seconds (`BookDepthPoll`), asking a poll ahead so a book the read itself recorded is served at once; a
  failed read is a disconnect that never ends the price feed.
- Parity row A64. Measured: a 26-minute live run on a paper gateway over Deribit's testnet, replayed by a
  backtest over the fetched snapshots, read the same three values at 25 of 26 one-minute closes; at the
  other, live read a snapshot stamped 200 ms after the close, before its evaluation ran.

## 8. Cost

- Tick path, for every strategy: one more `startsWith` in `isObservationSymbol` (measured about 1–2 ns a
  call; it runs twice a tick). Nothing else changes for a strategy that does not read depth.
- Live, a strategy reading depth: one gateway read and three observation ticks per contract every 10
  seconds, on the poll thread.
- Backtest: seven days of 10-second snapshots of one contract (60,480 snapshots, 181,440 observation ticks)
  add 0.5–1.1 s to a 3 s run over 1-minute bars, and run the same in a 256 MB heap.

## 9. Out of scope

Depth within a price band or at a price (`depth_at`), more than ten levels, a fill model that walks the
book, venues other than the gateway's, and pruning the gateway's record.
