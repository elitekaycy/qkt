# Sharding by account/venue — design (future scaler)

> Companion to `2026-06-08-async-order-flow.md`. **Not in scope** for the async PR. Captures the
> horizontal-scaling path so it's recorded when capital/throughput needs it. Async sends fix the
> *engine thread*; sharding fixes the *broker terminal*. Orthogonal and complementary.

## The boundary is physical, not arbitrary

qkt-prod is one chain today: `qkt daemon (one engine thread) → qkt-mt5 gateway → one MT5 terminal →
one Exness account`. The MT5 terminal is a single process logged into ONE account, and `order_send`
is **synchronous inside the terminal** — one order at a time. That terminal is the hard serialization
point; no threading in qkt parallelizes it. So **one account ≈ one terminal ≈ one order queue ≈ one
shard.** Capital allocation to accounts decides the shard count; everything else is downstream.

## A shard IS the §1 single-consumer engine, bound to one account

```
shard = { engine thread + inbound queue + EventBus + OrderManager + positions }   (the §1 model)
        + { broker connection → gateway → terminal → account }
        + { the strategies assigned to it }
```

Sharding composes with §1 for free: N independent single-consumer engines, none sharing mutable
state (different accounts). No architectural conflict — just more of the same engine.

## Two questions decide the shape

**(1) Do the strategies share one account or several?**
- **All on one account → one shard, by necessity.** They contend on one position/margin state and
  MUST be serialized (two threads independently deciding "am I flat?" on one account is the
  netting-vs-hedging bug class). Sharding gives nothing here; **async + event-driven OCO is the whole
  answer** and the terminal is the throughput ceiling.
- **Spread across N accounts → N shards.** N terminals place orders in parallel; a session-open burst
  on shard A doesn't touch shard B's terminal or engine thread. This is where sharding makes it fast.

**(2) N processes or one process with N engines?**
- **N processes** (natural from where prod is): each shard = its own daemon + gateway + terminal +
  account. Clean isolation (separate JVMs / failure domains / Docker stacks). Go from one
  `qkt-prod-sqvjvo` compose project to N (or one compose with N gateway+daemon pairs).
- **One process, N `LiveSession`s**: shared JVM + shared market-data feed, separate engine threads per
  shard. Saves memory; a crash takes all shards down. Good when shards are small.

## Shared vs per-shard (the efficiency split)

| | shared | per shard |
|---|---|---|
| Market data (ticks/candles — same regardless of account) | ✅ fan one feed to all shards | |
| Engine thread / bus / OrderManager / positions | | ✅ one per shard |
| Broker / gateway / terminal / account | | ✅ one per shard |
| Risk / margin / equity | | ✅ per account, cannot be shared |

Efficient design: **one read-only market-data layer fanned to every shard** (don't pay for N feeds of
the same XAUUSD ticks); **per-account everything that touches money.** A XAUUSD tick is delivered to
every shard trading XAUUSD; each shard's engine independently decides what its strategies do on its
account.

## What's needed to build it

- A **router / mapping** `strategy → account → shard`. Today it's implicit (one strategies dir → one
  daemon). Sharded, the deployer launches each shard with its strategy subset + its account's gateway.
- The **market-data fan-out** (one source → many shard subscribers) — read-only, so no coordination.
- A **supervisor** to aggregate cross-shard reporting (total equity/exposure becomes a sum over shards,
  since it's no longer one in-memory number). Most risk is per-account so this is mostly reporting.

## Costs

- **Ops multiplies:** N terminals to keep logged in (VNC-once-per-terminal — the real operational tax),
  N gateways, N deploys.
- **Cross-account coordination is explicit:** a global kill-switch or cross-account exposure cap must be
  a coordination layer, not a shared variable (that isolation is the point).
- **Cross-account portfolio view is a join**, not a lookup.

## Mental model

- **Async + event-driven OCO** → one engine thread, correct + non-blocking → one account/shard hosts as
  many strategies as the *terminal* can place orders for.
- **Sharding by account** → several such engines in parallel, each on its own terminal → throughput past
  one terminal's ceiling.
- Async fixes the thread; sharding fixes the terminal. Build async first; shard when you put capital on
  more than one account.
