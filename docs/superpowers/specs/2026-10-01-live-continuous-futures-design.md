# Live continuous futures (phase 46) — design

**Status:** design for review, no code yet. **Why separate:** phase 45 ruled that continuous streams
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
roll that happens while live is measured the way `RollHistoryBuilder` measures history: each
contract's last 1-minute close at or before the roll instant. Live, those closes come from the
session's own candles of both contracts, so the lane subscribes the next contract from `leadMs`
(default 1 day) before the roll. At the roll instant the lane:
1. reads both closes (missing either: the roll cannot be priced, the stream stops taking new orders and
   alerts, as `RollExecutor` already does for an unpriceable roll),
2. appends the `RollRecord` to the root's history through `RollHistoryStore` (atomic write) and
   rebuilds the chain from it, then
3. carries positions and orders (`RollExecutor`), with the measured prices as the reference.

The appended record is exactly what `qkt fetch <root> --rolls` would compute from the same bars, so a
backtest run later over that period gets the same adjustment (a test pins this: a live roll's record
equals the builder's on the recorded bars).

### 2.2 Market data in continuous space

A `ContinuousLiveFeed` wraps the account's live source: it subscribes the front contract (and the next
from `leadMs` before a roll), maps each contract tick into the series with the contract's
`PriceSpace`, re-stamps it with the continuous symbol, and switches at the roll instant. Contract ticks
of the next contract before the roll feed the lane (for roll pricing) but never the strategy.

### 2.3 Orders through the gateway, per stream

Each `StreamLane` runs its venue on a private bus. Live, that venue is a gateway broker for the lane,
which needs two changes in the connector:
- **Routing by order, not strategy.** `GatewayRouting` delivers a fill to the attachment that sent the
  order (the ledger already owns each client order id), so a strategy's main broker and its lanes can
  attach side by side.
- **Positions in contract space.** The lane's attachment judges `reduce_only` and contributes to the
  account holdings check with the lane's own contract positions (`RollLegs` per strategy), not the
  strategy's continuous position.

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
