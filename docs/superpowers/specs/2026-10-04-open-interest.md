# Open interest as a declared capability and stored series — design

**Status:** design for #1300, part of #1295, on the capability foundation of
`2026-10-04-perpetual-funding-and-venue-capabilities.md`. Amends `2026-10-01-vgp-v1-wire.md` (additive; the
gateway's copy follows it).

## 1. Problem

Open interest (the contracts outstanding on a contract) is a common input to derivatives strategies:
rising open interest with price confirms a move, falling open interest into a rally says shorts are
covering. qkt has no source of it. `HUB:` datasets carry none, gateways do not serve it, and a strategy
cannot read it in a backtest or live.

Venues publish it differently, and qkt must not know which venue it is on:

| | present figure | history |
|---|---|---|
| Deribit | `open_interest` on every ticker, at the ticker's `timestamp` | none: `get_open_interest_history` and `get_open_interest` are `Method not found`; chart data carries no open interest (probed 2026-10-04, testnet and mainnet) |
| Binance USDⓈ-M | `/fapi/v1/openInterest` | `/futures/data/openInterestHist`: 5 min to 1 day, last 30 days only, each row stamped with the start of its period and published about 95 s after it (measured 2026-10-04) |

## 2. The rules (those of #1294)

1. **Declared:** a gateway adapter serves open interest only by declaring `open_interest` in `/v1/health`
   `capabilities`; undeclared, `/v1/open-interest` answers `501 unsupported`, and `AdapterContractTest`
   checks both ways.
2. **Refused, never missing:** a strategy reading open interest on an account whose gateway does not
   declare it fails at start, naming the account; a backtest without stored figures covering the run is
   refused naming the fetch.
3. **One shape:** `{time, open_interest}` on the wire and in the store, whatever the venue.
   `open_interest` is in the contract's order quantity; `time` is when the figure became known.
4. **Replayable:** `qkt fetch --open-interest` stores what live reads under `data_root`, and the backtest
   replays each figure from `time` on.

## 3. Wire (VGP v1, additive)

- Capability `open_interest`.
- `GET /v1/open-interest?symbol=<code>&from=<ms>&to=<ms>` → `{"open_interest": [{"time", "open_interest"}],
  "next"}`, oldest first, each `time` in `[from, min(to, now)]`, at most 1000 figures over at most 1000
  minutes a page; `next` is the next page's `from`, absent on the last.
- `time` is the instant the venue made the figure known: a figure stamped with the start of the period it
  summarizes is served at the period's end.
- A venue that publishes no history serves the figures its adapter recorded, and its README says so.

## 4. Gateway

- `adapter-api`: `Capability.OPEN_INTEREST`, `VenueAdapter.openInterest(code, from, to)` (default: refused
  as unsupported), `VenueOpenInterest(timeMs, openInterest)`.
- `host`: `/v1/open-interest`, paged as above; the testkit's `openInterest` check (declared: ascending, in
  the window, never negative, the present figure at least; undeclared: refused).
- `deribit`: `DeribitOpenInterest` records the ticker's figure when a read's window reaches the present
  (within a minute) and serves what was recorded, kept in `open-interest/<code>.csv` in the state volume
  across restarts. A series starts when the gateway first read it; time nothing read it is a gap.
- `paper`: the same, over the Deribit public market it quotes.

## 5. qkt

- DSL: `<alias>.open_interest` on a venue stream. `OpenInterestFieldExpansion` (run with the hub
  expansion, at parse and in the compiler) rewrites it into a hidden stream `<alias>/open_interest =
  OI:<VENUE>:<NAME>`, an observation stream (`isObservationSymbol`) whose each figure closes as its own
  event candle at its `time`. Indicators, lookback and warmup therefore work on it as on a price.
  `DslVocabulary` lists the field (candle and numeric fields), so `qkt dsl vocabulary` and the language
  server offer it; on an alias with no venue series (a basket) the compiler refuses it by name.
- Store: `open_interest/<VENUE>/<NAME>.csv` (`time,open_interest`), `OpenInterestStore`.
- Fetch: `qkt fetch <VENUE:NAME> --open-interest --from --to`: Binance's public history for `BINANCE_UM`
  (stored at period end; an older `--from` than 30 days is refused naming the limit), else the
  `type: gateway` account named after the venue.
- Backtest: `OI:` streams route to the store (`StoreMarketSource`); setup refuses a run whose stored
  figures do not cover it (`OpenInterestCoverage`, the funding rule: start and end within three of the
  series' own intervals, no longer gap).
- Live: every account directory routes `OI:` streams to the account serving the contract
  (`TradingAccount.openInterest`); a gateway account's source checks `open_interest` in `/v1/health` and
  refuses naming the account. The feed reads once at start (so a refusal fails the deploy) and then
  every minute (`OpenInterestPoll`); a failed read is a disconnect that never ends the price feed.
- Parity row A61.

## 6. Out of scope

Open interest in quotes (a poll suffices: Deribit's figure moves slowly and Binance publishes every five
minutes); Bybit's own open-interest endpoint (outside VGP); the rest of #1295.
