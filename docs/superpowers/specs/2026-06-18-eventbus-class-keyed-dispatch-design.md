# EventBus class-keyed dispatch — Design

Date: 2026-06-18
Status: approved, pre-implementation
Scope of this spec: replace `EventBus`'s per-publish Kotlin-reflection type retrieval + `KClass`-keyed subscriber lookup with a `Class`-keyed equivalent, removing hot-path overhead from the engine's most-traversed line. Dispatch semantics — order, `subscribeFirst`, per-subscriber exception isolation, event stamping — are unchanged. Backtest=live parity is preserved by construction.

---

## 1. Purpose

`EventBus.publish` runs on every published event — ticks, candles, signals, orders, fills — so in a backtest it executes millions of times. JFR profiling of the deployed engine (after the earlier hot-path round) showed `publish` doing a per-call `subscribers[stamped::class]` lookup, where `stamped::class` is a Kotlin-reflection `KClass` retrieval rather than a plain class reference.

This change swaps the lookup to a `Class`-keyed map read off `stamped.javaClass` (a JVM field access). It is a pure lookup-mechanism change: what gets dispatched, to whom, and in what order is untouched.

Non-goals (§5): per-tick **symbol-keyed** map lookups (`priceTracker[symbol]`, the order index, the candle index) — the real source of the profile's `String.hashCode` — and the `OrderManager.evaluateTriggers` per-tick allocations. Both are separate, broader levers.

---

## 2. The cost

`EventBus` keys subscribers by `KClass`:

```kotlin
internal val subscribers = mutableMapOf<KClass<out Event>, MutableList<(Event) -> Unit>>()
```

and `publish` looks up by the published event's Kotlin class:

```kotlin
val handlers = subscribers[stamped::class] ?: return
```

`stamped::class` does not return a cheap class reference; it goes through Kotlin reflection (`getOrCreateKotlinClass`) to produce a `KClass` wrapper for the concrete type, on every publish. That retrieval is the per-publish overhead the profile attributes to `EventBus.publish`. The map lookup itself is cheap — `KClass`/`Class` hashCode is identity-based — so the lookup *key type* is not the problem; the *type retrieval* is.

(The profile's larger `String.hashCode` slice is unrelated: it is per-tick symbol-keyed map lookups elsewhere in the pipeline, not `EventBus`. Class/KClass hashing involves no strings.)

---

## 3. Design — `Class`-keyed dispatch

A single-file change in `EventBus.kt`.

**Subscriber map** — re-keyed from `KClass` to `Class`:

```kotlin
@PublishedApi
internal val subscribers = mutableMapOf<Class<out Event>, MutableList<(Event) -> Unit>>()
```

It stays `@PublishedApi internal` because the `inline`/`reified` `subscribe` functions reference it from call sites.

**Registration** — `subscribe<T>` / `subscribeFirst<T>` resolve the concrete class via the reified type:

```kotlin
inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    subscribers.getOrPut(T::class.java) { mutableListOf() }.add { event -> handler(event as T) }
}
// subscribeFirst identical, with .add(0) { ... }
```

`T::class.java` is resolved at each call site to the concrete `Class` — same key the dispatch will use, same `getOrPut`/`add`/`add(0)` semantics.

**Dispatch** — `publish` keys off `javaClass`:

```kotlin
val handlers = subscribers[stamped.javaClass] ?: return
```

`stamped.javaClass` is a direct `Object.getClass()` field access — no reflection wrapper.

**Unchanged:** `stamp()`, the off-thread `offThreadSink`/`engineThread` reroute, the index-loop over `handlers` (no iterator allocation), per-subscriber try/catch isolation with first-failure rethrow, registration order, and `subscribeFirst` head-insertion. Only the key type and the per-publish type-retrieval change.

---

## 4. Why it is parity-neutral

- `stamped.javaClass` is exactly the concrete runtime class that `stamped::class` wrapped — the same identity used as the map key.
- All subscriptions are to **concrete leaf event types** (verified across `src/main`: `TickEvent`, `BrokerEvent.OrderFilled`, `RiskEvent.Halted`, … — no subscriptions to a sealed parent like `Event` or `BrokerEvent`). The bus already matches by concrete class, so no superclass-subscription behavior exists to preserve or break.
- The handler lists, their order, head-insertion via `subscribeFirst`, and the dispatch/exception semantics are identical.

Therefore a backtest produces a bit-identical result; this is the acceptance gate.

---

## 5. Scope and non-goals

In scope:
- Re-key `subscribers` to `Class<out Event>`.
- `subscribe` / `subscribeFirst` use `T::class.java`.
- `publish` uses `stamped.javaClass`.
- Keep the existing `EventBus` tests green; bit-identical backtest gate.

Out of scope (separate, larger levers):
- Per-tick symbol-keyed map lookups (`priceTracker`, live-order index, candle index) — the profile's `String.hashCode`.
- `OrderManager.evaluateTriggers` per-tick list allocations.
- A typeId/array or EnumMap dispatch (Approach B/C) — only revisit if a re-profile after this change still shows the `Class`-map lookup as material.
- Any change to event stamping, the off-thread sink, or the live engine-loop binding.

---

## 6. Testing and verification

- **Existing `EventBus` tests** must stay green (registration order, `subscribeFirst` ordering, per-subscriber isolation + rethrow, off-thread reroute).
- **Bit-identical backtest gate:** the backtest determinism/e2e suites must pass unchanged; a real-data backtest must remain byte-identical to before (the standing parity check used in the earlier hot-path rounds).
- **ktlint** clean.
- **JFR re-profile** on bot2 (build-from-source path, since the `:edge` runtime lacks `jdk.jfr`): confirm the `::class` retrieval is gone from `EventBus.publish`. If the residual `Class`-map lookup is still material, that is the trigger to consider Approach B (typeId/array) in a follow-up — not now.

---

## 7. References

- Profiling method + prior hot-path rounds: memory `project_sweep_fanout_2026_06_17` (HOT-PATH sections).
- Code: `src/main/kotlin/com/qkt/bus/EventBus.kt`, `src/main/kotlin/com/qkt/events/Event.kt`.
