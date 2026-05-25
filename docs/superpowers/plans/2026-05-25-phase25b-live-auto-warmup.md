# Phase 25B — Live auto-warmup: implementation plan

**Spec:** `docs/superpowers/specs/2026-05-25-phase25b-live-auto-warmup-design.md`
**Issue:** [#125](https://github.com/elitekaycy/qkt/issues/125)
**Branch:** `issue125-live-auto-warmup`

## Build order

Foundations first (interfaces + new APIs, no behavior change yet), then bridge DSL, then wire pipeline, then error handling, then end-to-end tests, then docs.

Each task: failing test → implementation → passing test → commit. Tasks 1-5 are mechanical; 6-9 carry the correctness traps called out in the spec.

## Files touched

**Create:**
- `src/main/kotlin/com/qkt/strategy/PerStreamWarmable.kt`
- `src/main/kotlin/com/qkt/dsl/compile/WarmupRequirements.kt`
- `src/main/kotlin/com/qkt/app/WarmupFailedException.kt`
- `src/test/kotlin/com/qkt/strategy/PerStreamWarmableTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/CandleHubSeedTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/WarmupRequirementsTest.kt`
- `src/test/kotlin/com/qkt/app/IndicatorWarmerPerStreamTest.kt`
- `src/test/kotlin/com/qkt/app/LiveAutoWarmupColdBootTest.kt`
- `src/test/kotlin/com/qkt/app/LiveAutoWarmupHotAddTest.kt`
- `src/test/kotlin/com/qkt/app/LiveAutoWarmupBrokerFailureTest.kt`
- `docs/phases/phase-25b-live-auto-warmup.md`

**Modify:**
- `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt` — add `seed(key, candles)`
- `src/main/kotlin/com/qkt/app/IndicatorWarmer.kt` — add per-stream `warmup(perStream, now)` form
- `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` — `CompiledStrategy implements PerStreamWarmable`, compute requirements
- `src/main/kotlin/com/qkt/app/LiveSession.kt` — branch on `PerStreamWarmable`, seed hub before warmup
- `src/main/kotlin/com/qkt/app/TradingPipeline.kt` — feed `WarmupGate` from warmup-tick path
- `docs/phases/phase-24-risk-sizing-primitives.md` — remove "live closed candles only" caveat
- `docs/reference/dsl/streams.md` — note auto-prefetch

---

## Task 1: `PerStreamWarmable` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/PerStreamWarmable.kt`
- Create: `src/test/kotlin/com/qkt/strategy/PerStreamWarmableTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package com.qkt.strategy

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerStreamWarmableTest {
    @Test
    fun `empty per-stream map means no warmup`() {
        val w = object : PerStreamWarmable { override val perStreamWarmup = emptyMap<String, WarmupSpec>() }
        assertThat(w.perStreamWarmup).isEmpty()
    }

    @Test
    fun `per-stream warmup specs survive interface destructuring`() {
        val spec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 50)
        val w = object : PerStreamWarmable { override val perStreamWarmup = mapOf("BACKTEST:BTCUSDT" to spec) }
        assertThat(w.perStreamWarmup["BACKTEST:BTCUSDT"]).isEqualTo(spec)
    }
}
```

- [ ] **Step 2: Create interface**

```kotlin
package com.qkt.strategy

/**
 * Per-stream warmup spec — extends [Warmable] for strategies that need different
 * warmup windows on different streams (e.g. 5m gold + 1h spx).
 *
 * The single-spec [Warmable] interface stays as a legacy fallback. Callers should
 * prefer [PerStreamWarmable] when both are available.
 */
interface PerStreamWarmable {
    /** Map from qkt symbol (e.g. "EXNESS:XAUUSD") to its required warmup. */
    val perStreamWarmup: Map<String, WarmupSpec>
}
```

- [ ] **Step 3: Commit**

`feat(strategy): add PerStreamWarmable for multi-stream warmup specs`

---

## Task 2: `CandleHub.seed` prepend-only API

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/CandleHubSeedTest.kt`

- [ ] **Step 1: Failing test**

Test cases:
- `seed` on an unregistered key throws.
- `seed` on a registered key with empty ring populates from oldest to newest.
- `seed` prepends only — candles older than the ring's oldest go in; candles overlapping or newer are dropped.
- `seed` respects retention — only the most recent `retention` bars are kept.
- `seed` does not fire `onClosed` callbacks.

```kotlin
@Test
fun `seed populates an empty ring up to retention`() {
    val hub = CandleHub()
    val key = HubKey("BACKTEST", "BTCUSDT", "1m")
    hub.register(key, retention = 10, owner = "strategy-a")
    val candles = (0..4).map { Candle("BACKTEST:BTCUSDT", BigDecimal("100"), BigDecimal("110"),
        BigDecimal("90"), BigDecimal("105"), BigDecimal("1"), it * 60_000L, (it + 1) * 60_000L) }
    hub.seed(key, candles)
    assertThat(hub.history(key, 0)?.startTime).isEqualTo(4 * 60_000L)
    assertThat(hub.history(key, 4)?.startTime).isEqualTo(0L)
}
```

- [ ] **Step 2: Implement**

Add `fun seed(key: HubKey, candles: List<Candle>)` on `CandleHub`. Sort `candles` by `startTime`. If the ring already has data, drop input candles whose `startTime >= oldestExistingStartTime`. Prepend remaining candles older than existing. Truncate to retention. Do **not** invoke `onClosed` callbacks.

- [ ] **Step 3: Verify + commit**

Run targeted test. `feat(dsl): add CandleHub.seed for prepend-only history seeding`

---

## Task 3: Per-stream form of `IndicatorWarmer.warmup`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/IndicatorWarmer.kt`
- Create: `src/test/kotlin/com/qkt/app/IndicatorWarmerPerStreamTest.kt`

- [ ] **Step 1: Failing test**

Inject a fake `MarketSource` that records which (symbol, window, range) combos `bars()` was called with. Test:
- Per-stream warmup with two symbols at different windows → two distinct `bars(...)` calls with correct windows.
- Per-stream warmup with `WarmupSpec.None` for one symbol → no `bars()` call for that symbol.
- Per-stream warmup feeds `pipeline.ingestForWarmup(tick)` for each fetched candle.

- [ ] **Step 2: Implement**

```kotlin
fun warmup(perStream: Map<String, WarmupSpec>, now: Instant) {
    for ((symbol, spec) in perStream) {
        val resolved = resolveBarSpec(spec) ?: continue
        warmupSymbol(symbol, resolved, now)
    }
}
```

Keep existing `warmup(symbols, spec, now)` as a thin wrapper:

```kotlin
fun warmup(symbols: List<String>, spec: WarmupSpec, now: Instant) =
    warmup(symbols.associateWith { spec }, now)
```

- [ ] **Step 3: Verify + commit**

`feat(app): IndicatorWarmer supports per-stream warmup specs`

---

## Task 4: `WarmupRequirements` — compute bars-needed per alias

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/WarmupRequirements.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/WarmupRequirementsTest.kt`

- [ ] **Step 1: Failing test**

Cases:
- Empty strategy → empty map.
- `WARMUP 50 BARS` on a stream → 50.
- `EMA(close, 200)` on a stream with no explicit WARMUP → 200.
- `WARMUP 30 BARS` + `EMA(close, 100)` → 100 (max wins).
- Lookback `btc.close[20]` → 21 (N + 1 for current bar).
- Rolling `LET x = btc.close[5..10]` → 10.
- Two streams with different indicators → distinct entries per alias.

- [ ] **Step 2: Implement**

```kotlin
object WarmupRequirements {
    fun compute(ast: StrategyAst, plan: SnapshotPlan, bindings: IndicatorBinding.Bag): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (stream in ast.streams) {
            val explicit = stream.warmupBars ?: 0
            val indicator = bindings.maxPeriodForAlias(stream.alias)
            val lookback = plan.rollingMaxN.entries
                .filter { /* alias filter */ }
                .maxOfOrNull { it.value + 1 } ?: 0
            val needed = maxOf(explicit, indicator, lookback)
            if (needed > 0) out[stream.alias] = needed
        }
        return out
    }
}
```

Returns alias→bars-needed (alias, not qktSymbol). Caller translates via `streams: Map<String, HubKey>`.

- [ ] **Step 3: Verify + commit**

`feat(dsl): add WarmupRequirements derivation`

---

## Task 5: `CompiledStrategy` implements `PerStreamWarmable`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`

