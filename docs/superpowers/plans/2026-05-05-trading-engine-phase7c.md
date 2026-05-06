# Phase 7c — TradingView Vendor + Sample Strategies + Live Demo + Phase Changelog

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish Phase 7 by landing the first concrete `MarketSource` vendor (TradingView via WebSocket), two sample strategies that exercise the new abstractions, a live demo entry point, and the user-facing Phase 7 changelog. After this plan merges, qkt has end-to-end live trading capability against real market data with paper fills, plus a worked-example document covering every capability shipped across 7a + 7b + 7c.

**Architecture:** Phase 7a delivered `MarketSource`, `LocalMarketSource`, `CompositeMarketSource`, `TimeMark`, `TradingCalendar`, `RangeAggregateIndicator`, `SessionAnchoredIndicator`, `Mode`, `SessionContext`, and `Strategy.onTickWithContext`. Phase 7b delivered `WarmupSpec` + `Warmable`, `IndicatorWarmer`, `TradingPipeline.ingestForWarmup`, `LiveTickSource`, `LiveTickFeed`, `LiveSession`, `LiveSessionHandle`, `InMemoryMarketSource` test fixture, `Backtest` warmup integration, `CompositeMarketSource` multi-vendor fan-in. Plan 7c assumes both are merged. New code in this plan is wholly additive: a TV WebSocket client, the `TradingViewMarketSource` that composes it, two sample strategies, a `LiveDemo` main, README "Live trading" section, and the Phase 7 changelog at `docs/phases/phase-7-live-runtime.md`.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5, AssertJ, kotlinx-serialization-json (already a dep). New dependency: `com.squareup.okhttp3:okhttp:4.12.0` (no coroutine module — qkt has no coroutines anywhere; OkHttp's standard sync API is sufficient).

**Spec:** [`docs/superpowers/specs/2026-05-05-trading-engine-phase7-design.md`](../specs/2026-05-05-trading-engine-phase7-design.md)
**Predecessors:**
- [`docs/superpowers/plans/2026-05-05-trading-engine-phase7a.md`](2026-05-05-trading-engine-phase7a.md)
- [`docs/superpowers/plans/2026-05-05-trading-engine-phase7b.md`](2026-05-05-trading-engine-phase7b.md)

---

## File structure overview

### New files

```
src/main/kotlin/com/qkt/marketdata/live/tv/
    TradingViewFrame.kt                   — typed wrapper over the JSON envelope
    TradingViewWebSocket.kt               — low-level WS client (OkHttp + framing)
    TradingViewListener.kt                — listener interface
    TradingViewQuoteSession.kt            — high-level live-tick subscription
    TradingViewChartSession.kt            — high-level historical bars query
    TradingViewResolution.kt              — TimeWindow → TV resolution mapper
    TradingViewMarketSource.kt            — public MarketSource implementation

src/main/kotlin/com/qkt/strategy/samples/
    BreakoutOfYesterdayHighStrategy.kt    — uses PreviousDayHigh
    RollingHighBreakoutStrategy.kt        — uses RangeAggregateIndicator

src/main/kotlin/com/qkt/app/
    LiveDemo.kt                           — main() that runs LiveSession against TV

src/test/resources/tv-fixtures/
    quote-session-eurusd.jsonl            — recorded TV WS frames (offline fixture)
    chart-session-eurusd-m5.jsonl         — recorded TV chart-session frames

src/test/kotlin/com/qkt/marketdata/live/tv/
    TradingViewFrameTest.kt
    TradingViewWebSocketFramingTest.kt
    TradingViewQuoteSessionTest.kt
    TradingViewChartSessionTest.kt
    TradingViewResolutionTest.kt
    TradingViewMarketSourceTest.kt
    TradingViewLiveSmokeTest.kt           — @Tag("e2e"), excluded from default test run

src/test/kotlin/com/qkt/strategy/samples/
    BreakoutOfYesterdayHighStrategyTest.kt
    RollingHighBreakoutStrategyTest.kt

docs/phases/
    phase-7-live-runtime.md               — user-facing Phase 7 changelog (per SKILL.md §6)
```

### Modified files

```
gradle/libs.versions.toml               — add okhttp version + library coordinate
build.gradle.kts                        — add okhttp dependency; add e2e test filter via -PincludeTags
README.md                               — add "Live trading" section
```

### Deleted files

None.

---

## Task summary

| # | Group | Title |
|---|---|---|
| 1 | A | Add OkHttp build dependency and `e2e` tag exclusion |
| 2 | B | Add `TradingViewFrame` typed wrapper |
| 3 | B | Add `TradingViewListener` interface and frame framing helpers |
| 4 | B | Add `TradingViewWebSocket` low-level client |
| 5 | C | Add `TradingViewResolution` mapper |
| 6 | C | Add `TradingViewQuoteSession` (offline replay test) |
| 7 | C | Add `TradingViewChartSession` (offline replay test) |
| 8 | D | Add `TradingViewMarketSource` |
| 9 | D | Add `TradingViewLiveSmokeTest` (manual e2e) |
| 10 | E | Add `BreakoutOfYesterdayHighStrategy` sample |
| 11 | E | Add `RollingHighBreakoutStrategy` sample |
| 12 | F | Add `LiveDemo` main entry point |
| 13 | F | Update README with "Live trading" section |
| 14 | G | Write Phase 7 changelog at `docs/phases/phase-7-live-runtime.md` |
| 15 | — | Final verification |

15 tasks. Cumulative test counts after each (assuming Phase 7b merged at ~347):

| After task | New tests | Cumulative |
|---|---|---|
| 1  |  0 | 347 |
| 2  | +5 | 352 |
| 3  |  0 | 352 |
| 4  | +4 | 356 |
| 5  | +6 | 362 |
| 6  | +5 | 367 |
| 7  | +4 | 371 |
| 8  | +6 | 377 |
| 9  |  0 | 377 |
| 10 | +4 | 381 |
| 11 | +3 | 384 |
| 12 |  0 | 384 |
| 13 |  0 | 384 |
| 14 |  0 | 384 |
| 15 |  0 | 384 |

Final target: **~384 tests** (excluding `@Tag("e2e")` smoke tests). Rough number; ±5 expected.

---

## Group A: Build setup

### Task 1: Add OkHttp build dependency and `e2e` tag exclusion

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

OkHttp 4.12.0 is the last 4.x line; it is JVM-targeting, no coroutine dependency, no Kotlin reflection runtime. We pull it in for `TradingViewWebSocket` only.

The `@Tag("e2e")` smoke tests need to be excluded from the default `./gradlew test` run because they hit the real TradingView WebSocket and are non-deterministic / network-dependent. JUnit 5's `useJUnitPlatform { excludeTags("e2e") }` does exactly that. We add a Gradle property `-PincludeTags=e2e` that flips the include set so users can opt in.

- [ ] **Step 1: Add OkHttp version + library to `gradle/libs.versions.toml`**

Edit `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.1.0"
junit = "5.11.4"
assertj = "3.27.0"
ktlint-plugin = "12.1.1"
slf4j = "2.0.16"
kotlinx-serialization = "1.7.3"
okhttp = "4.12.0"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-plugin" }
```

- [ ] **Step 2: Wire the dependency and the e2e filter in `build.gradle.kts`**

Edit `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
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
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.qkt.app.MainKt")
}

tasks.test {
    useJUnitPlatform {
        val included = (project.findProperty("includeTags") as String?)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (included.isEmpty()) {
            excludeTags("e2e")
        } else {
            includeTags(*included.toTypedArray())
        }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.5.0")
    verbose.set(true)
    outputToConsole.set(true)
    enableExperimentalRules.set(false)
}
```

- [ ] **Step 3: Confirm the build still passes**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. ~347 tests pass (Phase 7b baseline). OkHttp downloads on first build.

- [ ] **Step 4: Verify the `-PincludeTags=e2e` filter is wired**

Run: `./gradlew test -PincludeTags=e2e`
Expected: zero tests run (no `@Tag("e2e")` tests exist yet). No failures.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build: add okhttp dependency and e2e tag exclusion"
```

---

## Group B: TradingView WebSocket client

### Task 2: Add `TradingViewFrame` typed wrapper

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewFrame.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewFrameTest.kt`

TV WS frames are JSON envelopes: `{"m": "<method>", "p": [<args>...]}`. `TradingViewFrame` is a thin typed wrapper backed by `kotlinx-serialization-json`'s `JsonElement`. It exposes accessors for the most-needed fields (`method`, `params`, `paramAt(i)`, `paramAsString(i)`, `paramAsObject(i)`) without forcing every consumer to write `frame.params[1].jsonObject["v"]?.jsonObject?.get("lp")` chains.

The frame also models heartbeats: TV's heartbeat frames are bare `~h~N~h~` envelopes with no JSON, just an integer counter. We represent these as `TradingViewFrame.Heartbeat(seq: Int)`. Everything else is `TradingViewFrame.Message(method, params)`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewFrameTest.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewFrameTest {
    @Test
    fun `parses message frame with method and params`() {
        val json =
            """{"m":"qsd","p":["session_xyz",{"n":"OANDA:EURUSD","s":"ok","v":{"lp":1.10}}]}"""
        val frame = TradingViewFrame.parse(json)
        assertThat(frame).isInstanceOf(TradingViewFrame.Message::class.java)
        val msg = frame as TradingViewFrame.Message
        assertThat(msg.method).isEqualTo("qsd")
        assertThat(msg.params).hasSize(2)
        assertThat(msg.paramAsString(0)).isEqualTo("session_xyz")
    }

    @Test
    fun `paramAsObject returns the JsonObject at index`() {
        val json =
            """{"m":"qsd","p":["s",{"n":"OANDA:EURUSD","v":{"lp":1.10}}]}"""
        val msg = TradingViewFrame.parse(json) as TradingViewFrame.Message
        val obj: JsonObject = msg.paramAsObject(1)
        assertThat(obj["n"]?.toString()).isEqualTo("\"OANDA:EURUSD\"")
    }

    @Test
    fun `parses heartbeat frame`() {
        val frame = TradingViewFrame.parse("~h~7~h~")
        assertThat(frame).isInstanceOf(TradingViewFrame.Heartbeat::class.java)
        assertThat((frame as TradingViewFrame.Heartbeat).seq).isEqualTo(7)
    }

    @Test
    fun `serializes message frame back to JSON for sending`() {
        val msg =
            TradingViewFrame.Message(
                method = "quote_create_session",
                params =
                    buildJsonArray {
                        add(JsonPrimitive("session_xyz"))
                    },
            )
        assertThat(msg.toWireJson())
            .isEqualTo("""{"m":"quote_create_session","p":["session_xyz"]}""")
    }

    @Test
    fun `parsing malformed JSON throws TradingViewProtocolException`() {
        assertThatThrownBy { TradingViewFrame.parse("not valid") }
            .isInstanceOf(TradingViewProtocolException::class.java)
            .hasMessageContaining("not valid")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewFrameTest"`
Expected: compile failure (`Unresolved reference: TradingViewFrame`).

- [ ] **Step 3: Implement `TradingViewFrame`**

`src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewFrame.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TradingViewProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

sealed class TradingViewFrame {
    data class Message(
        val method: String,
        val params: JsonArray,
    ) : TradingViewFrame() {
        fun paramAt(index: Int): JsonElement = params[index]

        fun paramAsString(index: Int): String = params[index].jsonPrimitive.content

        fun paramAsObject(index: Int): JsonObject = params[index].jsonObject

        fun toWireJson(): String {
            val obj =
                buildJsonObject {
                    put("m", JsonElementFactory.string(method))
                    put("p", params)
                }
            return JSON.encodeToString(JsonElement.serializer(), obj)
        }
    }

    data class Heartbeat(val seq: Int) : TradingViewFrame() {
        fun toWireString(): String = "~h~$seq~h~"
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val HEARTBEAT_REGEX = Regex("^~h~(\\d+)~h~$")

        fun parse(payload: String): TradingViewFrame {
            HEARTBEAT_REGEX.matchEntire(payload)?.let { m ->
                return Heartbeat(m.groupValues[1].toInt())
            }
            val tree =
                try {
                    JSON.parseToJsonElement(payload)
                } catch (e: Exception) {
                    throw TradingViewProtocolException("Cannot parse frame: $payload", e)
                }
            val obj = tree.jsonObject
            val method =
                obj["m"]?.jsonPrimitive?.content
                    ?: throw TradingViewProtocolException("Frame missing 'm' field: $payload")
            val params = obj["p"]?.jsonArray ?: JsonArray(emptyList())
            return Message(method, params)
        }
    }
}

private object JsonElementFactory {
    fun string(value: String): JsonElement = kotlinx.serialization.json.JsonPrimitive(value)
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewFrameTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL, ~352 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewFrame.kt src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewFrameTest.kt
git commit -m "feat(marketdata): add TradingViewFrame typed wrapper"
```

---

### Task 3: Add `TradingViewListener` interface and frame framing helpers

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewListener.kt`
- Modify: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewFrame.kt` — add `Framing` object for `~m~LEN~m~PAYLOAD` encode/decode

TV's outermost wire format is `~m~<length>~m~<payload>`, where `<length>` is the byte length of `<payload>` and `<payload>` is either a JSON object (a Message) or a heartbeat string `~h~N~h~`. Multiple frames can be concatenated in one WebSocket message — `Framing.decodeAll(buffer)` returns a list of payloads, leaving any partial trailing frame in the buffer.

`TradingViewListener` is the contract `TradingViewWebSocket` calls back into.

- [ ] **Step 1: Add the listener interface**

`src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewListener.kt`:

```kotlin
package com.qkt.marketdata.live.tv

interface TradingViewListener {
    fun onFrame(frame: TradingViewFrame)

    fun onConnected()

    fun onDisconnected(reason: String)
}
```

- [ ] **Step 2: Add `Framing` helpers to `TradingViewFrame.kt`**

Append to `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewFrame.kt`:

```kotlin
object TradingViewFraming {
    private val WRAPPER = Regex("~m~(\\d+)~m~")

    fun encode(payload: String): String = "~m~${payload.toByteArray(Charsets.UTF_8).size}~m~$payload"

    fun decodeAll(buffer: String): DecodeResult {
        val frames = mutableListOf<String>()
        var offset = 0
        while (offset < buffer.length) {
            val match = WRAPPER.find(buffer, offset) ?: break
            if (match.range.first != offset) {
                throw TradingViewProtocolException("Unexpected bytes before frame header at offset=$offset")
            }
            val len = match.groupValues[1].toInt()
            val start = match.range.last + 1
            val end = start + len
            if (end > buffer.length) {
                return DecodeResult(frames = frames, leftover = buffer.substring(offset))
            }
            frames.add(buffer.substring(start, end))
            offset = end
        }
        return DecodeResult(frames = frames, leftover = buffer.substring(offset))
    }

    data class DecodeResult(
        val frames: List<String>,
        val leftover: String,
    )
}
```

- [ ] **Step 3: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewListener.kt src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewFrame.kt
git commit -m "feat(marketdata): add TradingViewListener and TradingViewFraming"
```

---

### Task 4: Add `TradingViewWebSocket` low-level client

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocket.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocketFramingTest.kt`

`TradingViewWebSocket` wraps OkHttp's `WebSocket`. Public surface:

- `connect()` — opens the WS connection; blocks until the connection is established or fails.
- `send(method: String, params: List<Any>)` — encodes a `Message`, wraps in framing, transmits.
- `addListener(listener: TradingViewListener)` — registers a listener; multiple listeners are supported (one per high-level session).
- `removeListener(listener: TradingViewListener)`.
- `close()` — closes the underlying WS gracefully.

Internal behavior:

- On connect, sends `set_auth_token` with the constant `"unauthorized_user_token"` (anonymous mode).
- Heartbeats (`~h~N~h~`) are echoed back automatically inside the receive callback before being delivered to listeners (listeners can still observe them, but they are not the consumer's responsibility).
- On parse error or socket close, all listeners receive `onDisconnected(reason)`. Reconnect runs in a daemon thread with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s, capped at 60s). Each successful reconnect calls `listener.onConnected()`, which gives high-level sessions a hook to re-create their server-side state.
- The class is single-instance per connection. Multiple JVM-level uses of TV would each construct their own `TradingViewWebSocket`.

The companion `TradingViewWebSocket.connect(...)` is the canonical factory; callers do not subclass.

This task tests the framing contract (Task 3) at the WebSocket boundary using a fake `okhttp3.WebSocket`. Tests that exercise the real network are deferred to Task 9 (smoke test).

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocketFramingTest.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingViewWebSocketFramingTest {
    @Test
    fun `encode prepends framing header with byte length`() {
        val payload = """{"m":"quote_create_session","p":["abc"]}"""
        val framed = TradingViewFraming.encode(payload)
        assertThat(framed).isEqualTo("~m~${payload.length}~m~$payload")
    }

    @Test
    fun `decodeAll splits concatenated frames`() {
        val a = """{"m":"qsd","p":["s",{}]}"""
        val b = "~h~1~h~"
        val concatenated = TradingViewFraming.encode(a) + TradingViewFraming.encode(b)
        val result = TradingViewFraming.decodeAll(concatenated)
        assertThat(result.frames).containsExactly(a, b)
        assertThat(result.leftover).isEmpty()
    }

    @Test
    fun `decodeAll returns leftover when frame is incomplete`() {
        val full = """{"m":"qsd","p":["s",{}]}"""
        val truncated = TradingViewFraming.encode(full).dropLast(5)
        val result = TradingViewFraming.decodeAll(truncated)
        assertThat(result.frames).isEmpty()
        assertThat(result.leftover).isEqualTo(truncated)
    }

    @Test
    fun `decodeAll throws on garbage prefix`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                TradingViewFraming.decodeAll("garbage~m~10~m~xxxxxxxxxx")
            }.isInstanceOf(TradingViewProtocolException::class.java)
    }
}
```

- [ ] **Step 2: Confirm GREEN against the framing impl from Task 3**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewWebSocketFramingTest"`
Expected: 4 tests PASS (the framing helpers from Task 3 already implement what the tests assert).

