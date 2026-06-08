# Sharding internals — the multi-account / multi-venue case

> Companion + correction to `2026-06-08-sharding-by-account.md`. That doc assumes a clean
> `strategy → one account → one shard` mapping. This doc covers the case it does **not**: one
> strategy fanning orders across N venues/accounts, and several strategies sharing a pool of
> accounts. The conclusion changes the recommended primitive.

## The reframe: the strategy is NOT the shard boundary

The existing doc's load-bearing claim is "a shard = the §1 single-consumer engine bound to one
account" and "one account ≈ one terminal ≈ one order queue ≈ one shard." That's right about the
*physical* serialization point and wrong about where the *engine* sits relative to it.

Two facts from the code break the 1:1:

1. **Venue is a property of the symbol, not the strategy.** A stream's identity is
   `broker:symbol` — `HubKey.qktSymbol = "$broker:$symbol"` (`HubKey.kt:21`, e.g. `EXNESS:XAUUSDm`,
   `BYBIT_SPOT:BTCUSDT`). `OrderRequest` carries `symbol` + `strategyId` and **no account/venue
   field** (`OrderRequest.kt:21-49`). The venue is recovered downstream by matching the symbol
   against a broker's `SymbolPattern`. So a single strategy that declares streams on `EXNESS:XAUUSDm`
   *and* `BYBIT_LINEAR:BTCUSDT` *and* a second MT5 profile already emits orders to three venues —
   nothing about the strategy is "in" one account.

2. **One strategy already fans across N brokers on ONE engine thread, today.** `LiveSession.buildBroker`
   groups the strategy's declared streams by `key.broker`, builds one leaf broker per venue, and wraps
   them in a `CompositeBroker` keyed by `SymbolPattern.exactSet(...)` (`LiveSession.kt:260-304`).
   `CompositeBroker.submit` routes each order to the leaf whose pattern matches `request.symbol`
   (`CompositeBroker.kt:48-59`, `brokerFor` at `:87-88`). All leaves share **one** bus, **one**
   OrderManager, **one** PositionTracker, **one** engine thread.