- [ ] **Step 1: Add field**

In `AstCompiler.compile()`, after `metaRefs = collectMetaRefs(...)` and before `return CompiledStrategy(...)`:

```kotlin
val warmupRequirements = WarmupRequirements.compute(ast, plan, bindings)
val perStreamWarmupSpec: Map<String, WarmupSpec> =
    warmupRequirements.mapNotNull { (alias, bars) ->
        val key = streams[alias] ?: return@mapNotNull null
        val window = TimeWindow.parse(key.timeframe)
        key.qktSymbol to WarmupSpec.Bars(window, bars)
    }.toMap()
```

Pass `perStreamWarmupSpec` to `CompiledStrategy` constructor. CompiledStrategy implements `PerStreamWarmable` returning this field.

- [ ] **Step 2: Test integration via Task 8/9 end-to-end tests**

No new unit test for this task in isolation — the integration tests cover it. Quick local sanity:

```kotlin
val s = AstCompiler().compile(Dsl.parse("... WARMUP 50 BARS ...").asSuccess().value)
assertThat((s as PerStreamWarmable).perStreamWarmup).hasSize(1)
```

- [ ] **Step 3: Commit**

`feat(dsl): CompiledStrategy implements PerStreamWarmable`

---

## Task 6: Feed `WarmupGate` from warmup ticks

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt` (or `AstCompiler.kt` — TBD by inspection)

**Pre-step (5 min): inspect TradingPipeline's WarmupTickEvent subscription.** Determine the cleanest seam to pump per-alias `WarmupGate.onClosedCandle(alias)` for each warmup tick. Candidate seams:

- (a) In `pipeline.ingestForWarmup(tick)`, look up which alias maps to the tick's symbol, increment the gate.
- (b) In `CompiledStrategy.evaluate()` path — but warmup ticks bypass `evaluate()`, so this needs a new seam there.

Pick (a) unless the symbol→alias mapping isn't available at the pipeline level (then pick a CompiledStrategy-side hook).

- [ ] **Step 1: Failing test**

```kotlin
@Test
fun `warmup ticks increment WarmupGate`() {
    // Compile strategy with WARMUP 5 BARS on alias g
    // Push 5 warmup ticks for g's symbol via pipeline.ingestForWarmup
    // Assert: warmupGate.isWarm("g") returns true
}
```

- [ ] **Step 2: Implement**

Per the chosen seam. Update one place; verify the test passes.

- [ ] **Step 3: Run all DSL tests**

`./gradlew test --tests 'com.qkt.dsl.*' --tests 'com.qkt.app.*'`

- [ ] **Step 4: Commit**

`feat(app): feed WarmupGate from warmup-tick pipeline`

---

## Task 7: Seed `CandleHub` before warmup in `LiveSession.start()`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`

