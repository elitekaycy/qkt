# Trade flow and liquidations as stream fields — design

**Status:** design for #1301 (public trades with aggressor side) and #1303 (liquidation prints), part of #1295,
on the capability foundation of `2026-10-04-perpetual-funding-and-venue-capabilities.md` and following
`2026-10-04-mark-and-index-stream-fields.md`. Amends `2026-10-01-vgp-v1-wire.md` (additive; the gateway's copy
follows it).

## 1. Problem

Trades reach qkt as bars or ticks, so whether a print was buyer- or seller-initiated is lost, and the prints a
venue made to liquidate positions are not seen at all. Order-flow rules (delta, imbalance) and cascade rules
(fade a wave of long liquidations) could not be written or backtested.

## 2. One source, two capabilities

Both come from a contract's **public trade tape**. Deribit marks every print with its taker's `direction` and
a print that liquidated a position with `liquidation` (`T` taker, `M` maker, `MT` both), on one tape. Other
venues split them: Binance and Bybit publish liquidations as their own feed (`forceOrder`, `allLiquidation`) and
do not mark them on the tape. So they are two capabilities in one shape:

- `trades`: every print with its aggressor side.
- `liquidations`: the prints that liquidated a position, with the side of the liquidation order (`sell` closed
  a liquidated long). A print that liquidated both counterparties is two entries.

A venue may declare one without the other. The account's own liquidations are fills, a risk-side concern.

## 3. The rule (as for funding and marks)

1. **Declared:** a gateway serves `/v1/trades` only when its adapter declares `trades`, `/v1/liquidations` only
   with `liquidations`; otherwise `501 unsupported`, and the contract kit checks both ways.
2. **Refused, never zero:** a strategy reading a flow field does not start on a feed that serves none (a gateway
   without the capability, MT5, Bybit, a continuous stream); a backtest without the stored tape covering the
   run is refused naming `qkt fetch <SYM> --tape` or `--liquidations`.
3. **One shape:** `id, time, price, size, side` on the wire and in the store, whatever the venue.
4. **Replayable:** `qkt fetch` stores what live reads; a backtest sees a bar's flow only when live would.

## 4. Wire (VGP v1, additive)

- Capabilities `trades`, `liquidations`.
- `GET /v1/trades?symbol&from&to` → `{"trades": [{"id", "time", "price", "size", "side"}], "next"}`: prints
  with `time` in `[from, min(to, now))`, oldest first, `side` the aggressor's, `size` in the order quantity. At
  most 1000 a page; a full page ends before its last millisecond, whose prints all open the next (`next` is that
  millisecond), so no print is served twice and the client needs no de-duplication.
- `GET /v1/liquidations?symbol&from&to` → `{"liquidations": [...], "next"}`: the same shape, `side` the
  liquidation order's; at most one hour a page (a venue may have to read its whole tape to find them).

## 5. Gateway

- `adapter-api`: `Capability.TRADES`, `Capability.LIQUIDATIONS`, `VenuePrint`, `VenueAdapter.trades(code, from,
  to, limit)` and `liquidations(code, from, to)` (refused unless declared).
- `host`: `/v1/trades` (one adapter call a page, the cut at the last millisecond), `/v1/liquidations`.
- `testkit`: the last hour of `activeCode`: trades not empty, in range, ascending, positive; liquidations in
  range (none is an answer).
- `deribit`: both from `get_last_trades_by_instrument_and_time` on the history host the marks use (mainnet's
  `history.deribit.com`; testnet's own host keeps about a day). Liquidations scan the tape (no index).
- `paper`: the same, from mainnet's history host by default.

## 6. qkt: what a strategy sees

Four stream fields of a venue stream, volumes in the contract's quantity, summed over one bar of the stream's
timeframe (epoch-aligned windows, as `/v1/bars`):

| field | sums | capability |
|---|---|---|
| `buy_volume` | prints whose aggressor bought | `trades` |
| `sell_volume` | prints whose aggressor sold | `trades` |
| `long_liq_volume` | liquidations that sold (longs liquidated) | `liquidations` |
| `short_liq_volume` | liquidations that bought (shorts liquidated) | `liquidations` |

**Read with a lookback of at least one bar:** `perp.buy_volume[1]` is the flow of the bar before the one closing
(`[n]`: n bars before it); a bare `perp.buy_volume` is refused at compile. A bar's flow is known only after the
bar closes, and live reads it from the gateway's tape after that: at the close itself the last prints may not
have arrived (a live bar closes on the first tick past its end), so reading the closing bar would make live see
less than the backtest, by however much the poll had not read. One bar later the tape has had a whole bar to
arrive, so live and backtest read the same sums. A bar with no print is `0`; a bar whose tape is not known is
Undefined (live: the first bars after a start, or the gateway unreachable; backtest: never, coverage refuses).

`x.buy_volume[n]` is compiled directly from the series (not through the `lag` indicator), so it needs no warmup
of its own and any `n` reads straight from the store or the live window cache.

- Live (`GatewayTradeFlow`): per code, kind and timeframe, a cache of closed windows' sums. A read that misses
  queues one background fetch (`/v1/trades` or `/v1/liquidations`, every page) from the window asked to the
  newest window closed at least two seconds ago, and answers Undefined; every read also queues the fetch of
  windows closed since, so in steady state each window is read within seconds of closing, a bar before any rule
  needs it. Only on the account's own contracts, when `/v1/health` declares the capability. No per-tick work.
- Store: `tape/<VENUE>/<NAME>/<day>.csv.gz` and `liquidations/<VENUE>/<NAME>/<day>.csv.gz`
  (`id,time,price,size,side`, oldest first), one whole UTC day a file, written by `qkt fetch <VENUE:CONTRACT>
  --tape` / `--liquidations --from --to` from the `type: gateway` account named after the venue; only days
  already over are written; a day without prints is stored empty.
- Backtest (`StoredTradeFlow`): a window's sums from its day's file (read once, a few days kept). Coverage
  (`BacktestFlowCoverage`) refuses a run whose reads need a day not stored, from the earliest window the run,
  its warmup and its lookbacks read.
- Bind (`requireTradeFlow`, live and backtest): each symbol and kind read must be served by its source.
- Parity row A63 (A58–A62 are taken).

## 7. Out of scope

Tick-level flow (per-print rules), the forming bar's flow, volume profiles and footprints, the Bybit and
Binance direct connectors' tapes, and the rest of #1295.
