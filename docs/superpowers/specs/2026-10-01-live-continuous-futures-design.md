# Live continuous futures (phase 46) — design

**Status:** steps 1-4 built (see §6); step 5 (restart) and the live leg timeout remain. **Why separate:** phase 45 ruled that continuous streams
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

Remaining:
- Step 5, restart (§2.4): persist each lane's contract book and an in-flight roll's legs; a roll in a
  downtime is already measured on the first tick after restart.
- A live leg timeout: a leg the venue never answers must stop the strategy on the stream and alert, and
  a late fill of that leg must not reach the engine as an order of its own. Not built until that late
  fill path is designed and tested.