- [ ] **Step 3: Implement `TradingViewWebSocket`**

`src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocket.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TradingViewWebSocket(
    private val url: String = DEFAULT_URL,
    private val origin: String = DEFAULT_ORIGIN,
    private val authToken: String = ANONYMOUS_TOKEN,
    private val client: OkHttpClient = defaultClient(),
) {
    private val log = LoggerFactory.getLogger(TradingViewWebSocket::class.java)

    private val listeners: MutableList<TradingViewListener> = CopyOnWriteArrayList()
    private val socket: AtomicReference<WebSocket?> = AtomicReference(null)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val buffer = StringBuilder()

    fun addListener(listener: TradingViewListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TradingViewListener) {
        listeners.remove(listener)
    }

    fun connect() {
        if (closed.get()) error("TradingViewWebSocket is closed")
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Origin", origin)
                .build()
        val webSocket = client.newWebSocket(request, InternalListener())
        socket.set(webSocket)
    }

    fun send(method: String, params: List<Any>) {
        val ws = socket.get() ?: error("TradingViewWebSocket is not connected")
        val message =
            TradingViewFrame.Message(
                method = method,
                params = paramsToJsonArray(params),
            )
        val framed = TradingViewFraming.encode(message.toWireJson())
        ws.send(framed)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.get()?.close(1000, "client close")
        }
    }

    private fun paramsToJsonArray(params: List<Any>): JsonArray =
        buildJsonArray {
            params.forEach { add(toJsonElement(it)) }
        }

    private fun toJsonElement(value: Any): JsonElement =
        when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonElement -> value
            is List<*> ->
                buildJsonArray {
                    value.forEach { add(toJsonElement(it ?: error("null param element"))) }
                }
            else -> error("Unsupported parameter type: ${value::class.java.simpleName}")
        }

    private fun handleHeartbeat(seq: Int) {
        val ws = socket.get() ?: return
        val response = TradingViewFraming.encode("~h~$seq~h~")
        ws.send(response)
    }

    private inner class InternalListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            send("set_auth_token", listOf(authToken))
            listeners.forEach { runCatching { it.onConnected() } }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(buffer) {
                buffer.append(text)
                val decoded = TradingViewFraming.decodeAll(buffer.toString())
                buffer.setLength(0)
                buffer.append(decoded.leftover)
                decoded.frames.forEach { dispatch(it) }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = "failure: ${t.message}"
            log.warn("TradingViewWebSocket onFailure: $reason", t)
            listeners.forEach { runCatching { it.onDisconnected(reason) } }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log.info("TradingViewWebSocket onClosed: code=$code reason=$reason")
            listeners.forEach { runCatching { it.onDisconnected("closed:$code:$reason") } }
        }

        private fun dispatch(payload: String) {
            try {
                val frame = TradingViewFrame.parse(payload)
                if (frame is TradingViewFrame.Heartbeat) {
                    handleHeartbeat(frame.seq)
                }
                listeners.forEach { runCatching { it.onFrame(frame) } }
            } catch (e: TradingViewProtocolException) {
                log.warn("Cannot parse frame: $payload", e)
            }
        }
    }

    companion object {
        const val DEFAULT_URL = "wss://data.tradingview.com/socket.io/websocket"
        const val DEFAULT_ORIGIN = "https://www.tradingview.com"
        const val ANONYMOUS_TOKEN = "unauthorized_user_token"

        fun connect(
            url: String = DEFAULT_URL,
            origin: String = DEFAULT_ORIGIN,
            authToken: String = ANONYMOUS_TOKEN,
            client: OkHttpClient = defaultClient(),
        ): TradingViewWebSocket =
            TradingViewWebSocket(url, origin, authToken, client).apply { connect() }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
    }
}
```

- [ ] **Step 4: Run check + commit**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL. The OkHttp dependency is now exercised at compile time.

```bash
git add src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocket.kt src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocketFramingTest.kt
git commit -m "feat(marketdata): add TradingViewWebSocket low-level client"
```

---

## Group C: TradingView sessions

### Task 5: Add `TradingViewResolution` mapper

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewResolution.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewResolutionTest.kt`

TV's `chart_session.create_series` accepts a string resolution: `1S`, `5S`, `15S`, `30S`, `1`, `5`, `15`, `30`, `60`, `240`, `1D`, `1W`, `1M`. Our `TimeWindow` is parameterized in millis. We map the durations directly; unsupported windows throw `UnsupportedDataException(BARS, "TradingViewMarketSource", "...")` per spec D5 + open question 4.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewResolutionTest.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.UnsupportedDataException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewResolutionTest {
    @Test
    fun `maps ONE_SECOND to 1S`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.ONE_SECOND)).isEqualTo("1S")
    }

    @Test
    fun `maps ONE_MINUTE to 1`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.ONE_MINUTE)).isEqualTo("1")
    }

    @Test
    fun `maps FIVE_MINUTES to 5`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.FIVE_MINUTES)).isEqualTo("5")
    }

    @Test
    fun `maps FIFTEEN_MINUTES to 15`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.FIFTEEN_MINUTES)).isEqualTo("15")
    }

    @Test
    fun `maps ONE_HOUR to 60`() {
        assertThat(TradingViewResolution.fromTimeWindow(TimeWindow.ONE_HOUR)).isEqualTo("60")
    }

    @Test
    fun `non-standard window throws UnsupportedDataException with supported list`() {
        val odd = TimeWindow(13_000L)
        assertThatThrownBy { TradingViewResolution.fromTimeWindow(odd) }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("TradingViewMarketSource")
            .hasMessageContaining("BARS")
            .hasMessageContaining("13000")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewResolutionTest"`
Expected: compile failure (`Unresolved reference: TradingViewResolution`).

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewResolution.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.UnsupportedDataException

object TradingViewResolution {
    private val SUPPORTED: Map<Long, String> =
        linkedMapOf(
            1_000L to "1S",
            5_000L to "5S",
            15_000L to "15S",
            30_000L to "30S",
            60_000L to "1",
            300_000L to "5",
            900_000L to "15",
            1_800_000L to "30",
            3_600_000L to "60",
            14_400_000L to "240",
            86_400_000L to "1D",
            604_800_000L to "1W",
        )

    fun fromTimeWindow(window: TimeWindow): String =
        SUPPORTED[window.durationMs]
            ?: throw UnsupportedDataException(
                MarketSourceCapability.BARS,
                "TradingViewMarketSource does not support window ${window.durationMs}ms; supported windows (ms): ${SUPPORTED.keys}",
            )

    fun supportedWindows(): List<TimeWindow> = SUPPORTED.keys.map { TimeWindow(it) }
}
```

The error message threading uses the existing `UnsupportedDataException(capability, providerClass)` two-arg constructor; we lean on the `providerClass` slot to carry the detail string.

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewResolutionTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewResolution.kt src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewResolutionTest.kt
git commit -m "feat(marketdata): add TradingViewResolution TimeWindow mapper"
```

---

### Task 6: Add `TradingViewQuoteSession` (offline replay test)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewQuoteSession.kt`
- Create: `src/test/resources/tv-fixtures/quote-session-eurusd.jsonl`
- Create: `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewQuoteSessionTest.kt`

`TradingViewQuoteSession` is the high-level live-tick subscription. Constructor takes a `TradingViewWebSocket` and a session-id generator (defaults to a random 8-char alphanumeric ID). Public surface:

- `subscribe(symbols: List<String>, onTick: (Tick) -> Unit, onError: (Throwable) -> Unit, onDisconnect: () -> Unit)` — registers the consumer, sends `quote_create_session` + `quote_set_fields` + `quote_add_symbols`.
- `unsubscribe(symbols: List<String>)` — sends `quote_remove_symbols`.
- `close()` — unregisters from the WS and clears state.

Internally:

- Implements `TradingViewListener`. Filters incoming frames to `qsd` only (quote session data); ignores `quote_completed`, `quote_session_status`, etc. for now.
- For each `qsd` frame, parses the inner `{n: "OANDA:EURUSD", v: {lp: 1.10, bid: ..., ask: ..., volume: ...}}` object and constructs a `Tick`. Tick `timestamp` uses `Clock.now()` (caller-injected) because TV doesn't provide tick timestamps for live updates.
- Updates per-symbol last-known field state so subsequent `qsd` frames that send only `lp` can still build a complete `Tick` with the previously-seen `bid`/`ask`.
- On `onDisconnected`, propagates to the consumer's `onDisconnect` callback. On `onConnected` after a reconnect, re-issues all the subscribe commands so the server-side session is restored.

The fixture `quote-session-eurusd.jsonl` is a hand-captured (or synthesized for test purposes) sequence of TV WS frames recorded against `OANDA:EURUSD`. Each line is one inner JSON payload (no framing wrapper). The test loads, parses, and replays each line through a fake `TradingViewWebSocket` that exposes a `replay(frames: Sequence<TradingViewFrame>)` test hook.

Because Kotlin doesn't allow extending classes with a constructor parameter list without inheritance ceremony, we make `TradingViewWebSocket`'s `dispatch` path testable via a small package-private `replayFrame(payload: String)` method protected against use outside the test setup. Cleanest alternative: introduce a thin interface `TradingViewWebSocketLike` that both the real `TradingViewWebSocket` and a `FakeTradingViewWebSocket` test class implement. We go with the interface route — minimal blast radius, no test hooks in production code.

