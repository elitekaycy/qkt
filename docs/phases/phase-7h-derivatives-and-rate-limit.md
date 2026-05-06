# Phase 7h — Bybit Derivatives + Rate Limiting

**Status:** Shipped. Merged into `main` on (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase7h-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7h-design.md)

## Summary

Phase 7h ships USDT perpetuals via `BybitLinearBroker`, broker-authoritative position reconciliation, reactive 429 handling with bounded sleep, paginated execution recovery (200-record cap), configurable account type, and an extracted `BrokerStateRecovery` interface. Spot reconciliation continues to use orders + executions + balances; linear adds a fourth path (`/v5/position/list`) where directional position math is honest. The engine can now trade BTCUSDT perpetuals alongside spot through a single `CompositeBroker` route addition.

## What's new

- `com.qkt.broker.BrokerStateRecovery` — interface with `fun reconcile()`. Extracted from the two real impls; not designed in advance.
- `com.qkt.broker.bybit.BybitSpotStateRecovery` — renamed from `BybitStateRecovery`; implements `BrokerStateRecovery`.
- `com.qkt.broker.bybit.BybitLinearStateRecovery` — new, four reconcile paths (orders + executions + balances + positions). Tolerance-aware position diffing.
- `com.qkt.broker.bybit.BybitLinearBroker` — Bybit USDT perpetuals broker. Symbol prefix `BYBIT_LINEAR:`. Mirrors `BybitSpotBroker` shape.
- `com.qkt.broker.bybit.BybitRateLimitException` — thrown on 429 when reset exceeds 5s cap or persists after one retry.
- `com.qkt.broker.bybit.BybitClient.postSigned` — reactive 429 handling: parse `X-Bapi-Limit-Reset-Timestamp`, sleep up to 5s, retry once; falls through to `BybitRateLimitException` otherwise.
- `com.qkt.broker.bybit.BybitClient(accountType: String? = null)` — constructor parameter, defaults via `BYBIT_ACCOUNT_TYPE` env var, falls back to `UNIFIED`.
- `BybitTransport.accountType: String` — surface for recovery to read account type without hardcoding.
- `BybitOrderTranslator.toCreateBody(request, reduceOnly = false)` — gains optional `reduceOnly` parameter. Linear orders also gain `positionIdx=0` automatically (one-way mode).
- `BybitSpotStateRecovery.reconcileExecutions` and `BybitLinearStateRecovery.reconcileExecutions` — paginated via `nextPageCursor`, capped at `MAX_EXECUTIONS_PER_RECONCILE = 200` records.
- `BrokerEvent.PositionReconciled` — new variant. `(symbol, oldQty?, newQty, oldAvgPx?, newAvgPx, source, reason)`. Old fields nullable for "we didn't know we had this position".
- `PositionTracker.reset(symbol, qty, avgPx)` — broker-authoritative resync. Removes the entry when `qty == 0`.
- `TradingPipeline` subscribes `BrokerEvent.PositionReconciled` → `PositionTracker.reset`.
- `FakeBybitClient` extended:
  - `accountType: String = "UNIFIED"` (overridable per test)
  - `responsesByPredicate: MutableList<Pair<(String, String) -> Boolean, String>>` for context-aware static responses
  - `dynamicResponses: MutableList<Pair<(String, String) -> Boolean, () -> String>>` for per-call computed responses (used for cursor pagination tests)

## Migration from previous phase

| 7g | 7h | Notes |
|---|---|---|
| `BybitStateRecovery` | renamed to `BybitSpotStateRecovery` | Implements new `BrokerStateRecovery` interface. Class file moved alongside same package. |
| `BybitOrderTranslator.toCreateBody(request)` | `toCreateBody(request, reduceOnly: Boolean = false)` | Default value preserves existing call sites. Linear orders auto-append `positionIdx`. |
| `BybitTransport` had 8 members | gains `accountType: String` (read-only) | `FakeBybitClient` defaults to `"UNIFIED"`. Custom transport implementors add the new property. |
| `BybitClient.postSigned` retried on conn failures and BybitApiException | now also handles 429 reactively | Backwards compatible — successful calls and BybitApiException paths unaffected. |
| `BrokerEvent` had 7 variants | gains `PositionReconciled` (sibling of `OrderEvent` / `BalancesUpdated`) | Existing subscribers unaffected. |
| `PositionTracker` was fill-only | gains `reset(symbol, qty, avgPx)` | Wired in `TradingPipeline`; downstream entry points (`Main`, `LiveSession`, `Backtest`) inherit via pipeline. |
| `/v5/execution/list` was single-page | now paginated with 200-record cap | Transparent to broker callers; recovery transparently follows `nextPageCursor`. |
| `accountType=UNIFIED` hardcoded | configurable via `BYBIT_ACCOUNT_TYPE` env var or `BybitClient(accountType=...)` | Default unchanged; existing callers inherit the same behavior. |