So the real serialization point is the terminal's `order_send` (correct in the old doc), but qkt's
engine is **upstream and shared across venues** — it is not, and cannot be, "bound to one account"
when the strategy spans accounts. The old doc's shard diagram (`{engine + queue + bus + OrderManager
+ positions} + {one account}`) only holds for the degenerate single-venue strategy.

---

## Q1. Is the shard "detected" from the strategy? No — from the symbol→venue routing

There is no smart detection and the strategy is the wrong unit. The forced boundary is the
**account/terminal `order_send`** (one MT5 terminal serializes order placement; the async doc's
"concurrency + scale reality" section). Everything that *must* serialize per account lives behind
that boundary:

- order placement to the venue,
- the venue's authoritative position/margin/equity state.

The strategy is a *decision* unit that may straddle several such boundaries. The mapping that
matters is `symbol (broker:symbol) → leaf broker → account/terminal`, which `CompositeBroker`
already computes. A shard, if we cut one, is cut along the account boundary, and a strategy's orders
are *distributed* across shards by that same symbol→venue map — the strategy does not "belong to" a
shard.

## Q2. Multi-broker-single-strategy: decision runs once, orders fan by symbol

Where decision logic runs vs where orders execute, today:

- **Decision** (indicators, signals, OCO/stack orchestration, risk approval) runs once, on the
  session's single engine thread (`TradingPipeline` wires every strategy's `onTick`/signal path onto
  the one bus; `LiveSession`'s `qkt-live-engine` thread is the sole consumer, `LiveSession.kt:619-653`).
- **Execution** fans out by symbol through `CompositeBroker` to per-venue leaves. With the async-order
  design landed, each leaf's `submit` returns immediately (optimistic ack) and the venue result comes
  back as a bus event re-queued onto the engine thread.

**Verdict: `CompositeBroker` + async already solves multi-broker routing for one strategy on one
engine thread.** Engine-sharding is therefore *not* required to let a strategy hit multiple venues.
Sharding earns its keep only for (a) CPU load — one engine thread saturating on ~tens of strategies'
in-memory work, which the async doc notes is microseconds, so this is far off — or (b) failure
isolation — one venue's connector wedging shouldn't stall another venue's strategies on the shared
thread. Multi-broker routing per se is a solved problem; do not shard for it.

One real ceiling remains and async does not fix it: a slow/blocked leaf's *events* still funnel
through the one inbound queue, and a leaf that blocks inside `submit`'s synchronous-local part (or a
non-async leaf) blocks the thread. That is a failure-isolation argument, not a routing one.

## Q3. Shared accounts across strategies: nobody serializes per-account state today

This is the sharp edge. The daemon spawns **one `LiveSession` per strategy** (`StrategyHandle.RealFactory.create`
builds `LiveSession(strategies = listOf(ast.name to strategy), ...)`, `StrategyHandle.kt:123-151`;
portfolios likewise fan one session per child, `PortfolioDeployer`). Each session constructs its own
bus, engine, OrderManager, `PositionTracker`, and `StrategyPositionTracker` (`LiveSession.start`,
`:426-444`), and **calls the broker factory itself** — so two strategies targeting the *same* MT5
profile get **two independent `MT5Broker` objects** pointing at the *same* terminal
(`DaemonCommand.kt:102-135` — the factory is a per-session closure; Bybit shares one `BybitClient`
transport but still hands out separate broker objects per session, `:153-164`).

Consequences:

- Per-account order/position/margin state is **not** serialized across strategies in-process. Two
  strategies on one account each run their own position model keyed only by `(strategyId, symbol)`
  (`StrategyPositionTracker.byStrategy`, `StrategyPositionTracker.kt:26`) — there is no account
  dimension and no shared authority.
- `ACCOUNT.equity` is **synthetic and per-strategy**: `startingBalance + realized + unrealized`
  computed in-engine (`StrategyPnL.equityFor`, `StrategyPnL.kt:67-73`), *not* pulled per-account from
  the broker. So "am I flat / what's my margin?" is answered from each strategy's private book, never
  from a shared account view.
- This is exactly the netting-vs-hedging bug class (#269, the hedge-straddle accumulation entry in
  memory): two independent deciders on one netting account each believe they own the account's
  position. The only cross-strategy link today is `siblingsLookup` (`DaemonCommand.kt:108-126`), an
  *out-of-band* orphan-recovery correlation for #154 — not a live serialization authority.

**So today there is no per-account serializer.** If two strategies share an account, correctness rests
on them not contending on the same symbol/position — which is a deployment convention, not an enforced
invariant. Any sharding design that wants shared accounts to be *safe* has to introduce the per-account
authority that doesn't exist yet.

## Q4. Decision plane vs execution plane + router — right model, premature to build

The two-plane split is the **correct mental model** and the right target architecture:

- **Decision/compute plane** — strategy logic, indicators, signals, OCO/stack orchestration, risk
  approval. Naturally shardable by strategy/CPU. This is `TradingPipeline`'s signal path.
- **Execution/account plane** — per-account `order_send` serialization + the authoritative
  position/margin/equity state for that account. Shardable by account/terminal. This is the leaf
  broker + a (currently missing) per-account position authority.
- **Router** — maps each venue-tagged order to its account's execution shard. `CompositeBroker`
  **is already this router**, keyed by `broker:symbol → SymbolPattern → leaf` (`CompositeBroker.kt:48-88`).

What it costs vs the current "one LiveSession = one engine + one OrderManager + one positions model":
today the two planes are **fused inside one session**, and the position/PnL/risk model lives entirely
in the *decision* plane, keyed by `(strategyId, symbol)` with no account axis. Splitting the planes
means:

1. Introducing an **account key** into the position/margin model (today there is none — `PositionTracker`
   is `Map<symbol, Position>`, `PositionProvider.kt:15`; `StrategyPositionTracker` is
   `Map<strategyId, Map<symbol, LegBook>>`).
2. Moving the **per-account authority** out of the per-strategy session into a shared execution shard
   so multiple strategies' decision planes consult one account truth (Q3's missing serializer).
3. A **cross-account supervisor** for aggregate equity/exposure/kill-switch — today these are single
   in-memory numbers per session; sharded they become a join (the old doc's supervisor point, which
   stands).

This is real surface area touching `PositionTracker`, `StrategyPositionTracker`, `StrategyPnL`,
`RiskState`/`RiskEngine`, `OrderManager`, and the session-construction path. **Recommendation: keep
the two-plane split as the design north star, but do NOT build it now.** Prod is one account
(`reference_mt5_gateway_login`), the async doc fixes the only live throughput pain on the engine
thread, and `CompositeBroker` already covers multi-venue routing. Building per-account execution
shards today is over-engineering for a one-account reality (YAGNI). The case that *forces* it is the
one we already hit: **two or more strategies sharing one account.** When that goes to production,
build the per-account authority first (Q3), not the full plane split.

## Q5. Parity: safe IFF the per-account split lives strictly below the parity boundary

`BacktestLiveParityTest` asserts `Backtest` and `LiveSession` produce identical trade lists, final
positions, and total realized PnL for the same ticks (`BacktestLiveParityTest.kt:20-27`). Backtest
models **one account, one PaperBroker** keyed by symbol (`brokerKind = BrokerKind.PAPER`,
`Backtest.kt`), and the position math (`PositionTracker`, `StrategyPositionTracker`) is the *shared*
code both modes run.

The tension is real: if LIVE splits OrderManager/positions per account while BACKTEST keeps one
account/one OrderManager, the two paths diverge and parity breaks. The resolution turns on **where the
account axis sits relative to the parity boundary**:

- The parity boundary is the *fill/position/PnL* path — `applyFill` and the realized-PnL math. That
  code already keys by `(strategyId, symbol)` and is mode-agnostic. As long as the **account key is a
  routing concern that lives in the execution plane (which leaf/terminal an order goes to)** and the
  **position/PnL math stays keyed the same way in both modes**, parity holds — the per-account split is
  *below* the boundary (it decides *where* the order_send happens, not *what the fill does to the
  book*).
- Parity **breaks** the moment account membership changes the *position/PnL result* — e.g. if two
  strategies sharing a netting account must net against each other in live (one shared account book)
  but backtest models them as independent books. That is the #269 semantics, and it is a *modeling*
  decision, not a sharding mechanism.

**The clean rule: sharding (which engine thread, which terminal serializes the send) must be a pure
execution-plane concern with zero effect on the fill→position→PnL computation. The async doc already
holds exactly this line** (in-memory brokers publish events inline → backtest unchanged; only HTTP
brokers defer). Sharding must inherit it: an order's journey through fill/position/PnL must be
bit-identical whether its `order_send` was serialized on shard A, shard B, or a backtest's single
PaperBroker.

Does backtest need to model multiple accounts? **Only if** we decide shared-account *netting
semantics* are part of the model (the #269 fix). If the chosen answer to #269 is the "truthful
position model" (every entry is its own real position, no netting lie — `project_truthful_position_model`
in memory), then account membership never changes the per-leg PnL, and backtest can stay single-account
while live shards by account **below** the parity boundary. If instead we commit to real cross-strategy
netting on a shared account, backtest must grow an account axis too — and that is a parity-relevant
modeling change that must be designed against `BacktestLiveParityTest`, not smuggled in via sharding.

---

## Where the existing sharding doc is wrong / oversimplified

| Old doc claim | Correction |
|---|---|
| "one account ≈ one terminal ≈ one order queue ≈ one shard" — clean 1:1 | True only for a single-venue strategy. A strategy spans venues; the shard boundary is the **account**, and a strategy's orders are *distributed* across shards by `broker:symbol` routing. |
| "a shard = the §1 single-consumer engine bound to one account" | The engine is **upstream of and shared across** venues (one engine → `CompositeBroker` → N leaves). It can't be "bound to one account" for a multi-venue strategy. The account boundary is the *leaf/terminal*, not the engine. |
| Shard = `{engine + queue + bus + OrderManager + positions} + {one account}` | Those engine-plane components are **per-strategy and venue-agnostic** today. Only the leaf broker + (missing) per-account authority belong to the account plane. |
| "All on one account → one shard, by necessity… they MUST be serialized" | Today they are **NOT** serialized — one `LiveSession` *per strategy*, independent OrderManager/positions, two `MT5Broker` objects on one terminal. The serializer the doc assumes **does not exist yet**; that's the gap to close before shared-account sharding is safe (#269). |
| Router is a deploy-time `strategy → account → shard` launcher mapping | The runtime router already exists in-process: `CompositeBroker` maps `broker:symbol → leaf`. Sharding adds an *account → execution-shard* layer beneath it, not a strategy→shard launcher. |

## Concrete changes (when shared-account sharding is actually needed)

1. **Per-account execution shard** — a serialized owner of one account's `order_send` + authoritative
   position/margin/equity, shared by every strategy decision-plane that touches that account. New type
   in the execution plane; the leaf broker plugs into it. Closes the Q3 gap.
2. **Account key in the position/risk model** — add an account axis to `PositionTracker` /
   `StrategyPositionTracker` / `StrategyPnL` / `RiskState`, or scope one authority per account. Must
   stay below the parity boundary (Q5).
3. **Router stays `CompositeBroker`** — extend it from `symbol → leaf` to `symbol → account-shard`;
   it is already the right seam.
4. **Market-data fan-out** — one read-only feed to all decision planes (the old doc's point; correct,
   already cheap since feeds are symbol-keyed and read-only).
5. **Cross-account supervisor** — aggregate equity/exposure + global kill-switch as a join over
   shards, not a shared variable (old doc's point; stands).

## Bottom line

- Sharding is detected from **account/venue routing (`broker:symbol`)**, never from the strategy.
- One strategy across N venues is **already handled** by `CompositeBroker` + async on one engine
  thread; shard for CPU/failure-isolation, not for routing.
- The **two-plane + router** model is right as the target, but premature to build for one-account
  prod. The trigger to build it is **shared accounts across strategies**, where qkt has *no*
  per-account serializer today (the #269 bug class).
- Parity survives **iff** the per-account split is a pure execution-plane concern (where the send
  serializes) with zero effect on fill→position→PnL. The moment shared-account *netting semantics*
  enter, backtest must model accounts too, and that is a parity-gated modeling decision — design it
  against `BacktestLiveParityTest`, don't let sharding smuggle it in.