- [ ] **Step 1: Add the `TradingViewWebSocketLike` interface**

Refactor `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocket.kt` to expose a small interface and have the concrete class implement it. The signatures match the existing public methods.

In `TradingViewWebSocket.kt`, before the `class TradingViewWebSocket(...)` declaration, add:

```kotlin
interface TradingViewWebSocketLike {
    fun addListener(listener: TradingViewListener)

    fun removeListener(listener: TradingViewListener)

    fun send(method: String, params: List<Any>)

    fun close()
}
```

Then change the class signature:

```kotlin
class TradingViewWebSocket(
    private val url: String = DEFAULT_URL,
    private val origin: String = DEFAULT_ORIGIN,
    private val authToken: String = ANONYMOUS_TOKEN,
    private val client: OkHttpClient = defaultClient(),
) : TradingViewWebSocketLike {
```

The `connect()` method is concrete-only; not part of the interface.

- [ ] **Step 2: Create the fixture**

`src/test/resources/tv-fixtures/quote-session-eurusd.jsonl`:

```jsonl
{"m":"quote_session_status","p":["qs_test","ok"]}
{"m":"qsd","p":["qs_test",{"n":"OANDA:EURUSD","s":"ok","v":{"lp":1.10010,"bid":1.10005,"ask":1.10015,"volume":100}}]}
{"m":"qsd","p":["qs_test",{"n":"OANDA:EURUSD","s":"ok","v":{"lp":1.10020}}]}
{"m":"qsd","p":["qs_test",{"n":"OANDA:EURUSD","s":"ok","v":{"lp":1.10018,"bid":1.10013,"ask":1.10023}}]}
{"m":"quote_completed","p":["qs_test","OANDA:EURUSD"]}
```

Each line is the inner JSON payload of one TV message. The middle three `qsd` frames carry tick updates; the first frame is a status acknowledgment; the last frame is a non-tick session-management notice.

- [ ] **Step 3: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewQuoteSessionTest.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.io.File
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingViewQuoteSessionTest {
    private fun loadFrames(resource: String): Sequence<TradingViewFrame> {
        val text =
            File("src/test/resources/$resource").readText()
        return text
            .lines()
            .filter { it.isNotBlank() }
            .map { TradingViewFrame.parse(it) }
            .asSequence()
    }

    @Test
    fun `qsd frames produce ticks`() {
        val ws = FakeTradingViewWebSocket()
        val clock = FixedClock(time = 1_700_000_000_000L)
        val session = TradingViewQuoteSession(ws, clock = clock, sessionIdGenerator = { "qs_test" })

        val captured = mutableListOf<Tick>()
        session.subscribe(
            symbols = listOf("OANDA:EURUSD"),
            onTick = { tick -> captured.add(tick) },
            onError = {},
            onDisconnect = {},
        )

        ws.replay(loadFrames("tv-fixtures/quote-session-eurusd.jsonl"))

        assertThat(captured).hasSize(3)
        assertThat(captured.map { it.symbol }).allMatch { it == "OANDA:EURUSD" }
        assertThat(captured[0].price).isEqualByComparingTo<BigDecimal>(Money.of("1.10010"))
        assertThat(captured[1].price).isEqualByComparingTo<BigDecimal>(Money.of("1.10020"))
        assertThat(captured[2].price).isEqualByComparingTo<BigDecimal>(Money.of("1.10018"))
    }

    @Test
    fun `tick carries last known bid and ask when the frame omits them`() {
        val ws = FakeTradingViewWebSocket()
        val clock = FixedClock(time = 1_700_000_000_000L)
        val session = TradingViewQuoteSession(ws, clock = clock, sessionIdGenerator = { "qs_test" })

        val captured = mutableListOf<Tick>()
        session.subscribe(
            symbols = listOf("OANDA:EURUSD"),
            onTick = { tick -> captured.add(tick) },
            onError = {},
            onDisconnect = {},
        )

        ws.replay(loadFrames("tv-fixtures/quote-session-eurusd.jsonl"))

        assertThat(captured[1].bid).isEqualByComparingTo<BigDecimal>(Money.of("1.10005"))
        assertThat(captured[1].ask).isEqualByComparingTo<BigDecimal>(Money.of("1.10015"))
        assertThat(captured[2].bid).isEqualByComparingTo<BigDecimal>(Money.of("1.10013"))
        assertThat(captured[2].ask).isEqualByComparingTo<BigDecimal>(Money.of("1.10023"))
    }

    @Test
    fun `subscribe sends create + set_fields + add_symbols in order`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(time = 0L), sessionIdGenerator = { "qs_test" })
        session.subscribe(listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"), {}, {}, {})

        assertThat(ws.commandsSent.map { it.first }).containsExactly(
            "quote_create_session",
            "quote_set_fields",
            "quote_add_symbols",
        )
        assertThat(ws.commandsSent[0].second).isEqualTo(listOf("qs_test"))
        assertThat(ws.commandsSent[2].second).isEqualTo(
            listOf("qs_test", "OANDA:EURUSD", "BINANCE:BTCUSDT"),
        )
    }

    @Test
    fun `unsubscribe sends quote_remove_symbols`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(time = 0L), sessionIdGenerator = { "qs_test" })
        session.subscribe(listOf("OANDA:EURUSD"), {}, {}, {})
        ws.commandsSent.clear()

        session.unsubscribe(listOf("OANDA:EURUSD"))
        assertThat(ws.commandsSent).hasSize(1)
        assertThat(ws.commandsSent.single().first).isEqualTo("quote_remove_symbols")
    }

    @Test
    fun `reconnect re-issues subscribe commands`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(time = 0L), sessionIdGenerator = { "qs_test" })
        session.subscribe(listOf("OANDA:EURUSD"), {}, {}, {})
        val initialCount = ws.commandsSent.size

        ws.simulateDisconnect("transient network error")
        ws.simulateConnect()

        assertThat(ws.commandsSent.size).isGreaterThan(initialCount)
        val resubscribeMethods = ws.commandsSent.drop(initialCount).map { it.first }
        assertThat(resubscribeMethods).contains("quote_add_symbols")
    }
}
```

`FakeTradingViewWebSocket` is the test-only test fixture; we add it next.

- [ ] **Step 4: Add the `FakeTradingViewWebSocket` test fixture**

`src/test/kotlin/com/qkt/marketdata/live/tv/FakeTradingViewWebSocket.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import java.util.concurrent.CopyOnWriteArrayList

class FakeTradingViewWebSocket : TradingViewWebSocketLike {
    private val listeners: MutableList<TradingViewListener> = CopyOnWriteArrayList()

    val commandsSent: MutableList<Pair<String, List<Any>>> = mutableListOf()

    var closed: Boolean = false
        private set

    override fun addListener(listener: TradingViewListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TradingViewListener) {
        listeners.remove(listener)
    }

    override fun send(method: String, params: List<Any>) {
        commandsSent.add(method to params)
    }

    override fun close() {
        closed = true
    }

    fun replay(frames: Sequence<TradingViewFrame>) {
        frames.forEach { frame -> listeners.forEach { it.onFrame(frame) } }
    }

    fun simulateDisconnect(reason: String) {
        listeners.forEach { it.onDisconnected(reason) }
    }

    fun simulateConnect() {
        listeners.forEach { it.onConnected() }
    }
}
```

- [ ] **Step 5: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewQuoteSessionTest"`
Expected: compile failure (`Unresolved reference: TradingViewQuoteSession`).

- [ ] **Step 6: Implement `TradingViewQuoteSession`**

`src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewQuoteSession.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class TradingViewQuoteSession(
    private val webSocket: TradingViewWebSocketLike,
    private val clock: Clock,
    private val sessionIdGenerator: () -> String = ::randomSessionId,
    private val fields: List<String> = DEFAULT_FIELDS,
) : TradingViewListener {
    private val log = LoggerFactory.getLogger(TradingViewQuoteSession::class.java)

    private val sessionId: String = sessionIdGenerator()
    private val symbols: MutableList<String> = mutableListOf()
    private val lastValues: MutableMap<String, MutableMap<String, BigDecimal>> = ConcurrentHashMap()

    private var onTick: ((Tick) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var onDisconnect: (() -> Unit)? = null

    fun subscribe(
        symbols: List<String>,
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
    ) {
        this.onTick = onTick
        this.onError = onError
        this.onDisconnect = onDisconnect
        this.symbols.addAll(symbols)
        webSocket.addListener(this)
        sendSubscribeCommands()
    }

    fun unsubscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return
        webSocket.send("quote_remove_symbols", listOf(sessionId) + symbols)
        this.symbols.removeAll(symbols.toSet())
    }

    fun close() {
        webSocket.removeListener(this)
        symbols.clear()
        lastValues.clear()
    }

    override fun onFrame(frame: TradingViewFrame) {
        if (frame !is TradingViewFrame.Message) return
        if (frame.method != "qsd") return
        runCatching {
            val data = frame.paramAsObject(1)
            val name = data["n"]?.jsonPrimitive?.content ?: return
            val values = data["v"]?.jsonObject ?: return
            emitTick(name, values)
        }.onFailure { t ->
            log.warn("Cannot translate qsd frame: ${frame.toWireJson()}", t)
            onError?.invoke(t)
        }
    }

    override fun onConnected() {
        if (symbols.isNotEmpty()) {
            sendSubscribeCommands()
        }
    }

    override fun onDisconnected(reason: String) {
        log.warn("TradingViewQuoteSession disconnected: $reason")
        onDisconnect?.invoke()
    }

    private fun sendSubscribeCommands() {
        webSocket.send("quote_create_session", listOf(sessionId))
        webSocket.send("quote_set_fields", listOf(sessionId) + fields)
        if (symbols.isNotEmpty()) {
            webSocket.send("quote_add_symbols", listOf(sessionId) + symbols)
        }
    }

    private fun emitTick(name: String, values: JsonObject) {
        val state = lastValues.getOrPut(name) { mutableMapOf() }
        for ((key, element) in values) {
            val numeric = element.jsonPrimitive.content.toBigDecimalOrNull() ?: continue
            state[key] = numeric
        }
        val price = state["lp"] ?: return
        val tick =
            Tick(
                symbol = name,
                price = price.setScale(Money.SCALE, Money.ROUNDING),
                timestamp = clock.now(),
                bid = state["bid"]?.setScale(Money.SCALE, Money.ROUNDING),
                ask = state["ask"]?.setScale(Money.SCALE, Money.ROUNDING),
                volume = state["volume"]?.setScale(Money.SCALE, Money.ROUNDING),
            )
        onTick?.invoke(tick)
    }

    companion object {
        val DEFAULT_FIELDS: List<String> = listOf("lp", "bid", "ask", "volume", "ch", "chp")

        private fun randomSessionId(): String {
            val chars = ('a'..'z') + ('0'..'9')
            return (1..8).map { chars.random(Random) }.joinToString("")
        }
    }
}
```

- [ ] **Step 7: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewQuoteSessionTest"`
Expected: 5 tests PASS.

- [ ] **Step 8: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocket.kt src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewQuoteSession.kt src/test/kotlin/com/qkt/marketdata/live/tv/FakeTradingViewWebSocket.kt src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewQuoteSessionTest.kt src/test/resources/tv-fixtures/quote-session-eurusd.jsonl
git commit -m "feat(marketdata): add TradingViewQuoteSession with offline replay test"
```

---

### Task 7: Add `TradingViewChartSession` (offline replay test)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewChartSession.kt`
- Create: `src/test/resources/tv-fixtures/chart-session-eurusd-m5.jsonl`
- Create: `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewChartSessionTest.kt`

`TradingViewChartSession` is the historical-bars query. Synchronous from caller's perspective: `getBars(symbol, resolution, count, toTimestampSeconds): List<Candle>`. Implementation:

- Sends `chart_create_session`, then `resolve_symbol`, then `create_series` with the requested resolution and bar count.
- Collects `timescale_update` frames into a list. Each `timescale_update` carries an array of bar tuples `[time_seconds, open, high, low, close, volume]`.
- Closes the chart session via `remove_series` + `chart_delete_session` once the data is collected.
- Throws `java.io.IOException` on timeout (default 30 seconds, configurable).
- Throws `TradingViewProtocolException` on protocol-level failures (e.g. `series_error` frame).

The fixture `chart-session-eurusd-m5.jsonl` contains a `timescale_update` frame with 3 sample M5 bars. The test loads, replays, asserts the resulting candle list.

- [ ] **Step 1: Create the fixture**

`src/test/resources/tv-fixtures/chart-session-eurusd-m5.jsonl`:

```jsonl
{"m":"timescale_update","p":["cs_test",{"sds_1":{"s":[{"i":0,"v":[1700000000,1.10005,1.10025,1.09995,1.10010,123]},{"i":1,"v":[1700000300,1.10010,1.10030,1.10005,1.10020,150]},{"i":2,"v":[1700000600,1.10020,1.10040,1.10015,1.10035,98]}]}}]}
{"m":"series_completed","p":["cs_test","sds_1","streaming","completed"]}
```

The `timescale_update` frame is one envelope with all bars; this matches TV's actual behavior for `create_series` with a fixed bar count.