- [ ] **Step 1: Sequence the operations**

In `start()`, for each strategy:

```
1. compile (existing)
2. hub.register(key, retention, strategyId)  (existing)
3. NEW: if strategy is PerStreamWarmable, for each (symbol, spec) fetch bars
        + hub.seed(key, fetched)
4. warmer.warmup(perStreamSpec, now)  (existing path, now via Task 3)
5. strategy.bindToHub(...)            (existing)
6. live tick loop                     (existing)
```

- [ ] **Step 2: Branch on `PerStreamWarmable` vs legacy `Warmable`**

```kotlin
val perStreamSpec: Map<String, WarmupSpec> =
    when (val s = strategy) {
        is PerStreamWarmable -> s.perStreamWarmup
        is Warmable -> symbols.associateWith { s.warmup }
        else -> emptyMap()
    }
```

- [ ] **Step 3: Verify legacy strategies still work**

Run any test in `src/test/kotlin/com/qkt/app/` that exercises non-DSL `Warmable` strategies.

- [ ] **Step 4: Commit**

`feat(app): seed CandleHub then warm indicators in LiveSession.start`

---

## Task 8: `WarmupFailedException` + fail-fast wrapper

**Files:**
- Create: `src/main/kotlin/com/qkt/app/WarmupFailedException.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`

- [ ] **Step 1: Exception**

```kotlin
package com.qkt.app

class WarmupFailedException(
    val streamAlias: String,
    val qktSymbol: String,
    cause: Throwable,
) : RuntimeException(
    "qkt: failed to fetch warmup history for stream '$streamAlias' ($qktSymbol) — " +
        "broker historical API returned: ${cause.message}. Deploy aborted. " +
        "Retry after fixing the broker connection, or remove WARMUP / reduce indicator periods.",
    cause,
)
```