No engine, strategy, or DSL surface changes.

## Usage cookbook

### 1. Construct `BybitLinearBroker` and add to composite

```kotlin
val client = BybitClient()                    // testnet default; same client serves spot and linear
client.connect()

val positions = PositionTracker()
val bus = EventBus(clock, sequencer)

val spot = BybitSpotBroker(client, bus, clock)
val linear = BybitLinearBroker(client, bus, clock, positions)

val composite = CompositeBroker(
    routes = listOf(
        SymbolPattern.prefix("BYBIT_SPOT:")   to spot,
        SymbolPattern.prefix("BYBIT_LINEAR:") to linear,    // NEW
    ),
    fallback = paperBroker,
    bus = bus,
)
```

The composite routes by symbol prefix automatically. Strategies submitting `BYBIT_LINEAR:BTCUSDT` orders flow through `BybitLinearBroker`; `BYBIT_SPOT:BTCUSDT` flows through `BybitSpotBroker`. No engine logic changes.

### 2. Subscribe to `PositionReconciled` for monitoring drift

```kotlin
bus.subscribe<BrokerEvent.PositionReconciled> { event ->
    val from = event.oldQty?.toPlainString() ?: "(none)"
    log.warn("Position drift {}: {} → {} (reason: {})",
        event.symbol, from, event.newQty.toPlainString(), event.reason)
}
```

`TradingPipeline` already wires the subscription that resets `PositionTracker`. Custom monitoring is opt-in alongside it.

### 3. Override `BYBIT_ACCOUNT_TYPE` for legacy accounts

```bash
# For accounts that haven't been migrated to Unified:
export BYBIT_ACCOUNT_TYPE=CONTRACT
./gradlew run
```

Or programmatically:

```kotlin
val client = BybitClient(accountType = "CONTRACT")
```

The wallet-balance endpoint requires the right account type; `UNIFIED` returns empty for legacy accounts.

### 4. Catch `BybitRateLimitException` in a strategy

```kotlin
try {
    broker.submit(orderRequest)
} catch (e: BybitRateLimitException) {
    log.warn("Rate limit hit; backing off for next bar")
    // Strategy decides: retry next tick, drop, escalate
}
```

`postSigned` already retries once with bounded sleep. The exception only fires when the rate limit can't clear within 5s OR persists after the retry. Most submits never see it.

### 5. Trade BTCUSDT perpetual with Limit + Stop

```kotlin
emit(Signal.Submit(
    OrderRequest.Limit(
        id = ids.next(),
        symbol = "BYBIT_LINEAR:BTCUSDT",
        side = Side.BUY,
        quantity = Money.of("0.01"),
        limitPrice = Money.of("80000"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
    ),
))

emit(Signal.Submit(
    OrderRequest.Stop(
        id = ids.next(),
        symbol = "BYBIT_LINEAR:BTCUSDT",
        side = Side.SELL,
        quantity = Money.of("0.01"),
        stopPrice = Money.of("75000"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
    ),
))
```

`BybitLinearBroker` advertises `MARKET`, `LIMIT`, `STOP`, `STOP_LIMIT`, `IF_TOUCHED`, `MODIFY` — same surface as spot. Bracket / OCO are decomposed by `OrderManager` (Phase 7d-b) into native primitives; strategies don't think about it.

### 6. Reactive 429 with custom HTTP client (testing pattern)