- [ ] **Step 2: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewChartSessionTest.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.common.Money
import java.io.File
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingViewChartSessionTest {
    private fun loadFrames(resource: String): Sequence<TradingViewFrame> =
        File("src/test/resources/$resource")
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .map { TradingViewFrame.parse(it) }
            .asSequence()

    @Test
    fun `getBars returns candles from a timescale_update frame`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewChartSession(ws, sessionIdGenerator = { "cs_test" }, seriesIdGenerator = { "sds_1" })

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply { isDaemon = true; start() }

        val bars = session.getBars(symbol = "OANDA:EURUSD", resolution = "5", count = 3, toTimestampSeconds = 1_700_000_900L)
        thread.join()

        assertThat(bars).hasSize(3)
        assertThat(bars[0].symbol).isEqualTo("OANDA:EURUSD")
        assertThat(bars[0].open).isEqualByComparingTo<BigDecimal>(Money.of("1.10005"))
        assertThat(bars[0].high).isEqualByComparingTo<BigDecimal>(Money.of("1.10025"))
        assertThat(bars[0].low).isEqualByComparingTo<BigDecimal>(Money.of("1.09995"))
        assertThat(bars[0].close).isEqualByComparingTo<BigDecimal>(Money.of("1.10010"))
        assertThat(bars[0].volume).isEqualByComparingTo<BigDecimal>(Money.of("123"))
        assertThat(bars[0].startTime).isEqualTo(1_700_000_000_000L)
        assertThat(bars[0].endTime).isEqualTo(1_700_000_300_000L)
    }

    @Test
    fun `getBars sends create_session resolve_symbol create_series in order`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewChartSession(ws, sessionIdGenerator = { "cs_test" }, seriesIdGenerator = { "sds_1" })

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply { isDaemon = true; start() }

        session.getBars("OANDA:EURUSD", "5", 3, 1_700_000_900L)
        thread.join()

        val methods = ws.commandsSent.map { it.first }
        val firstThree = methods.take(3)
        assertThat(firstThree).containsExactly(
            "chart_create_session",
            "resolve_symbol",
            "create_series",
        )
    }

    @Test
    fun `getBars throws IOException on timeout`() {
        val ws = FakeTradingViewWebSocket()
        val session =
            TradingViewChartSession(
                ws,
                sessionIdGenerator = { "cs_test" },
                seriesIdGenerator = { "sds_1" },
                timeoutMs = 200,
            )

        org.assertj.core.api.Assertions
            .assertThatThrownBy { session.getBars("OANDA:EURUSD", "5", 3, 1_700_000_900L) }
            .isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `getBars cleans up the chart session after success`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewChartSession(ws, sessionIdGenerator = { "cs_test" }, seriesIdGenerator = { "sds_1" })

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply { isDaemon = true; start() }

        session.getBars("OANDA:EURUSD", "5", 3, 1_700_000_900L)
        thread.join()

        assertThat(ws.commandsSent.map { it.first }).contains("chart_delete_session")
    }
}
```

- [ ] **Step 3: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewChartSessionTest"`
Expected: compile failure (`Unresolved reference: TradingViewChartSession`).

- [ ] **Step 4: Implement `TradingViewChartSession`**

`src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewChartSession.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class TradingViewChartSession(
    private val webSocket: TradingViewWebSocketLike,
    private val sessionIdGenerator: () -> String = ::randomSessionId,
    private val seriesIdGenerator: () -> String = ::randomSeriesId,
    private val timeoutMs: Long = 30_000L,
) {
    private val log = LoggerFactory.getLogger(TradingViewChartSession::class.java)

    fun getBars(
        symbol: String,
        resolution: String,
        count: Int,
        toTimestampSeconds: Long,
    ): List<Candle> {
        require(count > 0) { "count must be > 0: $count" }
        val sessionId = sessionIdGenerator()
        val seriesId = seriesIdGenerator()

        val collected: AtomicReference<List<Candle>> = AtomicReference(emptyList())
        val latch = CountDownLatch(1)
        val errorRef: AtomicReference<Throwable?> = AtomicReference(null)

        val listener =
            object : TradingViewListener {
                override fun onFrame(frame: TradingViewFrame) {
                    if (frame !is TradingViewFrame.Message) return
                    if (frame.method != "timescale_update") return
                    runCatching {
                        val update = frame.paramAsObject(1)
                        val series = update[seriesId]?.jsonObject ?: return
                        val rows = series["s"]?.jsonArray ?: return
                        val candles = rows.map { row -> rowToCandle(symbol, resolution, row.jsonObject) }
                        collected.set(candles)
                        latch.countDown()
                    }.onFailure { t ->
                        errorRef.set(t)
                        latch.countDown()
                    }
                }

                override fun onConnected() {}

                override fun onDisconnected(reason: String) {
                    errorRef.set(IOException("TradingView disconnected: $reason"))
                    latch.countDown()
                }
            }

        webSocket.addListener(listener)
        try {
            webSocket.send("chart_create_session", listOf(sessionId))
            webSocket.send("resolve_symbol", listOf(sessionId, "symbol_1", symbol))
            webSocket.send("create_series", listOf(sessionId, seriesId, "s1", "symbol_1", resolution, count, ""))
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("TradingView chart_session timed out after ${timeoutMs}ms for $symbol@$resolution")
            }
            errorRef.get()?.let { throw IOException("TradingView chart_session failed", it) }
            return collected.get()
        } finally {
            runCatching {
                webSocket.send("remove_series", listOf(sessionId, seriesId))
                webSocket.send("chart_delete_session", listOf(sessionId))
            }
            webSocket.removeListener(listener)
        }
    }

    private fun rowToCandle(symbol: String, resolution: String, row: JsonObject): Candle {
        val v = row["v"]?.jsonArray ?: error("timescale_update row missing v: $row")
        val timeSeconds = v[0].jsonPrimitive.content.toLong()
        val open = v[1].jsonPrimitive.content.toBigDecimal()
        val high = v[2].jsonPrimitive.content.toBigDecimal()
        val low = v[3].jsonPrimitive.content.toBigDecimal()
        val close = v[4].jsonPrimitive.content.toBigDecimal()
        val volume = if (v.size > 5) v[5].jsonPrimitive.content.toBigDecimalOrNull() ?: BigDecimal.ZERO else BigDecimal.ZERO
        val durationMs = resolutionToMillis(resolution)
        val startTime = timeSeconds * 1_000L
        val endTime = startTime + durationMs
        return Candle(
            symbol = symbol,
            open = open.setScale(Money.SCALE, Money.ROUNDING),
            high = high.setScale(Money.SCALE, Money.ROUNDING),
            low = low.setScale(Money.SCALE, Money.ROUNDING),
            close = close.setScale(Money.SCALE, Money.ROUNDING),
            volume = volume.setScale(Money.SCALE, Money.ROUNDING),
            startTime = startTime,
            endTime = endTime,
        )
    }

    private fun resolutionToMillis(resolution: String): Long =
        when (resolution) {
            "1S" -> 1_000L
            "5S" -> 5_000L
            "15S" -> 15_000L
            "30S" -> 30_000L
            "1" -> 60_000L
            "5" -> 300_000L
            "15" -> 900_000L
            "30" -> 1_800_000L
            "60" -> 3_600_000L
            "240" -> 14_400_000L
            "1D" -> 86_400_000L
            "1W" -> 604_800_000L
            else -> error("Unknown resolution: $resolution")
        }

    companion object {
        private fun randomSessionId(): String {
            val chars = ('a'..'z') + ('0'..'9')
            return "cs_" + (1..8).map { chars.random(Random) }.joinToString("")
        }

        private fun randomSeriesId(): String {
            val chars = ('a'..'z') + ('0'..'9')
            return "sds_" + (1..6).map { chars.random(Random) }.joinToString("")
        }
    }
}
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewChartSessionTest"`
Expected: 4 tests PASS.

- [ ] **Step 6: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewChartSession.kt src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewChartSessionTest.kt src/test/resources/tv-fixtures/chart-session-eurusd-m5.jsonl
git commit -m "feat(marketdata): add TradingViewChartSession with offline replay test"
```

---

## Group D: TradingView MarketSource

### Task 8: Add `TradingViewMarketSource`

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSource.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSourceTest.kt`

`TradingViewMarketSource` composes the three pieces above into the public `MarketSource` contract:

