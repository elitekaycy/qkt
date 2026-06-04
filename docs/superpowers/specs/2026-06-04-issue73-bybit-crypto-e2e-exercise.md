# Exercise crypto end-to-end on Bybit (#73) — Exercise Charter

**Type:** bug-finding exercise (not a feature build). The deliverable is "every reachable Bybit
path exercised against live/testnet, with the bugs that surface fixed and guarded by tests."

**Why a charter, not a feature spec:** you cannot spec fixes for bugs you have not surfaced yet.
So this document fixes the *exercise plan, safety rails, and done-criteria*; concrete fixes are
discovery-driven and fold in as they appear. A concrete implementation plan is written **after**
thread 1's discovery run, once the real bugs are known.

## Context

qkt has Bybit broker code (`BybitSpotBroker`, `BybitLinearBroker`) and market sources that have
never run end-to-end. Prior exercising (#73 comment) surfaced and fixed/filed:
- **G4** (fixed in #215): query/reconcile used POST on GET-only endpoints → 404s; now signed GET.
- **#213** (closed): linear data `BYBIT_PERP:` vs broker `BYBIT_LINEAR:` mismatch — unified on
  `BYBIT_LINEAR:` (`BybitSymbol`, `BybitLinearBroker.supports`, state recovery all agree now).
- **#214** (merged): fetched bars never reached the backtest replay — now synthesized to ticks.

So the backtest and linear paths are freshly unblocked. The only hard blocker remaining is a
trade-enabled testnet key for live order placement.

## Credentials

- **Testnet** key/secret live in `../qrypto/.env` (`BYBIT_API_KEY`/`BYBIT_API_SECRET`; the file
  header points at `testnet.bybit.com`). Scope (read vs trade) is detected at runtime.
- `../qkt-prod/.env` (production) is **off-limits** — its Bybit vars are commented out and it is
  never read by this work.

## Threads (in order)

1. **Public market-data + `fetch`→`backtest` e2e** (no creds). Run real `qkt fetch
   BYBIT_SPOT:BTCUSDT` and `BYBIT_LINEAR:BTCUSDT` against Bybit's public kline endpoint, then
   `qkt backtest` a real crypto strategy. This is the #214-unblocked path on live data and the
   most likely to surface latent bugs. **This is the discovery run.**
2. **Stale fixture cleanup.** `momentum_basket.qkt`, `multi_broker.qkt`: `BYBIT:` → `BYBIT_SPOT:`.
   Keep `RoundTripEquivalenceTest` / `ParserHeaderTest` green. Leave `syntax_errors.qkt` alone —
   its broker token is incidental to what that fixture tests.
3. **Testnet read/reconcile** (testnet key). Wallet-balance, open orders, executions, positions
   via the signed-GET path (the G4 area). Read-only.
4. **Order-flow E2E** (testnet). Submit / fill / cancel. Runs only if the key has trade scope; a
   `retCode=10005 "permissions for action"` means read-only — documented as the one piece needing
   a trade-enabled key, left one-command-runnable.

## Safety rails (non-negotiable)

- Verify the Bybit base URL resolves to **testnet** before any authenticated call; set the testnet
  flag explicitly. Abort rather than risk a mainnet call.
- Secrets are loaded from `../qrypto/.env` into process env only — never printed, logged, echoed,
  or committed.
- No mainnet. No real-money path. `../qkt-prod/.env` is never read.

## Done criteria

- Threads 1–3 run against live/testnet; every bug that surfaces is fixed with a regression test.
- Stale fixtures cleaned; parser tests green.
- Thread 4 either validated on testnet (trade key) or documented + left runnable (read-only key).
- A short runbook so the order-flow piece can be re-run with a trade key.

## Out of scope

- Mainnet / real-money anything.
- Other asset-class epics (#74 equities, #75 futures).
- New strategy features — this exercises existing code, it does not extend it.
