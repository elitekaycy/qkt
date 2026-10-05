# Mark and index price as stream fields — design

**Status:** design for #1298, part of #1295, on the capability foundation of
`2026-10-04-perpetual-funding-and-venue-capabilities.md`. Amends `2026-10-01-vgp-v1-wire.md` (additive; the
gateway's copy follows it).

## 1. Problem

A venue values positions, margins and liquidates at its **mark** price, and holds a perpetual near its
**index** (the spot index it tracks). Gateway quotes already carry both (wire §4a), but a strategy could not
read them: a perpetual's premium (`mark − index`), the price it would be liquidated on, was invisible to rules.

## 2. The rule (as for funding)

1. **Declared:** a gateway serves mark history (`/v1/marks`) only when its adapter declares `mark_prices`;
   otherwise `501 unsupported`, and the contract kit checks both ways.
2. **Refused, never Undefined for ever:** a strategy reading `<alias>.mark` or `.index` does not start on a
   feed that serves no marks (a gateway not declaring `mark_prices`, an MT5 or Bybit account); a backtest
   without the stored series covering the run is refused naming `qkt fetch <SYM> --marks`.
3. **One shape:** `time, mark, index` on the wire and in the store, whatever the venue.
4. **Replayable:** `qkt fetch` stores what live reads, and the backtest sees each value only after its time.

## 3. Wire (VGP v1, additive)

- Capability `mark_prices`.
- `GET /v1/marks?symbol=<code>&window_ms=<ms>&from=<ms>&to=<ms>` → `{"marks": [{"time", "mark", "index"}],
  "next"}`: for each closed window starting in `[from, to)` in which the venue reported them, the last report,
  at its own `time`; at most 100 windows a page (each may cost the adapter a venue call).

## 4. Gateway

- `adapter-api`: `Capability.MARK_PRICES`, `VenueMark`, `VenueAdapter.marks()` (refused unless declared).
- `host`: `/v1/marks`, which keeps one sample per window (the newest) and only closed windows.
- `deribit`: Deribit keeps no mark or index history of futures or perpetuals
  (`public/get_mark_price_history` answers `[]` for them, and only some options otherwise, without the index);
  every public trade carries `mark_price` and `index_price`, so a window's sample is its last trade's. Its
  public hosts keep about a day of trades; mainnet's whole history is on `history.deribit.com`, testnet has
  none (a testnet gateway serves the last day). Facts and fixtures: the adapter's README.
- `paper`: the same, from mainnet's history host by default.

## 5. qkt

- DSL: `<alias>.mark`, `<alias>.index` (`DslVocabulary.markFields`, part of the stream fields every tool lists).
  `MarkFieldCompiler` reads `MarketSource.marksFor(symbol)` at evaluation time; Undefined only until a first
  value is known. `requireMarkPrices` refuses at bind (live and backtest) a strategy whose symbols' source
  serves no marks or reports a problem.
- Live: `GatewayMarketSource` keeps each code's newest quote that carried a mark or index (by reference: no
  allocation per quote), and serves it by qkt symbol when `/v1/health` declares `mark_prices`. A continuous
  stream has no marks (it follows several contracts).
- Store: `marks/<VENUE>/<NAME>/<tf>/<day>.csv` (`time,mark,index`), one whole UTC day a file, written by
  `qkt fetch <VENUE:CONTRACT> --marks --tf <tf> --from --to` from the `type: gateway` account named after the
  venue; only days already over are written; a day without reports is stored empty.
- Backtest: `LocalMarketSource` serves `StoredMarkPrices`: at an instant, the newest sample of the stream's
  window strictly before it (at a bar close, the last inside the bar). `BacktestMarkCoverage` refuses a run
  with a day of a mark-reading stream not stored.
- Parity row A60.

## 6. Out of scope

Lookback on marks (`x.mark[1]`), marks as an indicator series, marks of continuous streams, the Bybit direct
connector's marks, and the rest of #1295 (depth, Greeks as rule inputs, open interest, liquidations).