- `name = "TradingView"`.
- `capabilities = setOf(LIVE_TICKS, BARS)` — no `TICKS` (TV doesn't expose tick history).
- `supports(symbol)`: regex match on `EXCHANGE:SYMBOL` form (one or more uppercase letters/digits, colon, one or more uppercase letters/digits/underscores). Caller is responsible for using TV's symbol form; we document this in the class Javadoc-equivalent comment.
- `liveTicks(symbols)`: constructs a `LiveTickSource` backed by `TradingViewQuoteSession`, wraps it in `LiveTickFeed`. The `LiveTickFeed` constructor takes the source and queue capacity (`10_000` default).
- `bars(symbol, window, range)`: maps `window` → TV resolution; maps `range` → bar count + `toTimestampSeconds`. Bar count rounded up to the nearest integer. TV doesn't accept arbitrary date ranges, so we request `count` bars going back from `range.to` and filter client-side to `[range.from, range.to)`. Returns `Sequence<Candle>`.
- `ticks(...)`: throws `UnsupportedDataException` (not advertised).
- Constructor: `TradingViewMarketSource(webSocket: TradingViewWebSocketLike, clock: Clock)`. Default convenience factory: `TradingViewMarketSource.connect()` constructs a real `TradingViewWebSocket`, calls `.connect()`, returns the source.
- Lifecycle: `close()` to shut down the underlying WS.

Internal `LiveTickSource` adapter:

```kotlin
private class TradingViewLiveTickSource(
    private val ws: TradingViewWebSocketLike,
    private val clock: Clock,
    private val symbols: List<String>,
) : LiveTickSource {
    private val session = TradingViewQuoteSession(ws, clock)

    override fun start(onTick, onError, onDisconnect) {
        session.subscribe(symbols, onTick, onError, onDisconnect)
    }

    override fun stop() {
        session.close()
    }
}
```

This is the bridge between the vendor protocol layer and the runtime layer.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSourceTest.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.UnsupportedDataException
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewMarketSourceTest {
    private fun loadFrames(resource: String): Sequence<TradingViewFrame> =
        File("src/test/resources/$resource")
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .map { TradingViewFrame.parse(it) }
            .asSequence()

    @Test
    fun `name and capabilities`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        assertThat(src.name).isEqualTo("TradingView")
        assertThat(src.capabilities).containsExactlyInAnyOrder(
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
        )
    }

    @Test
    fun `supports validates EXCHANGE_COLON_SYMBOL form`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        assertThat(src.supports("OANDA:EURUSD")).isTrue()
        assertThat(src.supports("BINANCE:BTCUSDT")).isTrue()
        assertThat(src.supports("EURUSD")).isFalse()
        assertThat(src.supports("oanda:eurusd")).isFalse()
        assertThat(src.supports("OANDA:")).isFalse()
    }

    @Test
    fun `ticks throws UnsupportedDataException because TV does not expose tick history`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.parse("2024-01-16T00:00:00Z"),
            )
        assertThatThrownBy { src.ticks("OANDA:EURUSD", range).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("TICKS")
    }

    @Test
    fun `bars maps window to resolution and clips by range`() {
        val ws = FakeTradingViewWebSocket()
        val src =
            TradingViewMarketSource(
                webSocket = ws,
                clock = FixedClock(time = 1_700_001_000_000L),
                chartSessionFactory = { _ ->
                    TradingViewChartSession(
                        ws,
                        sessionIdGenerator = { "cs_test" },
                        seriesIdGenerator = { "sds_1" },
                    )
                },
            )

        val thread =
            Thread {
                Thread.sleep(50)
                ws.replay(loadFrames("tv-fixtures/chart-session-eurusd-m5.jsonl"))
            }.apply { isDaemon = true; start() }

        val range =
            TimeRange(
                Instant.ofEpochSecond(1_700_000_000L),
                Instant.ofEpochSecond(1_700_000_900L),
            )
        val bars = src.bars("OANDA:EURUSD", TimeWindow.FIVE_MINUTES, range).toList()
        thread.join()

        assertThat(bars).hasSize(3)
        assertThat(bars[0].close).isEqualByComparingTo<BigDecimal>(Money.of("1.10010"))
        assertThat(bars.last().close).isEqualByComparingTo<BigDecimal>(Money.of("1.10035"))
    }

    @Test
    fun `bars on unsupported window throws UnsupportedDataException`() {
        val src =
            TradingViewMarketSource(
                webSocket = FakeTradingViewWebSocket(),
                clock = FixedClock(time = 0L),
            )
        val odd = TimeWindow(13_000L)
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.parse("2024-01-15T00:01:00Z"),
            )
        assertThatThrownBy { src.bars("OANDA:EURUSD", odd, range).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("13000")
    }

    @Test
    fun `liveTicks returns a LiveTickFeed backed by a quote session`() {
        val ws = FakeTradingViewWebSocket()
        val src =
            TradingViewMarketSource(
                webSocket = ws,
                clock = FixedClock(time = 1_700_000_000_000L),
            )
        val feed = src.liveTicks(listOf("OANDA:EURUSD"))

        ws.replay(loadFrames("tv-fixtures/quote-session-eurusd.jsonl"))

        val ticks = generateSequence { feed.next() }.take(3).toList()
        assertThat(ticks.map { it.symbol }).allMatch { it == "OANDA:EURUSD" }

        feed.close()
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewMarketSourceTest"`
Expected: compile failure (`Unresolved reference: TradingViewMarketSource`).

- [ ] **Step 3: Implement `TradingViewMarketSource`**

`src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSource.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.live.LiveTickSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.UnsupportedDataException
import java.time.Instant

class TradingViewMarketSource(
    private val webSocket: TradingViewWebSocketLike,
    private val clock: Clock = SystemClock(),
    private val queueCapacity: Int = 10_000,
    private val chartSessionFactory: (TradingViewWebSocketLike) -> TradingViewChartSession = { ws ->
        TradingViewChartSession(ws)
    },
) : MarketSource, AutoCloseable {
    override val name: String = "TradingView"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    override fun supports(symbol: String): Boolean = SYMBOL_REGEX.matches(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all { supports(it) }) {
            "TradingView symbols must match EXCHANGE:SYMBOL form: $symbols"
        }
        val source: LiveTickSource = TradingViewLiveTickSource(webSocket, clock, symbols)
        return LiveTickFeed(source = source, queueCapacity = queueCapacity)
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        require(supports(symbol)) {
            "TradingView symbol must match EXCHANGE:SYMBOL form: $symbol"
        }
        val resolution = TradingViewResolution.fromTimeWindow(window)
        val totalMs = range.to.toEpochMilli() - range.from.toEpochMilli()
        val rawCount = (totalMs + window.durationMs - 1) / window.durationMs
        val count = rawCount.toInt().coerceAtLeast(1)
        val toSeconds = range.to.epochSecond
        val candles =
            chartSessionFactory(webSocket).getBars(
                symbol = symbol,
                resolution = resolution,
                count = count,
                toTimestampSeconds = toSeconds,
            )
        return candles
            .asSequence()
            .filter { it.startTime >= range.from.toEpochMilli() && it.startTime < range.to.toEpochMilli() }
    }

    override fun ticks(symbol: String, range: TimeRange): Sequence<Tick> =
        throw UnsupportedDataException(
            MarketSourceCapability.TICKS,
            "TradingViewMarketSource does not expose tick history; use bars()",
        )

    override fun close() {
        webSocket.close()
    }

    private class TradingViewLiveTickSource(
        private val ws: TradingViewWebSocketLike,
        private val clock: Clock,
        private val symbols: List<String>,
    ) : LiveTickSource {
        private var session: TradingViewQuoteSession? = null

        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
        ) {
            val qs = TradingViewQuoteSession(ws, clock)
            session = qs
            qs.subscribe(symbols, onTick, onError, onDisconnect)
        }

        override fun stop() {
            session?.close()
            session = null
        }
    }

    companion object {
        private val SYMBOL_REGEX = Regex("^[A-Z0-9]+:[A-Z0-9_]+$")

        fun connect(
            url: String = TradingViewWebSocket.DEFAULT_URL,
            origin: String = TradingViewWebSocket.DEFAULT_ORIGIN,
            authToken: String = TradingViewWebSocket.ANONYMOUS_TOKEN,
            clock: Clock = SystemClock(),
        ): TradingViewMarketSource =
            TradingViewMarketSource(
                webSocket = TradingViewWebSocket.connect(url = url, origin = origin, authToken = authToken),
                clock = clock,
            )
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.live.tv.TradingViewMarketSourceTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSource.kt src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSourceTest.kt
git commit -m "feat(marketdata): add TradingViewMarketSource"
```

---

### Task 9: Add `TradingViewLiveSmokeTest` (manual e2e)

**Files:**
- Create: `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewLiveSmokeTest.kt`

`@Tag("e2e")`, excluded from default `./gradlew test` (Task 1 wires the filter). Connects to real TV, subscribes to `OANDA:EURUSD`, asserts at least one tick within 30 seconds. Documented in README how to run.

This test exists to give a single manual command to verify the production WS path end-to-end. It is not gated on CI; it is a smoke test for human runs after meaningful changes to the TV code.

- [ ] **Step 1: Write the smoke test**

`src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewLiveSmokeTest.kt`:

```kotlin
package com.qkt.marketdata.live.tv

import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("e2e")
class TradingViewLiveSmokeTest {
    @Test
    fun `subscribes to OANDA EURUSD and receives at least one tick within 30 seconds`() {
        val ws = TradingViewWebSocket.connect()
        val source =
            TradingViewMarketSource(
                webSocket = ws,
                clock = SystemClock(),
            )

        val latch = CountDownLatch(1)
        val captured = mutableListOf<Tick>()
        val feed = source.liveTicks(listOf("OANDA:EURUSD"))

        val reader =
            Thread {
                while (latch.count > 0) {
                    val tick = feed.next() ?: break
                    captured.add(tick)
                    latch.countDown()
                }
            }.apply { isDaemon = true; start() }

        val received = latch.await(30, TimeUnit.SECONDS)

        try {
            assertThat(received).isTrue()
            assertThat(captured).isNotEmpty()
            assertThat(captured.first().symbol).isEqualTo("OANDA:EURUSD")
            assertThat(captured.first().price.signum()).isPositive()
        } finally {
            feed.close()
            source.close()
            reader.interrupt()
            reader.join(Duration.ofSeconds(2).toMillis())
        }
    }
}
```

- [ ] **Step 2: Verify the test is excluded from the default run**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, ~377 tests, the smoke test does not appear in the run report.

- [ ] **Step 3: Verify the test is selectable via the include flag**

Run: `./gradlew test -PincludeTags=e2e --info` (network required; run only when verifying the live path).

If you don't have network access right now, skip the actual run; the structural placement is what we are verifying. The test itself becomes runnable once a human triggers it.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewLiveSmokeTest.kt
git commit -m "test(marketdata): add TradingView e2e live smoke test"
```

---

## Group E: Sample strategies

### Task 10: Add `BreakoutOfYesterdayHighStrategy` sample

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/samples/BreakoutOfYesterdayHighStrategy.kt`
- Create: `src/test/kotlin/com/qkt/strategy/samples/BreakoutOfYesterdayHighStrategyTest.kt`

`BreakoutOfYesterdayHighStrategy` is a session-anchored breakout. Per spec §14: holds a `PreviousDayHigh` indicator per symbol via `IndicatorMap` keyed on symbol, factory closes over `ctx.calendar`, `ctx.source`, `ctx.clock`. On `onTickWithContext`: if `tick.price > previousDayHigh.value()` and the indicator is ready, emit `Signal.Buy(tick.symbol, size)`. Emit at most once per `(symbol, session-day)` to avoid spamming signals as price stays above the level.

The strategy is `Warmable` with `WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 1440)` — that's 24 hours of M1 bars, enough to seed `PreviousDayHigh` deterministically.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/strategy/samples/BreakoutOfYesterdayHighStrategyTest.kt`:

```kotlin
package com.qkt.strategy.samples

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Mode
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.WarmupSpec
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BreakoutOfYesterdayHighStrategyTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")
    private val day14Start = Instant.parse("2024-01-14T00:00:00Z").toEpochMilli()

    private fun candle(high: String, startMs: Long): Candle =
        Candle(
            symbol = "X",
            open = Money.of(high),
            high = Money.of(high),
            low = Money.of(high),
            close = Money.of(high),
            volume = Money.of("1"),
            startTime = startMs,
            endTime = startMs + 60_000L,
        )

    private fun fakeSource(): MarketSource =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String): Boolean = true

            override fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> {
                if (range.from.toEpochMilli() == day14Start) {
                    return sequenceOf(
                        candle("110", day14Start),
                        candle("115", day14Start + 60_000L),
                        candle("113", day14Start + 120_000L),
                    )
                }
                return emptySequence()
            }
        }

    private fun ctx(): SessionContext =
        SessionContext(
            mode = Mode.BACKTEST,
            clock = FixedClock(time = day15.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
            source = fakeSource(),
        )

    @Test
    fun `emits Buy when price exceeds previous day high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X", size = Money.of("1"))

        strategy.onTickWithContext(
            tick = Tick("X", Money.of("116"), day15.toEpochMilli()),
            ctx = ctx(),
            emit = { emitted.add(it) },
        )

        assertThat(emitted).containsExactly(Signal.Buy("X", Money.of("1")))
    }

    @Test
    fun `does not emit when price is below previous day high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X")

        strategy.onTickWithContext(
            tick = Tick("X", Money.of("113"), day15.toEpochMilli()),
            ctx = ctx(),
            emit = { emitted.add(it) },
        )

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `emits at most once per session day`() {
        val emitted = mutableListOf<Signal>()
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X")

        strategy.onTickWithContext(Tick("X", Money.of("116"), day15.toEpochMilli()), ctx(), { emitted.add(it) })
        strategy.onTickWithContext(Tick("X", Money.of("117"), day15.toEpochMilli() + 1_000L), ctx(), { emitted.add(it) })
        strategy.onTickWithContext(Tick("X", Money.of("118"), day15.toEpochMilli() + 2_000L), ctx(), { emitted.add(it) })

        assertThat(emitted).hasSize(1)
    }

    @Test
    fun `warmup spec covers a full prior day of M1 bars`() {
        val strategy = BreakoutOfYesterdayHighStrategy(symbol = "X")
        assertThat(strategy.warmup).isEqualTo(WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 1440))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategyTest"`
Expected: compile failure (`Unresolved reference: BreakoutOfYesterdayHighStrategy`).

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/strategy/samples/BreakoutOfYesterdayHighStrategy.kt`:

```kotlin
package com.qkt.strategy.samples

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.indicators.range.PreviousDayHigh
import com.qkt.marketdata.Tick
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.ZoneOffset

class BreakoutOfYesterdayHighStrategy(
    private val symbol: String,
    private val size: BigDecimal = Money.of("1"),
) : Strategy, Warmable {
    init {
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1440)

    private val indicators: MutableMap<String, PreviousDayHigh> = mutableMapOf()
    private var lastEmitDayEpoch: Long = Long.MIN_VALUE

    override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
        // mode-aware path drives this strategy; default no-op when ctx is unavailable.
    }

    override fun onTickWithContext(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol != symbol) return
        val indicator =
            indicators.getOrPut(symbol) {
                PreviousDayHigh(symbol, ctx.calendar, ctx.source, ctx.clock)
            }
        indicator.update(tick)
        val level = indicator.value() ?: return
        if (tick.price <= level) return
        val today = java.time.Instant.ofEpochMilli(ctx.clock.now()).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        if (today == lastEmitDayEpoch) return
        lastEmitDayEpoch = today
        emit(Signal.Buy(symbol, size))
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategyTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/strategy/samples/BreakoutOfYesterdayHighStrategy.kt src/test/kotlin/com/qkt/strategy/samples/BreakoutOfYesterdayHighStrategyTest.kt
git commit -m "feat(strategy): add BreakoutOfYesterdayHighStrategy sample"
```

---

### Task 11: Add `RollingHighBreakoutStrategy` sample

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/samples/RollingHighBreakoutStrategy.kt`
- Create: `src/test/kotlin/com/qkt/strategy/samples/RollingHighBreakoutStrategyTest.kt`

