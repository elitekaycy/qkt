# Live continuous futures (phase 46) — design

**Status:** steps 1-5 built (see §6). **Why separate:** phase 45 ruled that continuous streams
(`VENUE:ROOT@front`) stay backtest-only (parity A33) because live rolling is not wiring. This document
designs it. **Builds on:** `ContinuousChain`, `AdjustmentChain`, `RollHistoryBuilder`,
`ContinuousContractBroker`/`StreamLane`/`RollExecutor` (phase 42.5) and the gateway connector (44/45).

## 1. What must be true live, as in backtest

- The stream's price series is the same panama-adjusted series backtests read, so a strategy's
  thresholds and warmup mean the same thing: history comes from stored bars, and each live roll extends
  the adjustment exactly as a later backtest of that period would.
- Orders on the stream trade the contract the roll schedule names; at a roll, positions and resting
  orders move to the next contract and the roll's cost is booked (continuous P&L + roll costs = the
  contract legs' P&L, the identity backtests pin).
- A restart in the middle of any of this loses or doubles nothing.

## 2. The four gaps and their designs

### 2.1 Rolls measured live (the adjustment past the last measured roll)

`ContinuousChain` maps contracts only up to the last roll in `RollHistory`; asking further fails. A
roll that happens while live is measured by the rule `RollHistoryBuilder` applies to history: each
contract's last 1-minute close at or before the roll instant. History is built from the venue's own
1-minute klines (Binance's daily kline files, published after the day), so live reads the same klines
from the venue through the gateway's `GET /v1/bars` (wire spec §3), which exist minutes after the roll
rather than a day. At the roll instant the lane:
1. waits for both contracts' 1-minute bar that closes at the roll instant, reads them through the
   account's bars (missing either after a bounded wait: the roll cannot be priced, the stream stops
   taking new orders and alerts, as `RollExecutor` already does for an unpriceable roll),
2. appends the `RollRecord` to the root's history through `RollHistoryStore` (atomic write) and
   rebuilds the chain from it, then
3. carries positions and orders (`RollExecutor`), with the measured prices as the reference.

Because the live record reads the same klines by the same rule, it equals the record `qkt fetch <root>
--rolls` computes later from the published files (a test pins this on recorded klines), so a backtest
over that period gets the same adjustment. Session candles built from quotes are never used for it:
they are not the venue's traded klines.

### 2.2 Market data in continuous space

A `ContinuousLiveFeed` wraps the account's live source: it subscribes the front contract, and the
next one from `leadMs` (default 1 hour) before a roll so its quotes are flowing when the stream
switches to it; it maps each front-contract tick into the series with the contract's `PriceSpace`,
re-stamps it with the continuous symbol, and switches at the roll instant. The next contract's ticks
before the roll feed the lane's venue (its marks and stops) but never the strategy; roll pricing uses
bars, not ticks (2.1).

### 2.3 Orders through the gateway, per stream

Each `StreamLane` runs its venue on a private bus. Live, that venue is a gateway broker for the lane,
which needs two changes in the connector:
- **Routing by order, not strategy.** `GatewayRouting` delivers a fill to the attachment that sent the
  order (the ledger already owns each client order id), so a strategy's main broker and its lanes can
  attach side by side.
- **Positions in contract space.** The lane's attachment judges `reduce_only` and contributes to the
  account holdings check with the lane's own contract positions (`RollLegs` per strategy), not the
  strategy's continuous position.

### 2.3a Roll legs fill asynchronously live (found while building step 4)

`RollExecutor` sends each roll leg and reads its outcome at once (`RollLegs.outcome`): in a backtest the
exchange simulator fills inside `submit`, so the outcome is there. A live venue fills later, on its
own thread, so the roll as written cannot run live. Steps 2 and 3 (live roll measurement,
`ContinuousLiveFeed`) do not depend on this; step 4 does.

Proposal, keeping every backtest identical:
- Split the executor into the **roll plan** (holders, resting orders, legs to send; what it computes
  today before trading) and a **leg driver**. The backtest driver stays synchronous (the simulator
  fills inline), so backtest results do not change; a golden backtest of a rolling stream pins it.
