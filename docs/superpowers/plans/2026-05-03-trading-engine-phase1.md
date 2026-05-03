# Trading Engine Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Phase 1 trading engine: tick generation → engine → strategy → mock broker, fully tested, end-to-end `main()` prints fills.

**Architecture:** Single-threaded event-driven pipeline in Kotlin. Strategy emits `Signal`s via callback; `Engine` converts to `Order`s and routes to `Broker`; broker fills using a shared `MarketPriceProvider`. All foundations (`Clock`, `IdGenerator`, pull-feed) chosen to make Phase 4 backtest determinism trivial.

**Tech Stack:** Kotlin 2.1.0, JDK 21 (via Gradle toolchain), Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0.

**Spec:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase1-design.md`](../specs/2026-05-03-trading-engine-phase1-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` (git already initialized, branch `main`, spec committed at `ad376a6`).

---

## Task ordering (dependency-respecting)

```
1. Gradle scaffold
2. common.Side
3. common.Clock
4. common.IdGenerator
5. Migrate Tick + Candle (replace broken old files)
6. marketdata.TickFeed (interface)
7. marketdata.MarketPriceProvider + Tracker  [TDD]
8. marketdata.MockTickFeed                    [TDD]
9. execution.OrderType
10. execution.Order
11. execution.Trade
12. strategy.Signal
13. strategy.Strategy (interface)
14. strategy.EveryNthTickBuyStrategy          [TDD]
15. broker.Broker (interface)
16. broker.MockBroker                         [TDD]
17. engine.Engine                             [TDD]
18. app.Main + smoke run
19. Final verification
```

`[TDD]` tasks: write failing test → verify fail → implement → verify pass → commit.
Non-TDD tasks: trivial types (enums, data classes, single-method interfaces) where the test would only restate the type. They are exercised indirectly by downstream `[TDD]` tasks.

---

## Task 1: Gradle project scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `.gitignore`
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` (via `gradle wrapper`)

- [ ] **Step 1: Create `.gitignore`**

`.gitignore`:
```
.gradle/
build/
.idea/
*.iml
local.properties
.DS_Store
out/
```

- [ ] **Step 2: Create `settings.gradle.kts`**

`settings.gradle.kts`:
```kotlin
rootProject.name = "qkt"
```

- [ ] **Step 3: Create `build.gradle.kts`**

`build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.qkt"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.qkt.app.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(21)
}
```

*Note: `mainClass` ends in `MainKt` because Kotlin compiles top-level `fun main()` in `Main.kt` into a synthetic class named `MainKt`.*

- [ ] **Step 4: Generate Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.14.4`
Expected: creates `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`. No errors.

- [ ] **Step 5: Verify wrapper works**