`RollingHighBreakoutStrategy` demonstrates the raw `RangeAggregateIndicator` (non-session). Constructor: `(symbol, lookback: Duration = Duration.ofDays(3), window: TimeWindow = TimeWindow.ONE_HOUR, size: BigDecimal = Money.of("1"))`. On `onTickWithContext`: when current price exceeds the rolling-window high, emit Buy. Refresh trigger: `OnSessionRollover`. Like the session-anchored strategy, emits at most once per session.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/strategy/samples/RollingHighBreakoutStrategyTest.kt`:

```kotlin
package com.qkt.strategy.samples

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Mode
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RollingHighBreakoutStrategyTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    private fun candle(high: String, startMs: Long): Candle =
        Candle(
            symbol = "X",
            open = Money.of(high),
            high = Money.of(high),
            low = Money.of(high),
            close = Money.of(high),
            volume = Money.of("1"),
            startTime = startMs,
            endTime = startMs + 3_600_000L,
        )

    private fun fakeSource(rangeHigh: String): MarketSource =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String): Boolean = true

            override fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> =
                sequenceOf(candle(rangeHigh, range.from.toEpochMilli()))
        }

    private fun ctx(rangeHigh: String): SessionContext =
        SessionContext(
            mode = Mode.BACKTEST,
            clock = FixedClock(time = now.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
            source = fakeSource(rangeHigh),
        )

    @Test
    fun `emits Buy when price exceeds rolling window high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = RollingHighBreakoutStrategy(symbol = "X")
        strategy.onTickWithContext(
            tick = Tick("X", Money.of("130"), now.toEpochMilli()),
            ctx = ctx("125"),
            emit = { emitted.add(it) },
        )
        assertThat(emitted).containsExactly(Signal.Buy("X", Money.of("1")))
    }

    @Test
    fun `does not emit when price is below rolling window high`() {
        val emitted = mutableListOf<Signal>()
        val strategy = RollingHighBreakoutStrategy(symbol = "X")
        strategy.onTickWithContext(
            tick = Tick("X", Money.of("120"), now.toEpochMilli()),
            ctx = ctx("125"),
            emit = { emitted.add(it) },
        )
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `emits at most once per session`() {
        val emitted = mutableListOf<Signal>()
        val strategy = RollingHighBreakoutStrategy(symbol = "X", lookback = Duration.ofDays(3))
        val context = ctx("125")
        strategy.onTickWithContext(Tick("X", Money.of("130"), now.toEpochMilli()), context, { emitted.add(it) })
        strategy.onTickWithContext(Tick("X", Money.of("131"), now.toEpochMilli() + 1_000L), context, { emitted.add(it) })
        assertThat(emitted).hasSize(1)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.strategy.samples.RollingHighBreakoutStrategyTest"`
Expected: compile failure.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/strategy/samples/RollingHighBreakoutStrategy.kt`:

```kotlin
package com.qkt.strategy.samples

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.RefreshTrigger
import com.qkt.common.TimeMark
import com.qkt.common.TimeRange
import com.qkt.indicators.range.RangeAggregateIndicator
import com.qkt.marketdata.Tick
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneOffset

class RollingHighBreakoutStrategy(
    private val symbol: String,
    private val lookback: Duration = Duration.ofDays(3),
    private val window: TimeWindow = TimeWindow.ONE_HOUR,
    private val size: BigDecimal = Money.of("1"),
) : Strategy, Warmable {
    init {
        require(!lookback.isZero && !lookback.isNegative) {
            "lookback must be positive: $lookback"
        }
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    override val warmup: WarmupSpec =
        WarmupSpec.Bars(window, count = (lookback.toMillis() / window.durationMs).toInt().coerceAtLeast(1))

    private var indicator: RangeAggregateIndicator<BigDecimal>? = null
    private var lastEmitDayEpoch: Long = Long.MIN_VALUE

    override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
        // mode-aware path drives this strategy; default no-op when ctx is unavailable.
    }

    override fun onTickWithContext(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol != symbol) return
        val agg =
            indicator
                ?: RangeAggregateIndicator(
                    symbol = symbol,
                    window = window,
                    rangeSpec = {
                        TimeRange.of(
                            from = TimeMark.RelativeToNow(lookback.negated()),
                            to = TimeMark.Now,
                            clock = ctx.clock,
                            calendar = ctx.calendar,
                        )
                    },
                    reduce = { it.maxOfOrNull { c -> c.high } },
                    source = ctx.source,
                    clock = ctx.clock,
                    refreshOn = RefreshTrigger.OnSessionRollover,
                ).also { indicator = it }
        agg.update(tick)
        val level = agg.value() ?: return
        if (tick.price <= level) return
        val today =
            java.time.Instant
                .ofEpochMilli(ctx.clock.now())
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toEpochDay()
        if (today == lastEmitDayEpoch) return
        lastEmitDayEpoch = today
        emit(Signal.Buy(symbol, size))
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.strategy.samples.RollingHighBreakoutStrategyTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/strategy/samples/RollingHighBreakoutStrategy.kt src/test/kotlin/com/qkt/strategy/samples/RollingHighBreakoutStrategyTest.kt
git commit -m "feat(strategy): add RollingHighBreakoutStrategy sample"
```

---

## Group F: Live demo + README

### Task 12: Add `LiveDemo` main entry point

**Files:**
- Create: `src/main/kotlin/com/qkt/app/LiveDemo.kt`

`LiveDemo` is a `main` function under `com.qkt.app`. Wires:

- `TradingViewMarketSource.connect()` for the data source.
- One `BreakoutOfYesterdayHighStrategy("OANDA:EURUSD")` for an FX leg.
- A `LiveSession` over `["OANDA:EURUSD", "OANDA:XAUUSD", "BINANCE:BTCUSDT"]`.
- Risk: a single `MaxPositionSize` per symbol (defensive default).
- Logging: prints fills + rejections to stdout; writes a single line per tick at INFO level.

The demo runs until killed (Ctrl-C). It is the canonical "qkt is alive" smoke test. Documented in README.

- [ ] **Step 1: Implement**

`src/main/kotlin/com/qkt/app/LiveDemo.kt`:

```kotlin
package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.Strategy
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger("LiveDemo")

fun main() {
    log.info("Starting qkt LiveDemo (TradingView -> LiveSession -> MockBroker)")

    val source = TradingViewMarketSource.connect(clock = SystemClock())

    val strategies: List<Strategy> =
        listOf(
            BreakoutOfYesterdayHighStrategy("OANDA:EURUSD", size = Money.of("1")),
        )
    val rules: List<RiskRule> =
        listOf(
            MaxPositionSize(symbol = "OANDA:EURUSD", maxQty = Money.of("3")),
            MaxPositionSize(symbol = "OANDA:XAUUSD", maxQty = Money.of("1")),
            MaxPositionSize(symbol = "BINANCE:BTCUSDT", maxQty = Money.of("1")),
        )

    val session =
        LiveSession(
            strategies = strategies,
            rules = rules,
            source = source,
            symbols = listOf("OANDA:EURUSD", "OANDA:XAUUSD", "BINANCE:BTCUSDT"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
        )

    val handle = session.start()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutting down LiveSession...")
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            source.close()
        },
    )

    log.info("LiveSession running. Press Ctrl-C to stop.")
    handle.awaitTermination(Duration.ofDays(365))
}
```

- [ ] **Step 2: Verify the file compiles and is reachable as a main**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

The default `application.mainClass` remains `com.qkt.app.MainKt`; running the demo is done with `./gradlew run -PmainClass=com.qkt.app.LiveDemoKt` or via `./gradlew runLiveDemo` if we add a Gradle task. We document the simplest path in the README (Task 13).

For now we keep the build untouched and rely on the run-class override. To make the override available, edit `build.gradle.kts` to allow a system property to choose the main class. Smallest precise diff is to register a separate task:

Append to `build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runLiveDemo") {
    group = "application"
    description = "Run the live TradingView demo"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.app.LiveDemoKt")
}
```

- [ ] **Step 3: Verify the task is registered**

Run: `./gradlew tasks --group application`
Expected: `runLiveDemo` appears in the list alongside the existing `run`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/LiveDemo.kt build.gradle.kts
git commit -m "feat(app): add LiveDemo TradingView entry point"
```

---

### Task 13: Update README with "Live trading" section

**Files:**
- Modify: `README.md`

Add a "Live trading" section that explains:

- The TradingView vendor and its constraints (anonymous mode, free-tier symbol coverage, no premium, no historical ticks).
- How to run the demo (`./gradlew runLiveDemo`).
- How to run the smoke test (`./gradlew test -PincludeTags=e2e`).
- A 10-line copy-pasteable Kotlin example showing how to construct a `LiveSession` against TV.
- Caveats: the engine and broker are paper-trading; real execution requires a `LiveBroker` (Phase 7d / Phase 8).

- [ ] **Step 1: Edit `README.md` — insert the new section after the existing "Getting real data" section**

Append (after the existing content):

```markdown

## Live trading (TradingView)

Phase 7 ships a live runtime alongside the historical backtest. The first vendor is TradingView via WebSocket, anonymous mode (no login required, free-tier symbol coverage). Live ticks feed the same `TradingPipeline` your backtest uses; the only difference is the `TickFeed` (live) and the `Clock` (system time).

### Run the bundled demo

```bash
./gradlew runLiveDemo
```

The demo connects to TradingView, subscribes to `OANDA:EURUSD`, `OANDA:XAUUSD`, and `BINANCE:BTCUSDT`, runs `BreakoutOfYesterdayHighStrategy` against EURUSD, and prints fills via the existing `MockBroker` to stdout. Press Ctrl-C to stop.

### Construct a LiveSession in your own code

```kotlin
import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy

fun main() {
    val source = TradingViewMarketSource.connect()
    val handle =
        LiveSession(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("OANDA:EURUSD")),
            source = source,
            symbols = listOf("OANDA:EURUSD"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
        ).start()
    handle.awaitTermination(java.time.Duration.ofMinutes(10))
}
```

### Run the live smoke test (manual)

The smoke test connects to real TradingView and asserts that at least one tick arrives within 30 seconds. Tagged `@Tag("e2e")` and excluded from the default `./gradlew test` run.

```bash
./gradlew test -PincludeTags=e2e
```

### TradingView vendor constraints

- **Anonymous mode only.** Logged-in / premium symbol coverage and higher rate limits are not implemented in Phase 7. See `docs/phases/phase-7-live-runtime.md` for the deferred-work list.
- **No tick history.** TradingView does not expose historical ticks. `TradingViewMarketSource.ticks(...)` throws `UnsupportedDataException`. Use bar history via `bars(...)` for warmup.
- **Symbol form is `EXCHANGE:SYMBOL`.** TV does not accept bare symbols (`EURUSD`). Use `OANDA:EURUSD`, `BINANCE:BTCUSDT`, etc. The `TradingViewMarketSource.supports(symbol)` check rejects symbols that do not match the form.
- **Rate limits apply.** The anonymous quote-session subscribes to a few dozen symbols comfortably. For larger universes, use `CompositeMarketSource` to route per-asset-class to dedicated vendors (e.g. Binance for crypto, OANDA for FX).
- **Paper trading only.** All fills go through `MockBroker`, which fills at the latest in-process price. Real-broker integrations land in Phase 7d / 8 behind a `LiveBroker` interface.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add Live trading section to README"
```

---

## Group G: Phase changelog

### Task 14: Write Phase 7 changelog at `docs/phases/phase-7-live-runtime.md`

**Files:**
- Create: `docs/phases/phase-7-live-runtime.md`

Per SKILL.md §6, the changelog is the user-facing artifact for the phase. Required sections (verbatim from §6):

1. **Summary** — 2–4 sentences.
2. **What's new** — bullet list of every new public type/function/package.
3. **Migration from previous phase** — 6 → 7 renames.
4. **Usage cookbook** — multiple worked examples covering every capability shipped (this is the heart of the doc).
5. **Testing patterns** — canonical fakes/fixtures + assertion examples.
6. **Known limitations** — what didn't ship.
7. **References** — spec, plans 7a/7b/7c, merge SHAs (placeholders OK).

Aim for 200–500 lines.

- [ ] **Step 1: Create the parent directory if it doesn't exist**

```bash
mkdir -p docs/phases
```

- [ ] **Step 2: Write the changelog file**

`docs/phases/phase-7-live-runtime.md`:

```markdown
# Phase 7 — Live Runtime + MarketSource Umbrella

**Status:** Shipped. Merged into `main` on (placeholder — fill in at merge).
**Spec:** [`docs/superpowers/specs/2026-05-05-trading-engine-phase7-design.md`](../superpowers/specs/2026-05-05-trading-engine-phase7-design.md)
**Plans:** 7a / 7b / 7c (linked at the bottom).

---

## Summary

Phase 7 turns qkt into a live trading runtime. The same strategy code that runs in `Backtest` now also runs against real-time market data via the new `LiveSession` runtime. Vendor-specific data acquisition is hidden behind the `MarketSource` umbrella; the first concrete vendor is TradingView (via OkHttp WebSocket, anonymous mode). Phase 7 also ships session-anchored indicators (`PreviousDayHigh`, `SessionHigh`, etc.), a pluggable `TradingCalendar` (FX / NYSE / crypto), `TimeMark` + `TimeRange.of(...)` composable time primitives, indicator warmup machinery, and a `SessionContext` that lets mode-aware strategies opt into knowing whether they are backtest or live.

---

## What's new

### Vendor-agnostic data layer

- `com.qkt.marketdata.source.MarketSource` — the umbrella interface for all market-data vendors. Three capabilities: `LIVE_TICKS`, `BARS`, `TICKS`.
- `com.qkt.marketdata.source.MarketSourceCapability` — enum.
- `com.qkt.marketdata.source.MarketRequest` — query value type (was `DataRequest`).
- `com.qkt.marketdata.source.LocalMarketSource` — Phase 6's on-disk store, now behind `MarketSource` (was `StoreHistoricalDataProvider`).
- `com.qkt.marketdata.source.CompositeMarketSource` — symbol-pattern router with multi-vendor live fan-in.
- `com.qkt.marketdata.source.SymbolPattern` — `prefix(...)`, `exact(...)` factories.
- `com.qkt.marketdata.source.SequenceTickFeed` — adapter from `Sequence<Tick>` to `TickFeed`.
- `com.qkt.marketdata.source.NullMarketSource` — inert source for `Backtest` paths that don't need a real one.
- `com.qkt.marketdata.source.UnsupportedDataException` — moved from `marketdata.history`.
- `com.qkt.marketdata.source.Reductions` — extension functions for sequences of candles, moved from `marketdata.history`.

### Live runtime

- `com.qkt.app.LiveSession` — runtime entry point alongside `Backtest`. Single dedicated engine thread; bounded queue tick ingest.
- `com.qkt.app.LiveSessionHandle` — control surface (`stop`, `awaitTermination`, `running`, `droppedTicks`, `recentTrades`).
- `com.qkt.app.IndicatorWarmer` — bar-driven warmup driver. Pushes synthetic ticks at `bar.endTime - 1` through `pipeline.ingestForWarmup`.
- `com.qkt.marketdata.live.LiveTickSource` — vendor-internal push-style producer interface.
- `com.qkt.marketdata.live.LiveTickFeed` — bounded-queue adapter from `LiveTickSource` (push) to `TickFeed` (pull). Drop-oldest overflow.
- `com.qkt.app.TradingPipeline.ingestForWarmup(tick)` — second ingress that bypasses strategies and risk; only updates `MarketPriceTracker` and `CandleAggregator`.

### Calendar + session anchors

- `com.qkt.common.TradingCalendar` — interface plus `fxDefault()`, `nyse()`, `crypto()` factories.
- `com.qkt.common.SessionAnchor` — sealed class: `PreviousDay`, `CurrentSession`, `PreviousSession`, `Rolling(duration)`.
- `com.qkt.common.TimeMark` — sealed class: `Now`, `Absolute(instant)`, `AtSessionAnchor(anchor, timeOfDay?)`, `RelativeToNow(offset)`.
- `com.qkt.common.TimeRange.of(from, to, clock, calendar)` — composable range builder.
- `com.qkt.common.RefreshTrigger` — sealed class: `Once`, `EveryNTicks(n)`, `OnAnchorRollover(anchor, calendar)`, `OnSessionRollover`, `OnTimeOfDay(time)`.

### Range-aggregate indicators

- `com.qkt.indicators.range.RangeAggregateIndicator<T>` — base machinery for any indicator that reduces over a `TimeRange` of bars.
- `com.qkt.indicators.range.SessionAnchoredIndicator<T>` — sugar over the above.
- `com.qkt.indicators.range.PreviousDayHigh` — concrete indicator.
- `com.qkt.indicators.range.PreviousDayLow` — concrete indicator.
- `com.qkt.indicators.range.SessionHigh` — concrete indicator (anchor-parameterized).
- `com.qkt.indicators.range.SessionLow` — concrete indicator (anchor-parameterized).

### Strategy mode-awareness

- `com.qkt.strategy.Mode` — enum: `BACKTEST`, `LIVE`.
- `com.qkt.strategy.SessionContext` — bundle: `mode`, `clock`, `calendar`, `source`.
- `com.qkt.strategy.Strategy.onTickWithContext(tick, ctx, emit)` — opt-in default method bridging to `onTick`.
- `com.qkt.strategy.WarmupSpec` — sealed class: `None`, `Bars(window, count)`, `Duration(window, duration)`, `Ticks(duration)`.
- `com.qkt.strategy.Warmable` — interface: `val warmup: WarmupSpec`.

### TradingView vendor (Plan 7c)

- `com.qkt.marketdata.live.tv.TradingViewMarketSource` — public `MarketSource` impl (`LIVE_TICKS` + `BARS`).
- `com.qkt.marketdata.live.tv.TradingViewWebSocket` — low-level WS client (OkHttp).
- `com.qkt.marketdata.live.tv.TradingViewWebSocketLike` — interface for testability.
- `com.qkt.marketdata.live.tv.TradingViewListener` — listener interface.
- `com.qkt.marketdata.live.tv.TradingViewFrame` — typed wrapper (`Message`, `Heartbeat`).
- `com.qkt.marketdata.live.tv.TradingViewFraming` — `~m~LEN~m~PAYLOAD` encode/decode.
- `com.qkt.marketdata.live.tv.TradingViewQuoteSession` — high-level live-tick subscription.
- `com.qkt.marketdata.live.tv.TradingViewChartSession` — high-level historical bars query.
- `com.qkt.marketdata.live.tv.TradingViewResolution` — `TimeWindow` → TV resolution string mapper.

### Sample strategies

- `com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy` — uses `PreviousDayHigh` + `SessionContext`.
- `com.qkt.strategy.samples.RollingHighBreakoutStrategy` — uses raw `RangeAggregateIndicator` + `RefreshTrigger.OnSessionRollover`.

### Application

- `com.qkt.app.LiveDemo.main` — entry point at `./gradlew runLiveDemo`.

### Backtest enhancements

- `Backtest.fromSource(source, request, candleWindow?, warmupSpec?)` — factory consuming `MarketSource`. New ergonomic entry point.
- `Backtest.fromStore(store, request, candleWindow?)` — kept for compatibility, internally rewritten over `LocalMarketSource`.
- `Backtest` accepts `warmupSpec: WarmupSpec = WarmupSpec.None` and `calendar: TradingCalendar = TradingCalendar.crypto()`.

---

## Migration from previous phase

| Phase 6 name | Phase 7 name | Notes |
|---|---|---|
| `com.qkt.marketdata.history.HistoricalDataProvider` | (deleted) | Absorbed into `MarketSource`. |
| `com.qkt.marketdata.history.StoreHistoricalDataProvider` | `com.qkt.marketdata.source.LocalMarketSource` | Same backend, now behind the umbrella. |
| `com.qkt.marketdata.store.DataRequest` | `com.qkt.marketdata.source.MarketRequest` | Renamed + moved. |
| `com.qkt.marketdata.history.UnsupportedDataException` | `com.qkt.marketdata.source.UnsupportedDataException` | Moved. |
| `com.qkt.marketdata.history.Reductions` | `com.qkt.marketdata.source.Reductions` | Moved (extension functions). |
| `DataCapability` | `MarketSourceCapability` | Renamed. |
| `package com.qkt.marketdata.history` | (deleted) | Replaced by `marketdata.source` + `marketdata.live`. |

Existing call sites need import rewrites only; behavior is identical for the historical-only path.

---

## Usage cookbook

Worked examples covering the full surface area of Phase 7. Each is a runnable Kotlin snippet (assuming a JVM project with qkt on the classpath).

### 1. Bar history from a vendor (one-shot query)

```kotlin
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import java.time.Instant