- The live driver runs each strategy's carry as a sealed state machine: `CloseSent → OpenSent →
  Carried`, or `→ Failed` on a refused opening leg (the venue close and stop the backtest already
  applies). Ledger entry, cost and any venue close are published when that strategy's carry ends,
  not in one batch at the roll.
- While a roll is in flight the lane refuses new orders on the stream and holds resting-order
  re-placement until the carries end, so no order interleaves with the legs.
- Legs keep their deterministic ids (`roll:<stream>:<atMs>:<strategy>:close|open`), so after a restart
  the gateway's idempotent submit and order recovery resolve a leg in flight instead of sending it
  twice (§2.4 persists the in-flight state).
- A leg not filled within a bound (a market order on a live venue should fill in seconds) stops that
  strategy on the stream and alerts, as a refused leg does.

**Ruling (step 3):** a roll that cannot be measured ends the stream's feed with its reason, and since a
live feed's end is an outage, the session stops (fail-closed) rather than running a strategy whose
stream no longer updates; the operator measures the roll (`qkt fetch <ROOT> --rolls`) and restarts.
The account feed's own outages pass through the stream unchanged.

### 2.4 Restart

Persisted with the session: each lane's current contract index and per-strategy contract positions;
the roll history is already on disk. On restart the lane restores them, reconciles the contract
positions against the account (the holdings check), and resumes; a roll that fell inside the downtime
is executed at start from the measured closes if both are stored, else the stream refuses new orders
until an operator measures it (`qkt fetch <root> --rolls`).

### 2.4a Restart, in detail (found while building step 5)

Two hazards make a naive restart unsafe. The lane's contract book would start empty while the venue
still holds the stream's contracts, so the account-wide holdings check refuses the account; and a
restart inside a roll would roll again from the strategy's stream position, sending a closing leg the
venue already filled (on a netting account, that opens the other side).

1. **The carry becomes an explicit state machine** (the qkt rule for lifecycles): per strategy,
   `Closing(sent)` → `Opening(closeFill, sent)` → `Carried`, or `Stopped(reason)`, instead of nested
   continuations. Each state names the leg it waits for, so it can be persisted and resumed.
2. **A roll is never repeated:** its legs are persisted with their state and taken back from the venue,
   so a restart neither re-sizes nor re-sends a leg the venue already holds.
3. **Each lane's state is persisted** through the session's `StatePersistor`, in its own record per
   stream (`<stream>-lane.json` under the session's state owner, the strategy that owns its risk state),
   never among the strategies' positions: the contract it last traded, each strategy's stream position
   and stop, the engine orders (with the quantity each venue order was placed for and what of the engine
   order has filled), the contract book, the roll's legs still out with their slices so far, the resting
   orders whose cancel is awaited, and the roll in flight with each holder's step. Contracts are stored by
   symbol, never by schedule index, so a catalog that dropped an expired contract cannot shift them. The
   record is written synchronously **before every venue action** (the lane's venue is wrapped, so no path
   can skip it) and **after every venue answer**: what the lane intends is durable before the venue can
   act on it, and an order the venue does not know after a restart was never sent.
4. **Restore** rebuilds the lane from that record when the session builds its brokers, and fails loudly
   when the chain no longer lists a saved contract or measures the saved roll differently. Each waiting
   leg is awaited again at once. When the engine hands its restored orders to the broker, or at the latest
   when the session is ready (`watchBookedLegs`), the lane has the venue take back every order it had out
   (engine orders under their venue ids, awaited cancels, roll legs), each with what the lane already
   booked of it, so the venue replays only what the lane missed. An engine order the venue does not know
   is forgotten (the engine retires it); a roll leg it does not know is sent again under its own id. Once
   the session is ready the lane forwards the ready signal to its venue (a gateway settles contracts that
   expired while away), re-sends awaited cancels, cancels any engine order the engine no longer holds, and
   only then sends what the restored roll does next: no leg leaves before the session is ready. A roll
   that fell inside the downtime runs on the first tick after the roll in flight (if any) ends: a lane
   rolls one contract at a time.
5. **Startup reconcile** treats a stream as one netting account its strategies share
   (`isAccountWide`): each strategy's persisted stream book stands, instead of being wiped because the
   continuous broker reports no venue positions of its own.

## 3. Parity that remains (rows to add)

- A live roll trades the market at the roll instant (real slippage and spread); backtest roll legs trade
  at the reference closes plus modelled slippage (A35). The adjustment itself is identical.
- Live needs the next contract quoted from `leadMs` before each roll; a venue that lists it later
  cannot be rolled live.

## 4. Testing

- The live roll record equals `RollHistoryBuilder`'s on the same recorded bars.
- A fake-gateway end-to-end: a continuous stream holds a position through a roll; the account ends on
  the next contract with the same signed quantity, the roll cost is booked once, and the identity
  continuous P&L + roll costs = contract P&L holds.
- Restart before, during (between the close and open legs) and after a roll.
- Routing: a strategy with a continuous stream and a listed contract on one account gets each fill on
  the right bus.

## 5. Order of work

1. Gateway routing by order and per-attachment positions (2.3), with tests; no behaviour change for
   single-attachment strategies.
2. Live roll measurement and history append (2.1).
3. `ContinuousLiveFeed` (2.2).
4. `SessionBrokers` routes continuous streams through `ContinuousContractBroker` over the account
   (venue factory = a lane attachment), `LiveSymbolChecks` stops refusing them.
5. Restart (2.4), docs and parity rows, review.

## 6. Built (2026-10-01, PR #1287)

- Step 1: gateway routing by the attachment that sent each order.
- Step 2: `LiveRollMeasurer` over the shared `RollPricing` rule; a live roll is appended only when it
  directly follows the last measured one.
- Step 3: `ContinuousLiveFeed` (one per stream, its own reader thread through `FanInTickFeed`), passing
  the account feed's outages through; `LiveContinuousStreams` measures per root under a lock and extends
  the shared `ContinuousChains` (`useHistory`).
- Step 4: roll legs answered later (`RollExecutor`/`RollCarry` continuations, backtests unchanged and
  pinned by the existing roll tests); lanes read the chain as it stands; each lane keeps its contract
  book (`StrategyPositionTracker`, netted) and hands it to its venue; `SessionBrokers` routes streams
  through a `ContinuousContractBroker` whose lanes are further attachments of the account, each on a bus
  bound to the engine loop (`LaneBuses`); `ContinuousWiring` assembles it in `LiveSession`.
  `LiveContinuousGatewayTest` runs a live session through a roll on the fake gateway end to end.
- Found on the way: the gateway host emitted no `position` events (wire spec §4); fixed in
  qkt-venue-gateway, proven on Deribit testnet.

- Step 5, restart (§2.4a): `PersistedStreamLane` and `StreamLaneFile` (persistence), `LaneState`
  (snapshot and restore), `savingFirst` (the venue wrapper), `LaneRecovery` (venue recovery and ready),
  `RollExecutor.restore`/`ready` (legs held until ready); `ContinuousWiring` hands the session's
  `LaneStateStore` to the lanes. Pinned by `LaneStateSavedTest`, `LaneRestartTest`,
  `LaneRestartEdgesTest`, `FileStatePersistorStreamLaneTest`, and end to end by
  `LiveContinuousRestartGatewayTest` (a session stopped with its closing leg out; the next one takes the
  leg back without re-sending it and carries the position once the gateway fills it).
- Found on the way: a resting order part-filled before a roll was re-placed for its whole quantity, and
  one that filled while the roll cancelled it was placed again; both fixed (`LaneSlicedFillsTest`). A
  re-placed order's slices now reach the engine with the engine order's fill so far.

Known limits:
- The lane and the engine persist separately: the lane synchronously around every venue action and
  answer, the engine through its own (possibly asynchronous) persistor. A crash in the instant between a
  fill reaching the engine and the engine's save can leave the engine's stream position one fill apart
  from the lane's, as with any venue; the lane's record is the one reconciled against the venue.
- A restored contract position whose opening time was never recorded takes the restart time.

**Ruling (no leg timeout):** a roll leg waits for the venue's answer. The gateway resolves every order
it took (write-ahead, then the venue's label, then the order's fills), so a leg always ends; meanwhile
the stream refuses new orders. A timeout would invent an outcome the venue never gave and leave a late
fill with nowhere correct to go. What a venue can do to a leg is handled: a leg filled in slices is
carried whole at the slices' weighted price; an opening leg ended part-filled is unwound and the
position closed on the stream; a closing leg ended part-filled closes only that part
(`LaneSlicedFillsTest`). The strategy's own orders filled in slices reach the engine slice by slice.