- [ ] **Step 2: Wrap warmer + seed calls**

In `LiveSession.start()`, wrap each per-stream fetch in try/catch, throw `WarmupFailedException(alias, qktSymbol, e)`.

- [ ] **Step 3: Verify deploy path surfaces the error**

Hand-test: a fake source that throws on `bars(...)`. Verify deploy exits non-zero with the typed message.

- [ ] **Step 4: Commit**

`feat(app): fail deploy with WarmupFailedException on broker fetch error`

---

## Task 9: Cold-boot integration test

**Files:**
- Create: `src/test/kotlin/com/qkt/app/LiveAutoWarmupColdBootTest.kt`

A faithful end-to-end test using `Backtest`-like wiring isn't possible here (live engine has different surface). Instead, wire a fake `MarketSource` + `CandleHub` + compile a DSL strategy with `EMA(close, 50)` + start a `LiveSession`. Assert:

- After `start()` returns (warmup completed), the EMA indicator binding's `isReady` is true.
- `warmupGate.isWarm(alias)` is true.
- `hub.history(key, 0)` is non-null with the last bar of the warmup range.
- First synthetic *live* tick fires the rule immediately (no further wait).

- [ ] Commit: `test(app): cold-boot live auto-warmup end-to-end`

---

## Task 10: Hot-add integration test

**Files:**
- Create: `src/test/kotlin/com/qkt/app/LiveAutoWarmupHotAddTest.kt`

Setup: shared CandleHub, strategy A already running (no warmup, accumulated 30 live bars), then deploy strategy B (`btc.close[200]`).

Assert:
- B's warmup fetches 200 bars from the fake source.
- A's `bindings.updateForAlias` was NOT called during B's warmup (A's pipeline is isolated).
- Hub now retains 200 bars (grew from 30); the 30 existing bars are still the most recent.
- B's indicator state is warm; A's untouched.

- [ ] Commit: `test(app): hot-add live auto-warmup preserves running strategies`

---

## Task 11: Broker-failure integration test

**Files:**
- Create: `src/test/kotlin/com/qkt/app/LiveAutoWarmupBrokerFailureTest.kt`

Fake source throws on `bars(...)`. Deploy a DSL strategy. Assert:
- `LiveSession.start()` throws `WarmupFailedException` with the expected stream alias + symbol.
- No `onClosed` callback registered on the hub (no half-bound state).
- Existing strategies on the daemon (if any) unaffected.

- [ ] Commit: `test(app): broker fetch failure aborts deploy cleanly`

---

## Task 12: Phase 25B changelog

**Files:**
- Create: `docs/phases/phase-25b-live-auto-warmup.md`
- Modify: `docs/phases/index.md` (add entry)
- Modify: `docs/phases/phase-24-risk-sizing-primitives.md` (remove the "live closed candles only" caveat)

- [ ] Commit: `docs(phases): add phase 25B changelog and remove phase 24 caveat`

---

## Task 13: Streams doc note

**Files:**
- Modify: `docs/reference/dsl/streams.md`

Update the `WARMUP N BARS` section to mention auto-prefetch:

> Phase 25B (live): on deploy, the engine fetches the requested history from the broker's historical API and seeds the candle hub + indicators. Rules fire on the first live closed candle. If the broker can't satisfy the fetch (rate limit, auth, missing data), deploy aborts with `WarmupFailedException`.

- [ ] Commit: `docs(dsl): note auto-prefetch behavior for WARMUP N BARS`

---

## Final checks

- `./gradlew test` — full suite green.
- `./gradlew ktlintCheck` — clean.
- `git log --oneline dev..HEAD` — every commit message follows the §3 conventions, no AI footers.
- Push branch, open PR against `dev`, watch CI.

## Plan-level risks worth re-noting

- **Task 6 seam choice** is the only place where I'd defer to inspection rather than picking up front. If `pipeline.ingestForWarmup()` doesn't have ready access to the alias map, the seam has to move into `CompiledStrategy`. Both options are mentioned; pick at code time.
- **`TimeWindow.parse(key.timeframe)` in Task 5** may not exist — verify; if not, route via the broker profile or a small helper in `CandleHub`.
- **Tasks 9-11 are integration tests** and will be slowest. Run them last after the unit-test foundations pass.