```kotlin
val httpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        if (firstCall) {
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests")
                .header("X-Bapi-Limit-Reset-Timestamp", "100")  // 100ms after epoch
                .body("...".toResponseBody())
                .build()
        } else {
            // succeed
        }
    }.build()

val client = BybitClient(httpClient = httpClient, clock = FixedClock(0L))
client.postSigned("/v5/order/create", "{}")    // sleeps 100ms then succeeds
```

The `clock` injection lets tests control the apparent time delta to the reset header.

## Testing patterns

- **`FakeBybitClient.responsesByPredicate`** — register `(path, body) -> Boolean` predicate paired with a static response. Useful for cursor-aware pagination tests.
- **`FakeBybitClient.dynamicResponses`** — register predicate paired with a `() -> String` factory. Each call invokes the factory, allowing per-call computed responses (counter-based pages, etc.).
- **`/v5/position/list` response shape** — `{ symbol, side: "Buy"|"Sell", size: "0.5", avgPrice: "80000", category: "linear" }`. Sign convention: Sell side becomes negative qty in engine via `signed = if (side == "Sell") size.negate() else size`.
- **429 simulation pattern** — OkHttp interceptor returning `Response.Builder().code(429).header("X-Bapi-Limit-Reset-Timestamp", ...)`. Combine with `FixedClock(0L)` to control the apparent time delta.
- **`FixedPositionProvider`** — tests pass an in-line `PositionProvider` impl backed by a `Map<String, Position>` to drive reconcile diff scenarios.

## Known limitations

- **No inverse perpetuals (`BYBIT_INVERSE:`).** Base-coin margin (BTC margin for BTCUSD) requires per-coin position math; spot/linear reconciliation patterns don't transfer cleanly. Future phase if demand exists.
- **No account state polling.** Equity, margin level, free margin, total uPnL, leverage settings — all derivatives concerns that pair with Phase 8 PnL framework. Currently only wallet balances are observable.
- **No hedge mode.** One-way mode only (`positionIdx=0`). Hedge mode allows simultaneous long+short on the same symbol, which mismatches `PositionTracker`'s single-position-per-symbol model.
- **No pre-emptive rate-limit token bucket.** Reactive only — `postSigned` doesn't track quota state, just reacts to 429s. Sufficient for current scale (~6 reconcile calls/min in steady state); pre-emptive bucketing is over-engineering until measured pressure exists.
- **No leverage / margin mode configuration.** Bybit defaults apply (cross margin; isolated set per-symbol via Bybit UI).
- **No funding rate tracking.** Periodic funding payments / receipts on perpetuals are informational; not surfaced today.
- **`OrderTypeCapability.REDUCE_ONLY` and `POST_ONLY` not in capability enum.** `BybitOrderTranslator.toCreateBody(reduceOnly = true)` works, but engine dispatch via `OrderManager` doesn't consume these flags from strategies yet. Phase 8+ work.
- **Position reconciliation for spot remains absent.** Spot pair-net (engine) ≠ wallet coin balance (broker). Honest reconciliation is impossible without baseline tracking; intentionally skipped (see 7g spec).
- **No retry budget for pagination loop failures.** If `/v5/execution/list` fails mid-loop, recovery logs and gives up; next periodic tick retries from the latest `lastFillTime`.
- **`BybitLinearBroker.knownOrders` typed as `BybitSpotStateRecovery.ManagedOrderView`.** Quick wart — the data class lives on the spot recovery class but is generic. Could move to a shared package when it matters; deferred.
- **Reconcile cadence uniform across paths.** Orders/executions/balances/positions all hit on the same 30s tick. Splitting cadences (e.g., positions every 10s, balances every 60s) is possible but complex; uniform until profiling shows pressure.
- **No JVM-restart persistence.** Same as 7f/7g; manual restart picks up from Bybit's canonical state via initial reconcile.

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase7h-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7h-design.md)
- Plan: [`../superpowers/plans/2026-05-06-trading-engine-phase7h.md`](../superpowers/plans/2026-05-06-trading-engine-phase7h.md)
- Phase 7g baseline: [`phase-7g-reconciliation-and-balances.md`](phase-7g-reconciliation-and-balances.md)
- Bybit V5 position API: https://bybit-exchange.github.io/docs/v5/position
- Bybit V5 rate limits: https://bybit-exchange.github.io/docs/v5/rate-limit