fun main() {
    val source = TradingViewMarketSource.connect()
    try {
        val range =
            TimeRange(
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-15T01:00:00Z"),
            )
        val bars = source.bars("OANDA:EURUSD", TimeWindow.FIVE_MINUTES, range).toList()
        bars.forEach { c -> println("${c.startTime} O=${c.open} H=${c.high} L=${c.low} C=${c.close}") }
    } finally {
        source.close()
    }
}
```

### 2. Live tick consumption (no strategy, just observe)

```kotlin
import com.qkt.marketdata.live.tv.TradingViewMarketSource

fun main() {
    val source = TradingViewMarketSource.connect()
    val feed = source.liveTicks(listOf("OANDA:EURUSD"))
    try {
        repeat(50) {
            val tick = feed.next() ?: return
            println("${tick.timestamp} ${tick.symbol} @ ${tick.price}")
        }
    } finally {
        feed.close()
        source.close()
    }
}
```

### 3. Backtest with `MarketRequest` (no warmup)

```kotlin
import com.qkt.app.Backtest
import com.qkt.common.SystemClock
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.EveryNthTickBuyStrategy
import java.nio.file.Path
import java.time.Instant

fun main() {
    val store = DefaultDataStore(root = Path.of("data/sample"))
    val source = LocalMarketSource(store, SystemClock())
    val request =
        MarketRequest(
            symbols = listOf("EURUSD"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
    val result =
        Backtest.fromSource(
            strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 10)),
            source = source,
            request = request,
        ).run()
    println("Trades: ${result.tradeCount}, PnL: ${result.totalPnL}")
}
```

### 4. Backtest with bar warmup

```kotlin
import com.qkt.app.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import java.nio.file.Path
import java.time.Instant

fun main() {
    val store = DefaultDataStore(root = Path.of("data/sample"))
    val source = LocalMarketSource(store, SystemClock())
    val request =
        MarketRequest(
            symbols = listOf("EURUSD"),
            from = Instant.parse("2024-01-16T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
    Backtest
        .fromSource(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("EURUSD")),
            source = source,
            request = request,
            warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1440),
        ).run()
        .let { println("Trades: ${it.tradeCount}, PnL: ${it.totalPnL}") }
}
```

### 5. LiveSession against TradingView

```kotlin
import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import java.time.Duration

fun main() {
    val source = TradingViewMarketSource.connect()
    val handle =
        LiveSession(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("OANDA:EURUSD")),
            rules = listOf(MaxPositionSize("OANDA:EURUSD", maxQty = Money.of("3"))),
            source = source,
            symbols = listOf("OANDA:EURUSD"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
        ).start()
    handle.awaitTermination(Duration.ofMinutes(10))
    source.close()
}
```

### 6. Multi-vendor routing via `CompositeMarketSource`

```kotlin
import com.qkt.app.LiveSession
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.strategy.Strategy
import java.time.Duration

fun main() {
    val tv = TradingViewMarketSource.connect()
    // Pretend BinanceMarketSource exists. Phase 7 ships only TV; the routing pattern is forward-compatible.
    // val binance = BinanceMarketSource.connect()
    val composite =
        CompositeMarketSource(
            routes =
                listOf(
                    SymbolPattern.prefix("BINANCE:") to tv, // would be `binance` once that vendor lands
                    SymbolPattern.prefix("OANDA:") to tv,
                ),
            fallback = tv,
        )
    val strategies: List<Strategy> = emptyList()
    val handle =
        LiveSession(
            strategies = strategies,
            source = composite,
            symbols = listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"),
            clock = SystemClock(),
            calendar = TradingCalendar.crypto(),
        ).start()
    handle.awaitTermination(Duration.ofSeconds(10))
}
```

### 7. Session-anchored indicator standalone

```kotlin
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.indicators.range.PreviousDayHigh
import com.qkt.marketdata.live.tv.TradingViewMarketSource

fun main() {
    val source = TradingViewMarketSource.connect()
    val pdh = PreviousDayHigh(symbol = "OANDA:EURUSD", calendar = TradingCalendar.fxDefault(), source = source, clock = SystemClock())
    val feed = source.liveTicks(listOf("OANDA:EURUSD"))
    repeat(20) {
        val tick = feed.next() ?: return@repeat
        pdh.update(tick)
        println("now=${tick.price} prevDayHigh=${pdh.value()}")
    }
    feed.close()
    source.close()
}
```

### 8. Custom range-aggregate indicator (rolling 6-hour high)

```kotlin
import com.qkt.candles.TimeWindow
import com.qkt.common.RefreshTrigger
import com.qkt.common.SystemClock
import com.qkt.common.TimeMark
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.indicators.range.RangeAggregateIndicator
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import java.math.BigDecimal
import java.time.Duration

fun main() {
    val source = TradingViewMarketSource.connect()
    val clock = SystemClock()
    val calendar = TradingCalendar.fxDefault()
    val rolling6hHigh =
        RangeAggregateIndicator<BigDecimal>(
            symbol = "OANDA:EURUSD",
            window = TimeWindow.ONE_MINUTE,
            rangeSpec = {
                TimeRange.of(
                    from = TimeMark.RelativeToNow(Duration.ofHours(-6)),
                    to = TimeMark.Now,
                    clock = clock,
                    calendar = calendar,
                )
            },
            reduce = { it.maxOfOrNull { c -> c.high } },
            source = source,
            clock = clock,
            refreshOn = RefreshTrigger.EveryNTicks(100),
        )
    val feed = source.liveTicks(listOf("OANDA:EURUSD"))
    repeat(200) {
        val tick = feed.next() ?: return@repeat
        rolling6hHigh.update(tick)
        println("rolling6hHigh=${rolling6hHigh.value()}")
    }
    feed.close()
    source.close()
}
```

### 9. Time-of-day strategy gating using `TradingCalendar.nyse()`

```kotlin
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy

class NyseOnlyStrategy(private val symbol: String) : Strategy {
    override fun onTick(tick: Tick, emit: (Signal) -> Unit) {}

    override fun onTickWithContext(tick: Tick, ctx: SessionContext, emit: (Signal) -> Unit) {
        if (tick.symbol != symbol) return
        val nyse = TradingCalendar.nyse()
        val now = java.time.Instant.ofEpochMilli(ctx.clock.now())
        if (!nyse.isInSession(symbol, now)) return
        // ... your logic here
    }
}
```

### 10. Custom `WarmupSpec` (multi-strategy aggregation)

```kotlin
import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import com.qkt.strategy.samples.RollingHighBreakoutStrategy

fun main() {
    val source = TradingViewMarketSource.connect()
    val handle =
        LiveSession(
            strategies =
                listOf(
                    BreakoutOfYesterdayHighStrategy("OANDA:EURUSD"),
                    RollingHighBreakoutStrategy("OANDA:EURUSD"),
                ),
            source = source,
            symbols = listOf("OANDA:EURUSD"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
            // override the auto-derived widest spec with a hand-picked one
            warmupOverride = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 2880), // 48h of M1 bars
        ).start()
    handle.awaitTermination(java.time.Duration.ofMinutes(5))
    source.close()
}
```

### 11. Multi-vendor data composition (TV bars for warmup, TV live for steady state)

```kotlin
import com.qkt.app.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import java.time.Instant

fun main() {
    // TV's bar history doubles as the warmup source for a backtest.
    val tv = TradingViewMarketSource.connect(clock = SystemClock())
    val request =
        MarketRequest(
            symbols = listOf("OANDA:EURUSD"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-15T01:00:00Z"),
        )
    // Note: Backtest needs TICKS capability. TV does not advertise TICKS, so this combination falls back
    // to a LocalMarketSource for ticks and TV for warmup bars; in practice you wire that via CompositeMarketSource.
    // Shown here only to illustrate the TV.bars() warmup path.
    Backtest
        .fromSource(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("OANDA:EURUSD")),
            source = tv, // throws if TICKS missing — see "Known limitations" below
            request = request,
            warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 200),
        ).run()
}
```

In practice, mix TV (bars) with `LocalMarketSource` (ticks) via `CompositeMarketSource` when you need a pure backtest.

---

## Testing patterns

### Canonical fakes

- `InMemoryMarketSource` (test-side) — seed `liveTicks` and `bars` with pre-computed sequences. Used in 7b's runtime tests and 7c's strategy tests.
- `FakeTradingViewWebSocket` (test-side) — implements `TradingViewWebSocketLike`. Records every `send(...)` and exposes `replay(frames)` plus `simulateConnect()` / `simulateDisconnect(reason)`. Used in all `TradingView*SessionTest` cases.

### Recorded WS fixtures

Located at `src/test/resources/tv-fixtures/`. Each `.jsonl` file is a sequence of TV WS payloads (no framing wrapper — one JSON object or `~h~N~h~` heartbeat per line). Tests load, parse via `TradingViewFrame.parse`, replay through a `FakeTradingViewWebSocket`.

### Look-ahead-bias guard

Tests asserting that an indicator or `MarketSource` cannot read beyond `clock.now()` use the canonical message:

```kotlin
assertThatThrownBy { source.ticks("X", futureRange).toList() }
    .hasMessageContaining("look-ahead bias")
```

The same wording is used by `LocalMarketSource`, `RangeAggregateIndicator`, and `TimeRange.of(...)`.

### Determinism assertion

```kotlin
val a = Backtest.fromSource(strategies, source, request, warmupSpec = WarmupSpec.Bars(...)).run()
val b = Backtest.fromSource(strategies, source, request, warmupSpec = WarmupSpec.Bars(...)).run()
assertThat(a.tradeCount).isEqualTo(b.tradeCount)
assertThat(a.totalPnL).isEqualByComparingTo(b.totalPnL)
```

Phase 7b's `BacktestWarmupTest.\`Bars warmup backtest is deterministic across two runs\`` is the reference pattern.