Run: `./gradlew --version`
Expected: shows `Gradle 8.14.4`, `Kotlin: 2.0.21` (Gradle's bundled Kotlin — not what we use for our code), `JVM: 23.0.2` (or whatever local JDK is — fine).

- [ ] **Step 6: Verify build runs with no source**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Will skip `compileKotlin` and `test` because there are no sources yet. Gradle may download JDK 21 toolchain on first run (one-time, ~150MB).

- [ ] **Step 7: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle/ gradlew gradlew.bat
git status
git commit -m "build: gradle scaffold with kotlin 2.1 + jdk 21"
```
Expected: a single commit with the scaffold files. `git log --oneline` shows two commits: spec + this scaffold.

---

## Task 2: `common.Side` enum

**Files:**
- Create: `src/main/kotlin/com/qkt/common/Side.kt`

- [ ] **Step 1: Create `Side.kt`**

`src/main/kotlin/com/qkt/common/Side.kt`:
```kotlin
package com.qkt.common

enum class Side { BUY, SELL }
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. One Kotlin file compiled.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/common/Side.kt
git commit -m "feat(common): add Side enum"
```

---

## Task 3: `common.Clock` interface and impls

**Files:**
- Create: `src/main/kotlin/com/qkt/common/Clock.kt`

- [ ] **Step 1: Create `Clock.kt`**

`src/main/kotlin/com/qkt/common/Clock.kt`:
```kotlin
package com.qkt.common

interface Clock {
    fun now(): Long
}

class SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class FixedClock(var time: Long = 0L) : Clock {
    override fun now(): Long = time
}
```

*Notes for Java devs:*
- *Three top-level declarations in one file is fine in Kotlin (top-level functions/classes don't need to live in a class with the same name as the file).*
- *`var time: Long = 0L` declares a mutable property with default value `0L`. The `L` suffix is a long literal, same as Java.*
- *`override fun now(): Long = time` — single-expression function form, equivalent to `override fun now(): Long { return time }`.*

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/common/Clock.kt
git commit -m "feat(common): add Clock interface with SystemClock and FixedClock"
```

---

## Task 4: `common.IdGenerator` interface and impl

**Files:**
- Create: `src/main/kotlin/com/qkt/common/IdGenerator.kt`

- [ ] **Step 1: Create `IdGenerator.kt`**

`src/main/kotlin/com/qkt/common/IdGenerator.kt`:
```kotlin
package com.qkt.common

interface IdGenerator {
    fun next(): String
}

class SequentialIdGenerator(private val prefix: String = "ORD") : IdGenerator {
    private var counter = 0L
    override fun next(): String = "$prefix-${counter++}"
}
```

*Notes:*
- *`"$prefix-${counter++}"` — Kotlin string interpolation. `$var` for a single variable, `${expr}` for any expression. The `++` increments after the value is read (post-increment, Java-identical).*
- *Constructor with default value `prefix: String = "ORD"` means callers can write `SequentialIdGenerator()` or `SequentialIdGenerator(prefix = "TRD")`. No overload definitions needed.*

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/common/IdGenerator.kt
git commit -m "feat(common): add IdGenerator interface with SequentialIdGenerator"
```

---

## Task 5: Migrate `Tick` and `Candle` (replace broken old files)

**Files:**
- Delete: `market-data/Tick.kt` (existing, broken — `data class Tick {` should be `(`)
- Delete: `market-data/Candle.kt` (existing, clean)
- Delete: empty dirs `market-data/`, `common/`, `engine/` (created in original scaffolding, now superseded by `src/main/kotlin/com/qkt/...`)
- Create: `src/main/kotlin/com/qkt/marketdata/Tick.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/Candle.kt`

- [ ] **Step 1: Remove old files and empty directories**

Run:
```bash
rm market-data/Tick.kt market-data/Candle.kt
rmdir market-data/ common/ engine/
ls
```
Expected: only `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew`, `gradlew.bat`, `docs/`, `src/`, `.gitignore`, `.git/`, `.gradle/`, `build/` remain. The bare flat directories from the original scaffold are gone.

- [ ] **Step 2: Create `Tick.kt`**

`src/main/kotlin/com/qkt/marketdata/Tick.kt`:
```kotlin
package com.qkt.marketdata

data class Tick(
    val symbol: String,
    val price: Double,
    val timestamp: Long,
    val volume: Double? = null
)
```

*Notes:*
- *Replaces the broken file (had `data class Tick {` with curly brace).*
- *`Double?` (with `?`) means nullable. Without `?`, the type is non-null and the compiler enforces it.*
- *`= null` makes `volume` optional in the constructor. Callers can write `Tick("XAUUSD", 2400.0, 1000L)` without supplying volume.*

- [ ] **Step 3: Create `Candle.kt`**

`src/main/kotlin/com/qkt/marketdata/Candle.kt`:
```kotlin
package com.qkt.marketdata

data class Candle(
    val symbol: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val startTime: Long,
    val endTime: Long
)
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. 4 Kotlin files compiled total (Side, Clock, IdGenerator, Tick, Candle — wait, that's 5 declarations but in 4 files since Clock has 3 declarations).

- [ ] **Step 5: Commit**

```bash
git add -- src/main/kotlin/com/qkt/marketdata/Tick.kt src/main/kotlin/com/qkt/marketdata/Candle.kt
git add -u   # stages the deletions of market-data/Tick.kt and market-data/Candle.kt
git status   # verify only marketdata files staged
git commit -m "feat(marketdata): migrate Tick and Candle to gradle src layout"
```

*Note: `git add -u` stages deletions and modifications of already-tracked files. Since the original `market-data/` was untracked, the `rm` operations don't appear in `git status` as deletions. Only the new files in `src/main/kotlin/...` will show. That's fine.*

---

## Task 6: `marketdata.TickFeed` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/TickFeed.kt`

- [ ] **Step 1: Create `TickFeed.kt`**

`src/main/kotlin/com/qkt/marketdata/TickFeed.kt`:
```kotlin
package com.qkt.marketdata

interface TickFeed {
    fun next(): Tick?    // null = end of stream
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/TickFeed.kt
git commit -m "feat(marketdata): add TickFeed interface"
```

---

## Task 7: `marketdata.MarketPriceProvider` + `MarketPriceTracker` [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/marketdata/MarketPriceTrackerTest.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/MarketPriceProvider.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/MarketPriceTrackerTest.kt`:
```kotlin
package com.qkt.marketdata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketPriceTrackerTest {

    @Test
    fun `lastPrice returns null for unknown symbol`() {
        val tracker = MarketPriceTracker()
        assertThat(tracker.lastPrice("XAUUSD")).isNull()
    }

    @Test
    fun `update then lastPrice returns the value`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", 2400.5)
        assertThat(tracker.lastPrice("XAUUSD")).isEqualTo(2400.5)
    }

    @Test
    fun `update overwrites previous value for same symbol`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", 2400.0)
        tracker.update("XAUUSD", 2401.5)
        assertThat(tracker.lastPrice("XAUUSD")).isEqualTo(2401.5)
    }

    @Test
    fun `tracks multiple symbols independently`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", 2400.0)
        tracker.update("EURUSD", 1.0921)
        assertThat(tracker.lastPrice("XAUUSD")).isEqualTo(2400.0)
        assertThat(tracker.lastPrice("EURUSD")).isEqualTo(1.0921)
    }
}
```

*Notes:*
- *Backtick-quoted function names are Kotlin's way to allow spaces in test names. Useful for readability; only allowed in tests by convention.*
- *`assertThat(...)` from AssertJ. Fluent style: `assertThat(x).isEqualTo(y)`, `.isNull()`, etc.*

- [ ] **Step 2: Run test, verify it fails to compile**

Run: `./gradlew test --tests "com.qkt.marketdata.MarketPriceTrackerTest"`
Expected: `BUILD FAILED` with errors like `Unresolved reference: MarketPriceTracker`. This is the "failing test" — it can't compile because the class doesn't exist yet.

- [ ] **Step 3: Create the implementation**

`src/main/kotlin/com/qkt/marketdata/MarketPriceProvider.kt`:
```kotlin
package com.qkt.marketdata

interface MarketPriceProvider {
    fun lastPrice(symbol: String): Double?
}

class MarketPriceTracker : MarketPriceProvider {
    private val prices = mutableMapOf<String, Double>()

    fun update(symbol: String, price: Double) {
        prices[symbol] = price
    }

    override fun lastPrice(symbol: String): Double? = prices[symbol]
}
```

*Notes:*
- *Interface + class in one file — they're tightly coupled (`MarketPriceTracker` is the only impl of `MarketPriceProvider`). Split if a second impl appears later.*
- *`prices[symbol] = price` — Kotlin's index-set operator on Map. Compiles to `prices.put(symbol, price)`.*
- *`prices[symbol]` returns `Double?` — Kotlin's `Map.get` returns nullable, propagating to our return type.*

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew test --tests "com.qkt.marketdata.MarketPriceTrackerTest"`
Expected: `BUILD SUCCESSFUL`. 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/MarketPriceProvider.kt src/test/kotlin/com/qkt/marketdata/MarketPriceTrackerTest.kt
git commit -m "feat(marketdata): add MarketPriceProvider and tracker"
```

---

## Task 8: `marketdata.MockTickFeed` [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt`:
```kotlin
package com.qkt.marketdata

import com.qkt.common.FixedClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MockTickFeedTest {

    @Test
    fun `next returns count ticks then null`() {
        val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 3, clock = FixedClock(1000L))
        assertThat(feed.next()).isNotNull
        assertThat(feed.next()).isNotNull
        assertThat(feed.next()).isNotNull
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `same seed produces identical tick sequence`() {
        val feed1 = MockTickFeed("XAUUSD", 2400.0, 5, FixedClock(1000L), Random(seed = 42L))
        val feed2 = MockTickFeed("XAUUSD", 2400.0, 5, FixedClock(1000L), Random(seed = 42L))
        repeat(5) {
            assertThat(feed1.next()?.price).isEqualTo(feed2.next()?.price)
        }
    }

    @Test
    fun `prices stay positive and finite`() {
        val feed = MockTickFeed("XAUUSD", 2400.0, count = 100, FixedClock(1000L))
        var tick = feed.next()
        while (tick != null) {
            assertThat(tick.price).isGreaterThan(0.0).isFinite()
            tick = feed.next()
        }
    }

    @Test
    fun `each tick has clock's current timestamp`() {
        val clock = FixedClock(1714723200000L)
        val feed = MockTickFeed("XAUUSD", 2400.0, count = 1, clock = clock)
        val tick = feed.next()
        assertThat(tick?.timestamp).isEqualTo(1714723200000L)
    }

    @Test
    fun `throws on negative count`() {
        assertThatThrownBy { MockTickFeed("XAUUSD", 2400.0, count = -1, FixedClock(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `throws on non-positive startPrice`() {
        assertThatThrownBy { MockTickFeed("XAUUSD", startPrice = 0.0, count = 1, FixedClock(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MockTickFeed("XAUUSD", startPrice = -1.0, count = 1, FixedClock(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

*Notes:*
- *`assertThatThrownBy { ... }` runs the lambda and asserts it throws.*
- *`tick?.price` — safe call. If `tick` is null, returns null without crashing. Java equivalent: `tick == null ? null : tick.getPrice()`.*

- [ ] **Step 2: Run test, verify failure**

Run: `./gradlew test --tests "com.qkt.marketdata.MockTickFeedTest"`
Expected: compile failure — `Unresolved reference: MockTickFeed`.

- [ ] **Step 3: Create the implementation**

`src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt`:
```kotlin
package com.qkt.marketdata

import com.qkt.common.Clock
import kotlin.random.Random

class MockTickFeed(
    private val symbol: String,
    private val startPrice: Double,
    private val count: Int,
    private val clock: Clock,
    private val random: Random = Random(seed = 42L)
) : TickFeed {

    init {
        require(count >= 0) { "count must be >= 0: $count" }
        require(startPrice > 0.0) { "startPrice must be > 0: $startPrice" }
    }

    private var emitted = 0
    private var price = startPrice

    override fun next(): Tick? {
        if (emitted >= count) return null
        price *= (1.0 + (random.nextDouble() - 0.5) * 0.01)
        emitted++
        return Tick(symbol, price, clock.now())
    }
}
```

*Notes:*
- *`init { ... }` is a Kotlin initializer block that runs after the constructor's property assignments. `require(...)` throws `IllegalArgumentException` with the lambda's message if the condition is false.*
- *Uses `kotlin.random.Random` — Kotlin stdlib, not `java.util.Random`. Identical behavior; idiomatic Kotlin.*
- *Random walk: `price *= (1.0 + (rand - 0.5) * 0.01)` — multiply by something in `[0.995, 1.005]`. Geometric (always positive) random walk with ~0.5% step.*

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests "com.qkt.marketdata.MockTickFeedTest"`
Expected: `BUILD SUCCESSFUL`. 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt
git commit -m "feat(marketdata): add MockTickFeed with seeded random walk"
```

---

## Task 9: `execution.OrderType` enum

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/OrderType.kt`

- [ ] **Step 1: Create `OrderType.kt`**

`src/main/kotlin/com/qkt/execution/OrderType.kt`:
```kotlin
package com.qkt.execution

enum class OrderType { MARKET, LIMIT, STOP }
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/OrderType.kt
git commit -m "feat(execution): add OrderType enum"
```

---

## Task 10: `execution.Order` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/Order.kt`

- [ ] **Step 1: Create `Order.kt`**

`src/main/kotlin/com/qkt/execution/Order.kt`:
```kotlin
package com.qkt.execution

import com.qkt.common.Side

data class Order(
    val id: String,
    val symbol: String,
    val side: Side,
    val quantity: Double,
    val type: OrderType,
    val price: Double? = null,
    val timestamp: Long
)
```

*Notes:*
- *`price: Double? = null` — null for MARKET orders, required for LIMIT/STOP. The Broker enforces the latter at runtime.*
- *Importing `Side` from `com.qkt.common`. Kotlin imports work the same as Java.*

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/Order.kt
git commit -m "feat(execution): add Order data class"
```

---

## Task 11: `execution.Trade` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/Trade.kt`

- [ ] **Step 1: Create `Trade.kt`**

`src/main/kotlin/com/qkt/execution/Trade.kt`:
```kotlin
package com.qkt.execution

import com.qkt.common.Side

data class Trade(
    val orderId: String,
    val symbol: String,
    val price: Double,
    val quantity: Double,
    val side: Side,
    val timestamp: Long
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/Trade.kt
git commit -m "feat(execution): add Trade data class"
```

---

## Task 12: `strategy.Signal` sealed class

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/Signal.kt`

- [ ] **Step 1: Create `Signal.kt`**

`src/main/kotlin/com/qkt/strategy/Signal.kt`:
```kotlin
package com.qkt.strategy

sealed class Signal {
    data class Buy(val symbol: String, val size: Double) : Signal()
    data class Sell(val symbol: String, val size: Double) : Signal()
}
```

*Notes:*
- *`sealed class` — like a Java sealed class (Java 17+). The compiler knows exhaustive list of subtypes (`Buy`, `Sell`) and warns/errors if a `when (signal)` branch is missing one.*
- *`data class Buy(...) : Signal()` — `Buy` extends `Signal`. The empty `()` calls the parent's no-arg constructor.*

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/strategy/Signal.kt
git commit -m "feat(strategy): add Signal sealed class"
```

---

## Task 13: `strategy.Strategy` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/Strategy.kt`

- [ ] **Step 1: Create `Strategy.kt`**

`src/main/kotlin/com/qkt/strategy/Strategy.kt`:
```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(tick: Tick, emit: (Signal) -> Unit)
}
```

*Notes:*
- *`emit: (Signal) -> Unit` — Kotlin function type, equivalent to Java's `Consumer<Signal>`. Strategy invokes `emit(signal)` zero or more times per tick.*
- *No `onCandle` yet — that arrives in Phase 2 with the candle aggregator.*

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/strategy/Strategy.kt
git commit -m "feat(strategy): add Strategy interface with callback emit"
```

---

## Task 14: `strategy.EveryNthTickBuyStrategy` [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/strategy/EveryNthTickBuyStrategyTest.kt`
- Create: `src/main/kotlin/com/qkt/strategy/EveryNthTickBuyStrategy.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/strategy/EveryNthTickBuyStrategyTest.kt`:
```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EveryNthTickBuyStrategyTest {

    private fun tick(symbol: String = "XAUUSD", price: Double = 2400.0, ts: Long = 0L) =
        Tick(symbol, price, ts)

    @Test
    fun `emits no signal on first n minus 1 ticks`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 10)
        repeat(9) { strategy.onTick(tick()) { signals.add(it) } }
        assertThat(signals).isEmpty()
    }

    @Test
    fun `emits Buy on the nth tick`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0)
        repeat(10) { strategy.onTick(tick()) { signals.add(it) } }
        assertThat(signals).hasSize(1)
        assertThat(signals[0]).isEqualTo(Signal.Buy("XAUUSD", 1.0))
    }

    @Test
    fun `emits Buy on every nth tick`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 5, size = 2.0)
        repeat(15) { strategy.onTick(tick()) { signals.add(it) } }
        assertThat(signals).hasSize(3)
        assertThat(signals).allMatch { it == Signal.Buy("XAUUSD", 2.0) }
    }

    @Test
    fun `ignores ticks for non-target symbol`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 1)
        strategy.onTick(tick("EURUSD")) { signals.add(it) }
        strategy.onTick(tick("GBPUSD")) { signals.add(it) }
        assertThat(signals).isEmpty()
    }

    @Test
    fun `throws on non-positive n or size`() {
        assertThatThrownBy { EveryNthTickBuyStrategy("XAUUSD", n = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EveryNthTickBuyStrategy("XAUUSD", n = 1, size = 0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

*Notes:*
- *`strategy.onTick(tick()) { signals.add(it) }` — trailing-lambda syntax. Equivalent to `strategy.onTick(tick(), { signal -> signals.add(signal) })`. The lambda fills the `emit` parameter.*
- *`it` is the implicit name for a single-parameter lambda's argument.*

- [ ] **Step 2: Run test, verify failure**

Run: `./gradlew test --tests "com.qkt.strategy.EveryNthTickBuyStrategyTest"`
Expected: compile failure — `Unresolved reference: EveryNthTickBuyStrategy`.

- [ ] **Step 3: Create the implementation**

`src/main/kotlin/com/qkt/strategy/EveryNthTickBuyStrategy.kt`:
```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Tick

class EveryNthTickBuyStrategy(
    private val symbol: String,
    private val n: Int = 10,
    private val size: Double = 1.0
) : Strategy {

    init {
        require(n > 0) { "n must be > 0: $n" }
        require(size > 0.0) { "size must be > 0: $size" }
    }

    private var counter = 0

    override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
        if (tick.symbol != symbol) return
        counter++
        if (counter % n == 0) emit(Signal.Buy(symbol, size))
    }
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests "com.qkt.strategy.EveryNthTickBuyStrategyTest"`
Expected: `BUILD SUCCESSFUL`. 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/strategy/EveryNthTickBuyStrategy.kt src/test/kotlin/com/qkt/strategy/EveryNthTickBuyStrategyTest.kt
git commit -m "feat(strategy): add EveryNthTickBuyStrategy"
```

---

## Task 15: `broker.Broker` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/Broker.kt`

- [ ] **Step 1: Create `Broker.kt`**

`src/main/kotlin/com/qkt/broker/Broker.kt`:
```kotlin
package com.qkt.broker

import com.qkt.execution.Order
import com.qkt.execution.Trade

interface Broker {
    fun execute(order: Order): Trade?
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/Broker.kt
git commit -m "feat(broker): add Broker interface"
```

---

## Task 16: `broker.MockBroker` [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/broker/MockBrokerTest.kt`
- Create: `src/main/kotlin/com/qkt/broker/MockBroker.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/broker/MockBrokerTest.kt`:
```kotlin
package com.qkt.broker

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MockBrokerTest {

    private val clock = FixedClock(time = 1000L)
    private val tracker = MarketPriceTracker()
    private val broker = MockBroker(clock, tracker)

    private fun marketOrder(
        id: String = "ORD-0",
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: Double = 1.0,
        ts: Long = 1000L
    ) = Order(id, symbol, side, qty, OrderType.MARKET, null, ts)

    @Test
    fun `fills MARKET order at tracker last price`() {
        tracker.update("XAUUSD", 2400.5)
        val trade = broker.execute(marketOrder())
        assertThat(trade).isNotNull
        assertThat(trade!!.price).isEqualTo(2400.5)
    }

    @Test
    fun `returns null when no price seen for symbol`() {
        val trade = broker.execute(marketOrder(symbol = "BTCUSD"))
        assertThat(trade).isNull()
    }

    @Test
    fun `Trade has same orderId, symbol, qty, side as Order`() {
        tracker.update("XAUUSD", 2400.0)
        val order = marketOrder(id = "ORD-7", side = Side.SELL, qty = 3.5)
        val trade = broker.execute(order)
        assertThat(trade).isNotNull
        assertThat(trade!!.orderId).isEqualTo("ORD-7")
        assertThat(trade.symbol).isEqualTo("XAUUSD")
        assertThat(trade.quantity).isEqualTo(3.5)
        assertThat(trade.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `Trade timestamp is broker's clock-now`() {
        tracker.update("XAUUSD", 2400.0)
        clock.time = 9999L
        val trade = broker.execute(marketOrder(ts = 1000L))
        assertThat(trade!!.timestamp).isEqualTo(9999L)
    }

    @Test
    fun `throws on LIMIT order with null price`() {
        val order = Order("ORD-0", "XAUUSD", Side.BUY, 1.0, OrderType.LIMIT, null, 1000L)
        assertThatThrownBy { broker.execute(order) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `throws on STOP order with null price`() {
        val order = Order("ORD-0", "XAUUSD", Side.BUY, 1.0, OrderType.STOP, null, 1000L)
        assertThatThrownBy { broker.execute(order) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `throws on order with non-positive quantity`() {
        tracker.update("XAUUSD", 2400.0)
        assertThatThrownBy { broker.execute(marketOrder(qty = 0.0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { broker.execute(marketOrder(qty = -1.0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `fills LIMIT at order price not tracker price`() {
        tracker.update("XAUUSD", 2400.0)
        val order = Order("ORD-0", "XAUUSD", Side.BUY, 1.0, OrderType.LIMIT, price = 2350.0, timestamp = 1000L)
        val trade = broker.execute(order)
        assertThat(trade!!.price).isEqualTo(2350.0)
    }
}
```

*Notes:*
- *`trade!!.price` — `!!` asserts non-null. Used after `assertThat(trade).isNotNull`. Crashes with NPE if null, which is exactly what we want if the assertion failed.*
- *Tests use real `MockBroker`, `FixedClock`, `MarketPriceTracker` — no mocks per CLAUDE.md.*

- [ ] **Step 2: Run test, verify failure**

Run: `./gradlew test --tests "com.qkt.broker.MockBrokerTest"`
Expected: compile failure — `Unresolved reference: MockBroker`.

- [ ] **Step 3: Create the implementation**

`src/main/kotlin/com/qkt/broker/MockBroker.kt`:
```kotlin
package com.qkt.broker

import com.qkt.common.Clock
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceProvider

class MockBroker(
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider
) : Broker {

    override fun execute(order: Order): Trade? {
        require(order.quantity > 0.0) { "Order quantity must be > 0: $order" }
        val fillPrice = when (order.type) {
            OrderType.MARKET -> priceProvider.lastPrice(order.symbol) ?: return null
            OrderType.LIMIT, OrderType.STOP ->
                order.price ?: error("LIMIT/STOP requires price: $order")
        }
        return Trade(
            orderId = order.id,
            symbol = order.symbol,
            price = fillPrice,
            quantity = order.quantity,
            side = order.side,
            timestamp = clock.now()
        )
    }
}
```

*Notes:*
- *`priceProvider.lastPrice(order.symbol) ?: return null` — Elvis with early return. If `lastPrice` returns null, the *whole function* returns null. Exits at this point.*
- *`error("...")` — Kotlin stdlib helper, throws `IllegalStateException`. Used for "this should never happen but if it does, here's why."*
- *`when (order.type) { OrderType.MARKET -> ...; OrderType.LIMIT, OrderType.STOP -> ... }` — multi-value when branch, comma-separated. Java 21 switch pattern equivalent: `case LIMIT, STOP ->`.*

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests "com.qkt.broker.MockBrokerTest"`
Expected: `BUILD SUCCESSFUL`. 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/MockBroker.kt src/test/kotlin/com/qkt/broker/MockBrokerTest.kt
git commit -m "feat(broker): add MockBroker"
```

---

## Task 17: `engine.Engine` [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/engine/EngineTest.kt`
- Create: `src/main/kotlin/com/qkt/engine/Engine.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/engine/EngineTest.kt`:
```kotlin
package com.qkt.engine

import com.qkt.broker.Broker
import com.qkt.broker.MockBroker
import com.qkt.common.FixedClock
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EngineTest {

    private val clock = FixedClock(time = 1000L)
    private val ids = SequentialIdGenerator(prefix = "ORD")
    private val tracker = MarketPriceTracker()

    private fun engineWith(
        strategy: Strategy,
        broker: Broker = MockBroker(clock, tracker),
        trades: MutableList<Trade> = mutableListOf()
    ): Pair<Engine, MutableList<Trade>> =
        Engine(strategy, broker, clock, ids, tracker, onTrade = { trades.add(it) }) to trades

    @Test
    fun `updates price tracker before strategy sees the tick`() {
        val seenPrices = mutableListOf<Double?>()
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                seenPrices.add(tracker.lastPrice("XAUUSD"))
            }
        }
        val (engine, _) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.5, 1000L))
        assertThat(seenPrices).containsExactly(2400.5)
    }

    @Test
    fun `forwards tick to strategy`() {
        val seen = mutableListOf<Tick>()
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) { seen.add(tick) }
        }
        val (engine, _) = engineWith(strategy)
        val tick = Tick("XAUUSD", 2400.0, 1000L)
        engine.onTick(tick)
        assertThat(seen).containsExactly(tick)
    }

    @Test
    fun `converts Buy signal to MARKET BUY order`() {
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("XAUUSD", 2.0))
            }
        }
        val orders = mutableListOf<Order>()
        val capturingBroker = object : Broker {
            override fun execute(order: Order): Trade? { orders.add(order); return null }
        }
        val (engine, _) = engineWith(strategy, capturingBroker)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(orders).hasSize(1)
        val order = orders[0]
        assertThat(order.symbol).isEqualTo("XAUUSD")
        assertThat(order.side).isEqualTo(Side.BUY)
        assertThat(order.quantity).isEqualTo(2.0)
        assertThat(order.type).isEqualTo(OrderType.MARKET)
        assertThat(order.price).isNull()
        assertThat(order.id).isEqualTo("ORD-0")
        assertThat(order.timestamp).isEqualTo(1000L)
    }

    @Test
    fun `converts Sell signal to MARKET SELL order`() {
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Sell("XAUUSD", 3.0))
            }
        }
        val orders = mutableListOf<Order>()
        val capturingBroker = object : Broker {
            override fun execute(order: Order): Trade? { orders.add(order); return null }
        }
        val (engine, _) = engineWith(strategy, capturingBroker)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(orders).hasSize(1)
        assertThat(orders[0].side).isEqualTo(Side.SELL)
        assertThat(orders[0].quantity).isEqualTo(3.0)
    }

    @Test
    fun `routes order to broker and forwards trade to onTrade`() {
        tracker.update("XAUUSD", 2400.5)
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("XAUUSD", 1.0))
            }
        }
        val (engine, trades) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.5, 1000L))
        assertThat(trades).hasSize(1)
        assertThat(trades[0].price).isEqualTo(2400.5)
    }

    @Test
    fun `skips onTrade when broker returns null`() {
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("BTCUSD", 1.0))    // no price tracked → broker returns null
            }
        }
        val (engine, trades) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(trades).isEmpty()
    }

    @Test
    fun `assigns sequential ids to multiple signals from one tick`() {
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("XAUUSD", 1.0))
                emit(Signal.Sell("XAUUSD", 1.0))
            }
        }
        val orders = mutableListOf<Order>()
        val capturingBroker = object : Broker {
            override fun execute(order: Order): Trade? { orders.add(order); return null }
        }
        val (engine, _) = engineWith(strategy, capturingBroker)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(orders.map { it.id }).containsExactly("ORD-0", "ORD-1")
    }

    @Test
    fun `multiple signals all fill at same tracker price`() {
        tracker.update("XAUUSD", 2400.5)
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("XAUUSD", 1.0))
                emit(Signal.Buy("XAUUSD", 2.0))
            }
        }
        val (engine, trades) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.5, 1000L))
        assertThat(trades.map { it.price }).containsExactly(2400.5, 2400.5)
    }
}
```

*Notes:*
- *`object : Strategy { ... }` — Kotlin anonymous object. Java equivalent: `new Strategy() { ... }`.*
- *`Pair<Engine, MutableList<Trade>>` returned from helper, destructured as `val (engine, trades) = ...`. Saves boilerplate.*
- *`engineWith` is a helper that creates an Engine with default plumbing; tests can override `broker` to capture orders directly without going through MockBroker.*

- [ ] **Step 2: Run test, verify failure**

Run: `./gradlew test --tests "com.qkt.engine.EngineTest"`
Expected: compile failure — `Unresolved reference: Engine`.

- [ ] **Step 3: Create the implementation**

`src/main/kotlin/com/qkt/engine/Engine.kt`:
```kotlin
package com.qkt.engine

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy

class Engine(
    private val strategy: Strategy,
    private val broker: Broker,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val priceTracker: MarketPriceTracker,
    private val onTrade: (Trade) -> Unit = {}
) {

    fun onTick(tick: Tick) {
        priceTracker.update(tick.symbol, tick.price)
        strategy.onTick(tick) { signal -> route(signal) }
    }

    private fun route(signal: Signal) {
        val order = signal.toOrder(idGenerator.next(), clock.now())
        val trade = broker.execute(order) ?: return
        onTrade(trade)
    }

    private fun Signal.toOrder(id: String, ts: Long): Order = when (this) {
        is Signal.Buy  -> Order(id, symbol, Side.BUY,  size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }
}
```

*Notes:*
- *`private fun Signal.toOrder(id: String, ts: Long): Order` — extension function defined inside the class. The receiver is `Signal`. Inside, `this` refers to the Signal (Buy or Sell). Idiomatic way to add Engine-specific transformations to a sealed type without polluting the Signal class.*
- *`when (this) { is Signal.Buy -> ...; is Signal.Sell -> ... }` — exhaustive (compiler checks all sealed subtypes are covered). The `is` is Java's `instanceof` but Kotlin smart-casts inside the branch — `symbol` and `size` are accessible directly without explicit cast.*
- *`private val onTrade: (Trade) -> Unit = {}` — default is a no-op lambda. Callers can supply their own.*

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests "com.qkt.engine.EngineTest"`
Expected: `BUILD SUCCESSFUL`. 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/engine/Engine.kt src/test/kotlin/com/qkt/engine/EngineTest.kt
git commit -m "feat(engine): add Engine orchestrator"
```

---

## Task 18: `app.Main` + smoke run

**Files:**
- Create: `src/main/kotlin/com/qkt/app/Main.kt`

- [ ] **Step 1: Create `Main.kt`**

`src/main/kotlin/com/qkt/app/Main.kt`:
```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.strategy.EveryNthTickBuyStrategy

fun main() {
    val clock        = SystemClock()
    val ids          = SequentialIdGenerator()
    val priceTracker = MarketPriceTracker()
    val strategy     = EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0)
    val broker       = MockBroker(clock, priceTracker)
    val engine       = Engine(
        strategy, broker, clock, ids, priceTracker,
        onTrade = { t -> println("FILLED: ${t.side} ${t.quantity} ${t.symbol} @ ${t.price}") }
    )
    val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 100, clock = clock)

    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    println("Done.")
}
```

*Notes:*
- *`MockBroker(clock, priceTracker)` — Kotlin upcasts `MarketPriceTracker` (full class) to `MarketPriceProvider` (interface) automatically because the constructor expects the interface. Same instance, narrower view.*
- *`fun main()` at top level compiles to `class MainKt { static void main(String[] args) }` — that's why `mainClass` in `build.gradle.kts` is `com.qkt.app.MainKt`.*

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the application**

Run: `./gradlew run`
Expected output (the random-walk prices will vary, but format and count should match):
```
> Task :run
FILLED: BUY 1.0 XAUUSD @ 2398.55...
FILLED: BUY 1.0 XAUUSD @ 2391.21...
FILLED: BUY 1.0 XAUUSD @ 2387.94...
FILLED: BUY 1.0 XAUUSD @ ...
FILLED: BUY 1.0 XAUUSD @ ...
FILLED: BUY 1.0 XAUUSD @ ...
FILLED: BUY 1.0 XAUUSD @ ...
FILLED: BUY 1.0 XAUUSD @ ...
FILLED: BUY 1.0 XAUUSD @ ...
FILLED: BUY 1.0 XAUUSD @ ...
Done.

BUILD SUCCESSFUL
```

10 `FILLED` lines (one every 10 ticks × 100 ticks = 10 fills), then `Done.`. Prices are reproducible across runs because `MockTickFeed` is seeded.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/Main.kt
git commit -m "feat(app): add Main wiring and entry point"
```

---

## Task 19: Final verification

**Files:** No new files.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Compile + all tests + assemble.

- [ ] **Step 2: Run full test suite explicitly**

Run: `./gradlew test`
Expected: 31 tests, 0 failures, 0 ignored.
Console shows roughly:
```
MarketPriceTrackerTest > tracks multiple symbols independently PASSED
MarketPriceTrackerTest > update overwrites previous value for same symbol PASSED
MarketPriceTrackerTest > update then lastPrice returns the value PASSED
MarketPriceTrackerTest > lastPrice returns null for unknown symbol PASSED
MockTickFeedTest > each tick has clock's current timestamp PASSED
... (30 lines total)
```

- [ ] **Step 3: Verify file count and structure**

Run: `find src -name "*.kt" | sort`
Expected:
```
src/main/kotlin/com/qkt/app/Main.kt
src/main/kotlin/com/qkt/broker/Broker.kt
src/main/kotlin/com/qkt/broker/MockBroker.kt
src/main/kotlin/com/qkt/common/Clock.kt
src/main/kotlin/com/qkt/common/IdGenerator.kt
src/main/kotlin/com/qkt/common/Side.kt
src/main/kotlin/com/qkt/engine/Engine.kt
src/main/kotlin/com/qkt/execution/Order.kt
src/main/kotlin/com/qkt/execution/OrderType.kt
src/main/kotlin/com/qkt/execution/Trade.kt
src/main/kotlin/com/qkt/marketdata/Candle.kt
src/main/kotlin/com/qkt/marketdata/MarketPriceProvider.kt
src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt
src/main/kotlin/com/qkt/marketdata/Tick.kt
src/main/kotlin/com/qkt/marketdata/TickFeed.kt
src/main/kotlin/com/qkt/strategy/EveryNthTickBuyStrategy.kt
src/main/kotlin/com/qkt/strategy/Signal.kt
src/main/kotlin/com/qkt/strategy/Strategy.kt
src/test/kotlin/com/qkt/broker/MockBrokerTest.kt
src/test/kotlin/com/qkt/engine/EngineTest.kt
src/test/kotlin/com/qkt/marketdata/MarketPriceTrackerTest.kt
src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt
src/test/kotlin/com/qkt/strategy/EveryNthTickBuyStrategyTest.kt
```

23 Kotlin files: 18 production + 5 test.

- [ ] **Step 4: Verify line counts**

Run: `wc -l src/main/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest file is around 30-40 lines. No file exceeds ~150 lines (acceptance criterion §13 of spec).

- [ ] **Step 5: Verify no mock libraries on classpath**

Run: `grep -rE 'mockito|mockk' build.gradle.kts || echo "no mock libs (as expected)"`
Expected: `no mock libs (as expected)` — confirms acceptance criterion §13.

- [ ] **Step 6: Verify git history**

Run: `git log --oneline`
Expected: ~19 commits, starting with the spec commit and ending with `feat(app): add Main wiring and entry point`. Each commit is small, focused, and message-driven.

- [ ] **Step 7: Phase 1 done**

Acceptance criteria from spec §13 — verify all checked:

- [x] `./gradlew build` passes
- [x] `./gradlew run` produces ~10 `FILLED` lines + `Done.`
- [x] `./gradlew test` runs all 31 tests, all green
- [x] Same `MockTickFeed` seed produces same ticks (verified by `same seed produces identical tick sequence` test)
- [x] No production class is mocked in any test (no Mockito/MockK on classpath)
- [x] All decisions in spec §3 visible in code
- [x] No file exceeds ~150 lines
- [x] `git log` shows clean history starting from `git init`

Phase 1 is done. Phase 2 (event bus + candles) can begin from this baseline.

---

## Notes for the implementing engineer

- **Run tests after every TDD task.** Don't batch.
- **Don't use mocks.** Use anonymous objects (`object : Strategy { ... }`) and capture lists (`val captured = mutableListOf<Trade>()`). Per CLAUDE.md, mocking internals is forbidden.
- **Match Kotlin style exactly.** Don't add semicolons (Kotlin doesn't use them). Don't add `public` modifier (it's the default).
- **Frequent small commits.** Each task ends in a commit. Don't squash.
- **If a test fails unexpectedly:** read the error, fix the code (or the test if the test was wrong), re-run. Don't disable tests. Don't catch and swallow.
- **No emoji in code or commit messages.** Per CLAUDE.md.