### Multi-vendor fan-in

`CompositeMarketSource` returns a `FanInTickFeed` when `liveTicks(symbols)` spans multiple sources. The fan-in is round-robin per `next()` call. Test harness:

```kotlin
val composite = CompositeMarketSource(routes = listOf(...), fallback = ...)
LiveSession(strategies, source = composite, symbols = ...).start().awaitTermination(...)
assertThat(strategy.seen.map { it.symbol }).containsExactlyInAnyOrder("OANDA:EURUSD", "BINANCE:BTCUSDT")
```

### `@Tag("e2e")` for live smoke tests

Excluded from default `./gradlew test`. Run on demand with `./gradlew test -PincludeTags=e2e`. Used by `TradingViewLiveSmokeTest` to validate the production WS path against real network.

---

## Known limitations

- **No real-broker integration.** All fills go through `MockBroker`, which fills at the latest in-process price. The `LiveBroker` interface and concrete `AlpacaBroker`, `IBKRBroker` implementations are deferred to Phase 7d / Phase 8.
- **TradingView authenticated mode not implemented.** Anonymous mode only. Premium symbol coverage and elevated rate limits will land when authentication is added; out of scope for Phase 7.
- **TradingView tick history not exposed.** TV's protocol does not provide it; `TradingViewMarketSource.ticks(...)` throws `UnsupportedDataException`. Use bar history for warmup.
- **NyseCalendar half-days not modeled.** Early-close days (day before Independence Day, day after Thanksgiving, Christmas Eve) are treated as full sessions. Documented in the Phase 7a calendar discussion; will be addressed in a future phase or backlog item.
- **No DST handling for the FX calendar.** `FxCalendar` uses a fixed 22:00 UTC cutoff for the weekly Sunday-open / Friday-close. The real FX market's cutoff shifts by an hour twice a year with US daylight saving. Acceptable for Phase 7; revisit when we add real-broker integrations whose timestamps are DST-aware.
- **No persistence of indicator state across restarts.** `LiveSession` rebuilds all indicator state from warmup at startup. Crash recovery via state checkpointing is deferred.
- **Multi-region / multi-instance live sessions not supported.** `LiveSession` is single-process, single-thread.
- **`CompositeMarketSource` live fan-in is round-robin, not strict timestamp arrival order.** Acceptable for Phase 7's spec invariant ("arrival order across vendors, not timestamp order"); revisit if bursty vendors cause starvation.
- **No additional vendors beyond TradingView.** Binance, OANDA, IBKR, etc. are out of scope. The `CompositeMarketSource` interface is the extensibility point; new vendors implement `MarketSource` and slot in.
- **DSL not shipped.** Phase 8. The `SessionContext` design is forward-compatible.

---

## References

- Spec: [`docs/superpowers/specs/2026-05-05-trading-engine-phase7-design.md`](../superpowers/specs/2026-05-05-trading-engine-phase7-design.md)
- Plan 7a (refactor + abstractions): [`docs/superpowers/plans/2026-05-05-trading-engine-phase7a.md`](../superpowers/plans/2026-05-05-trading-engine-phase7a.md)
- Plan 7b (live runtime + warmup): [`docs/superpowers/plans/2026-05-05-trading-engine-phase7b.md`](../superpowers/plans/2026-05-05-trading-engine-phase7b.md)
- Plan 7c (TradingView + samples + demo): [`docs/superpowers/plans/2026-05-05-trading-engine-phase7c.md`](../superpowers/plans/2026-05-05-trading-engine-phase7c.md)
- Phase 6 baseline (historical data layer): [`docs/superpowers/specs/2026-05-04-trading-engine-phase6-design.md`](../superpowers/specs/2026-05-04-trading-engine-phase6-design.md)
- Merge SHAs (placeholder — fill in at merge time):
  - 7a merge: `__SHA_7A__`
  - 7b merge: `__SHA_7B__`
  - 7c merge: `__SHA_7C__`
- Prior art for the TradingView WS protocol: `Mathieu2301/TradingView-API` on GitHub (Node.js, ~3k stars; consulted for protocol details, no code copied).
```

- [ ] **Step 3: Verify the file renders cleanly**

Run: `wc -l docs/phases/phase-7-live-runtime.md`
Expected: between 200 and 600 lines (target was 200–500; some flex above acceptable for a phase this large).

- [ ] **Step 4: Commit**

```bash
git add docs/phases/phase-7-live-runtime.md
git commit -m "docs: phase 7 changelog with usage cookbook"
```

---

## Task 15: Final verification

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. ~384 tests pass (excluding e2e). The smoke test class is compiled but its `@Tag("e2e")` test is excluded.

- [ ] **Step 2: Verify the existing demo still works**

Run: `./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"`
Expected: 10 (3 FILLED + 7 REJECTED — same as Phase 6, unchanged).

- [ ] **Step 3: Verify the live demo task is registered**

Run: `./gradlew tasks --group application | grep runLiveDemo`
Expected: `runLiveDemo - Run the live TradingView demo`.

- [ ] **Step 4: Run the live demo for ~30 seconds (manual)**

```bash
timeout 30 ./gradlew runLiveDemo --console=plain || true
```

Expected: log lines indicating connection to TV, ticks arriving for `OANDA:EURUSD`, possibly some `FILLED`/`REJECTED` lines depending on whether the session-anchored indicator triggers. Skip if you don't have network access; the structural placement of `LiveDemo` is what matters for plan completion.

- [ ] **Step 5: Run the e2e smoke test (manual)**

```bash
./gradlew test -PincludeTags=e2e --tests "com.qkt.marketdata.live.tv.TradingViewLiveSmokeTest"
```

Expected: 1 test PASS. Confirms real TV connectivity. Skip if network access is unavailable for this run.

- [ ] **Step 6: Verify acceptance criteria for Phase 7c**

Cross-check against Phase 7 spec §11 acceptance criteria. Phase 7c's contributions are bolded.

- [x] (7a) `MarketSource` interface exists; `MarketSourceCapability` enum exists; `MarketRequest` type exists.
- [x] (7a) `LocalMarketSource` implements `MarketSource` with `BARS` + `TICKS`.
- [ ] **(7c) `TradingViewMarketSource` implements `MarketSource` with `LIVE_TICKS` + `BARS` capabilities. Connects to TV WS, subscribes to a multi-symbol set, parses `qsd` frames into `Tick`s, handles heartbeats, reconnects with backoff.**
- [x] (7a) `CompositeMarketSource` exists and routes per-symbol via `Map<SymbolPattern, MarketSource>` + fallback.
- [x] (7b) `LiveSession` runtime starts on a background engine thread, wires `MarketSource.liveTicks(...)` as `TickFeed`, drives the existing `TradingPipeline`, exposes `LiveSessionHandle` with stop/status/observable streams.
- [x] (7b) `LiveTickFeed` adapts WS push to `TickFeed.next()` pull via bounded queue with drop-oldest overflow; drop count observable.
- [x] (7b) `IndicatorWarmer` + `Warmable` mixin + `WarmupSpec` ship; warmup runs `pipeline.ingestForWarmup(...)`.
- [x] (7a) `TradingCalendar` interface ships with `fxDefault()`, `nyse()`, `crypto()`, `custom(spec)` factories.
- [x] (7a) `SessionAnchor` sealed type ships.
- [x] (7a) `TimeMark` sealed type ships. `TimeRange.of(from, to, clock, calendar)` resolves marks.
- [x] (7a) `RangeAggregateIndicator<T>` base class ships with configurable `RefreshTrigger`.
- [x] (7a) `SessionAnchoredIndicator<T>` ships; `PreviousDayHigh`, `PreviousDayLow`, `SessionHigh`, `SessionLow` ship.
- [ ] **(7c) Per-source supported-window declarations: `TradingViewMarketSource` exposes its supported `TimeWindow` set; querying with an unsupported window throws `UnsupportedDataException`.**
- [x] (7a) `SessionContext` + `Mode` enum ship; `Strategy.onTickWithContext` default-implementation bridges to `onTick`; existing strategies compile unchanged.
- [x] (7a) Phase 6 names refactored.
- [x] (7a/7b) `Backtest.fromStore` retains compatibility; `Backtest.fromSource(source, request, ...)` is the new entry point. `Backtest` gains `warmupSpec` and `calendar`.
- [ ] **(7c) `BreakoutOfYesterdayHighStrategy` sample exists. `RollingHighBreakoutStrategy` sample exists.**
- [x] (7b) Multi-vendor fan-in: `LiveSession` accepts a `CompositeMarketSource` and demonstrably routes per-symbol live subscriptions to the right vendor.
- [x] All existing tests continue to pass.
- [ ] **(7c) New tests cover: `TradingViewMarketSource` against a recorded WS fixture (offline test); offline `TradingViewQuoteSession` and `TradingViewChartSession` replay tests; sample-strategy backtest tests; `TradingViewLiveSmokeTest` smoke test (manual).**
- [ ] **(7c) Live demo: a minimal main runs `LiveSession` against TV with EURUSD + XAUUSD + BTCUSD, receives ticks, simulates fills via `MockBroker`, prints trades to stdout. Documented in README.**
- [x] **(7c) No new runtime dependencies beyond OkHttp (planned) and the existing `kotlinx-serialization-json`.**
- [x] (Phase 6 invariant) `./gradlew run` (the existing demo) still produces identical output.
- [ ] **(7c) Phase 7 changelog at `docs/phases/phase-7-live-runtime.md` (per SKILL.md §6).**

- [ ] **Step 7: No commit (verification only)**

When all boxes checked, Plan 7c is done and Phase 7 ships in full. The next phases (7d real broker integrations, Phase 8 DSL) build on this foundation.

---

## Spec ambiguities encountered

1. **`TradingViewWebSocket` testability.** The spec does not call out an interface seam between the real WS client and the high-level sessions. Leaving them coupled would force tests through the real OkHttp path. We introduced `TradingViewWebSocketLike` as a thin interface that both the real client and the `FakeTradingViewWebSocket` test harness implement. Public consumers of `TradingViewMarketSource` never see the interface; they construct via `TradingViewMarketSource.connect()` which returns a configured source over a real WS.

2. **TV symbol regex.** The spec says "callers are responsible for using TV's symbol form" but doesn't pin the regex. We chose `^[A-Z0-9]+:[A-Z0-9_]+$` (uppercase exchange, colon, uppercase symbol with underscores allowed). This rejects `EURUSD` (no colon) and `oanda:eurusd` (lowercase) but accepts `BINANCE:BTCUSDT`, `OANDA:EURUSD`, `NASDAQ:AAPL`, `OANDA:XAU_USD`.

3. **`bars` count rounding.** TV's `create_series` takes a fixed bar count, not a date range. We round up: `count = ceil((to - from) / window.durationMs)`. After receiving the bars we filter client-side to `[from, to)`. There can be at most one extra bar requested over what the user asked for; the filter handles that.

4. **`bars` `toTimestampSeconds`.** TV expects a target right-edge timestamp in seconds. We use `range.to.epochSecond` directly. TV may return bars whose `time` is slightly past `to_timestamp` if the bar boundary doesn't align; the client-side filter clips them.

5. **Reconnect backoff.** Spec says "1s, 2s, 4s, 8s, capped at 60s." Our `TradingViewWebSocket` does not yet wire an explicit reconnect supervisor — OkHttp's `WebSocket` callback fires `onFailure` / `onClosed` on the OkHttp thread, and we propagate via listener callbacks. The high-level `TradingViewQuoteSession` re-issues subscribe commands on `onConnected()`, but the connection itself is not auto-retried in 7c. **Resolution:** reconnect supervisor is a follow-up TODO; for the demo, the user manually restarts the JVM on connection drop. We track this in the changelog's "Known limitations" section as a post-7c improvement. Adding a reconnect supervisor here would balloon the plan; the explicit decision is to keep 7c focused on the protocol slice and ship reconnect in the same phase as authenticated mode.

6. **Heartbeat echo.** Spec says "echo `~h~N~h~` back." Our `TradingViewWebSocket.handleHeartbeat(seq)` sends back the framed heartbeat directly. Listeners still see the heartbeat frame for observability; ack happens before listener dispatch.

7. **`TradingViewChartSession.getBars` synchronicity.** Spec says "synchronous, blocks until response received or timeout." We use a `CountDownLatch(1)` in the listener and wait `timeoutMs`. `IOException` is thrown on both timeout and disconnection.

8. **`BreakoutOfYesterdayHighStrategy` warmup.** Spec doesn't pin a count. We use 1440 M1 bars (24 hours) — enough to fully populate the previous-day window for any reasonable session-rollover time. This is conservative; if it proves wasteful we'll tune in a follow-up.

9. **`runLiveDemo` Gradle task.** Spec says "live demo entry: `LiveDemo.kt` with a `main` function." We need a way to run it; the existing `application` plugin pins `mainClass` to `MainKt`. Cleanest non-disruptive solution is a registered `JavaExec` task, which is what we did.

10. **Phase changelog target line count.** SKILL.md §6 says 200–500 lines; this phase ships a large surface area (essentially three plans worth) and the cookbook section alone needs ~10 worked examples. The changelog comes in around 450–500 lines. If line count exceeds 500 by a non-trivial margin, the user can split the cookbook out into a separate `docs/phases/phase-7-cookbook.md` and link from the changelog. Out of scope for plan execution; flag at merge review.
