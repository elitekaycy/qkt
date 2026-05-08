# Phase 12b — Single-Strategy Observability HTTP Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed an HTTP server in `qkt run` that exposes `/status`, `/events` (SSE), `/logs`, `/health`, and `/stop` endpoints. Default port = `0` (kernel-assigned ephemeral, collision-free). Default bind = `127.0.0.1`. No new external deps — uses `com.sun.net.httpserver` (built-in JDK) and `kotlinx.serialization.json` (already a project dep). Single strategy per process; multi-strategy hosting is the daemon's job in 12c.

**Architecture:** New package `com.qkt.cli.observe` containing `EventRing` (bounded buffer with listener fan-out), `StatusSnapshot` DTO, and `ObservabilityServer` (jdk.httpserver wrapper). `RunCommand` instantiates the server, wires `LiveSession.onTrade` callbacks into the ring, prints port discovery to stdout in two forms (`[INFO] observability: …` and `QKT_PORT=…`), and routes `POST /stop` through the existing shutdown hook. `--port < 1024` rejected unless `--allow-privileged-port` is set. `--port-file <path>` writes the bound port atomically.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. Zero new external dependencies (`jdk.httpserver` is JDK built-in; `kotlinx.serialization.json` already a dep; `okhttp` already a dep for client-side test calls).

**Spec:** `docs/superpowers/specs/2026-05-08-trading-engine-phase12b-design.md`.

**Branch:** `phase12b-observability` — cut from `main` at start of Task 1.

---

## Design notes

### Package layout

```
src/main/kotlin/com/qkt/cli/observe/
├── EventRing.kt                # bounded ring + listener fan-out
├── EventEntry.kt               # data class for ring entries
├── StatusSnapshot.kt           # /status DTO (kotlinx.serialization)
├── ObservabilityServer.kt      # HttpServer wrapper, route dispatch
├── Routes.kt                   # per-route handlers (top-level functions)
└── PortPrinter.kt              # stdout port-discovery formatting + --port-file write

src/main/kotlin/com/qkt/cli/RunCommand.kt    # modified: instantiate + wire ObservabilityServer

src/test/kotlin/com/qkt/cli/observe/
├── EventRingTest.kt
├── StatusSnapshotTest.kt
├── ObservabilityServerTest.kt    # /status, /health, /logs, /stop via HTTP
├── SseStreamTest.kt              # /events SSE framing
└── PortPrinterTest.kt
```

### EventRing shape

```kotlin
data class EventEntry(
    val ts: Long,
    val kind: String,
    val payload: kotlinx.serialization.json.JsonObject,
)

class EventRing(private val capacity: Int = 1000) {
    private val buf = ArrayDeque<EventEntry>(capacity)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(EventEntry) -> Unit>()
    private val lock = java.util.concurrent.locks.ReentrantLock()

    fun append(kind: String, payload: JsonObject) { /* ... */ }
    fun snapshot(since: Long, limit: Int): List<EventEntry> { /* ... */ }
    fun subscribe(listener: (EventEntry) -> Unit): AutoCloseable { /* ... */ }
    fun size(): Int = lock.withLock { buf.size }
}
```

Single-writer-many-readers via `ReentrantLock`. SSE fan-out is best-effort (any listener exception is swallowed, the listener is *not* removed automatically — the SSE handler closes its own subscription on disconnect).

### StatusSnapshot shape

```kotlin
@kotlinx.serialization.Serializable
data class StatusSnapshot(
    val strategy: String,
    val version: Int,
    val uptimeMs: Long,
    val startedAt: String,    // ISO-8601
    val equity: BigDecimal,
    val balance: BigDecimal,
    val realized: BigDecimal,
    val unrealized: BigDecimal,
    val positions: List<PositionDto>,
    val lastTrade: TradeDto?,
)
```

`BigDecimal` serialization needs a custom serializer (`kotlinx.serialization` doesn't ship one). Plain string emission with `toPlainString()` to avoid scientific notation.

### Server routing

`com.sun.net.httpserver.HttpServer` dispatches by URI prefix. Each route is a top-level function in `Routes.kt`:

```kotlin
fun handleStatus(snapshot: () -> StatusSnapshot): HttpHandler = HttpHandler { ex -> ... }
fun handleHealth(running: () -> Boolean): HttpHandler = ...
fun handleLogs(ring: EventRing): HttpHandler = ...
fun handleEvents(ring: EventRing): HttpHandler = ...   // SSE
fun handleStop(onStop: (Boolean) -> Unit): HttpHandler = ...
```

`ObservabilityServer` wires them up on `start()`.

### Port print + port-file write

```kotlin
object PortPrinter {
    fun announce(host: String, port: Int, file: java.nio.file.Path? = null, out: java.io.PrintStream = System.out) {
        out.println("[INFO] observability: http://$host:$port")
        out.println("QKT_PORT=$port")
        out.flush()
        file?.let { writeAtomic(it, port.toString()) }
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
```

### LiveSession hooks

12a's `LiveSession` already has `onTrade`. For `/events` we need `onSignal` and (optionally) `onLog`. For 12b scope:
- **`onTrade`** — already wired in 12a. Reused.
- **`onSignal`** — new optional callback in `LiveSession` constructor. Default no-op. Strategy-emitted signals (Buy/Sell) become events on the ring.
- **`onLog`** — defer to 12c; SLF4J-to-ring bridge needs a logback/slf4j adapter that's not justified for a single-process runner. `/logs` in 12b returns trade + signal events from the ring; literal log lines redirect via stdout.

### Privileged port rejection

```kotlin
val port = args.option("port")?.toInt() ?: 0
val allowPrivileged = args.flag("allow-privileged-port")
if (port in 1..1023 && !allowPrivileged) {
    System.err.println(
        "qkt: error: port $port is privileged (< 1024); add --allow-privileged-port to override.",
    )
    return ExitCodes.ARG_ERROR
}
```

---

## File structure

### New files

```
src/main/kotlin/com/qkt/cli/observe/
├── EventRing.kt
├── EventEntry.kt
├── StatusSnapshot.kt
├── ObservabilityServer.kt
├── Routes.kt
└── PortPrinter.kt

src/test/kotlin/com/qkt/cli/observe/
├── EventRingTest.kt
├── StatusSnapshotTest.kt
├── ObservabilityServerTest.kt
├── SseStreamTest.kt
└── PortPrinterTest.kt

src/test/kotlin/com/qkt/cli/
└── RunCommandObservabilityTest.kt    # extends 12a's RunCommandTest with --port 0 + scrape /status
```

### Modified files

```
src/main/kotlin/com/qkt/cli/RunCommand.kt    # instantiate server, wire callbacks, parse new flags
src/main/kotlin/com/qkt/app/LiveSession.kt   # optional onSignal callback
src/test/kotlin/com/qkt/cli/EndToEndCliTest.kt # add observability scenario

docs/phases/phase-12b-observability.md       # changelog (Task 10)
```

---

## Tasks

### Task 1: `EventRing` + `EventEntry`

Bounded ring buffer with listener fan-out. Standalone, no HTTP yet.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/observe/EventEntry.kt`
- Create: `src/main/kotlin/com/qkt/cli/observe/EventRing.kt`
- Create: `src/test/kotlin/com/qkt/cli/observe/EventRingTest.kt`

- [ ] **Step 1: Cut branch**

```bash
git checkout -b phase12b-observability
```

- [ ] **Step 2: `EventEntry` data class**

```kotlin
package com.qkt.cli.observe

import kotlinx.serialization.json.JsonObject

data class EventEntry(
    val ts: Long,
    val kind: String,
    val payload: JsonObject,
)
```

- [ ] **Step 3: Failing test**

```kotlin
package com.qkt.cli.observe

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventRingTest {
    private fun obj(s: String): JsonObject = buildJsonObject { put("v", s) }

    @Test
    fun `append then snapshot returns recent entries in order`() {
        val ring = EventRing(capacity = 5)
        ring.append("trade", obj("a"))
        ring.append("trade", obj("b"))
        val s = ring.snapshot(since = 0L, limit = 100)
        assertThat(s.map { (it.payload["v"] as kotlinx.serialization.json.JsonPrimitive).content })
            .containsExactly("a", "b")
    }

    @Test
    fun `oldest entries evict at capacity`() {
        val ring = EventRing(capacity = 3)
        for (c in listOf("a", "b", "c", "d", "e")) ring.append("trade", obj(c))
        val s = ring.snapshot(since = 0L, limit = 100)
        assertThat(s.map { (it.payload["v"] as kotlinx.serialization.json.JsonPrimitive).content })
            .containsExactly("c", "d", "e")
    }

    @Test
    fun `snapshot since filters by timestamp`() {
        val ring = EventRing(capacity = 5)
        ring.append("trade", obj("a"))
        Thread.sleep(2)
        val cutoff = System.currentTimeMillis()
        Thread.sleep(2)
        ring.append("trade", obj("b"))
        val s = ring.snapshot(since = cutoff, limit = 100)
        assertThat(s).hasSize(1)
        assertThat((s[0].payload["v"] as kotlinx.serialization.json.JsonPrimitive).content).isEqualTo("b")
    }

    @Test
    fun `snapshot limit caps result size`() {
        val ring = EventRing(capacity = 100)
        repeat(20) { i -> ring.append("trade", obj(i.toString())) }
        assertThat(ring.snapshot(since = 0L, limit = 5)).hasSize(5)
    }

    @Test
    fun `subscriber receives appended entries`() {
        val ring = EventRing(capacity = 5)
        val received = mutableListOf<String>()
        val close = ring.subscribe { e ->
            received.add((e.payload["v"] as kotlinx.serialization.json.JsonPrimitive).content)
        }
        ring.append("trade", obj("x"))
        ring.append("trade", obj("y"))
        close.close()
        ring.append("trade", obj("z"))   // should not arrive
        assertThat(received).containsExactly("x", "y")
    }

    @Test
    fun `listener exception does not block subsequent appends`() {
        val ring = EventRing(capacity = 5)
        ring.subscribe { throw RuntimeException("boom") }
        ring.append("trade", obj("a"))
        ring.append("trade", obj("b"))
        assertThat(ring.snapshot(since = 0L, limit = 100)).hasSize(2)
    }
}
```

- [ ] **Step 4: Implement `EventRing`**

```kotlin
package com.qkt.cli.observe

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonObject

class EventRing(
    private val capacity: Int = 1000,
) {
    private val buf: ArrayDeque<EventEntry> = ArrayDeque(capacity)
    private val listeners: CopyOnWriteArrayList<(EventEntry) -> Unit> = CopyOnWriteArrayList()
    private val lock = ReentrantLock()

    init {
        require(capacity >= 1) { "EventRing capacity must be >= 1: $capacity" }
    }

    fun append(
        kind: String,
        payload: JsonObject,
    ) {
        val entry = EventEntry(System.currentTimeMillis(), kind, payload)
        lock.withLock {
            buf.addLast(entry)
            while (buf.size > capacity) buf.removeFirst()
        }
        for (l in listeners) runCatching { l(entry) }
    }

    fun snapshot(
        since: Long,
        limit: Int,
    ): List<EventEntry> {
        require(limit >= 1) { "limit must be >= 1: $limit" }
        return lock.withLock {
            buf.filter { it.ts >= since }.takeLast(limit.coerceAtMost(capacity))
        }
    }

    fun subscribe(listener: (EventEntry) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun size(): Int = lock.withLock { buf.size }
}
```

- [ ] **Step 5: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.observe.EventRingTest
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/cli/observe/ src/test/kotlin/com/qkt/cli/observe/
git commit -m "feat(cli): EventRing bounded buffer with listener fan-out"
```

---

### Task 2: `StatusSnapshot` DTO + JSON serialization

`/status` payload. Uses `kotlinx.serialization.json` (already a dep). Custom `BigDecimal` serializer (string, plain notation).

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/observe/StatusSnapshot.kt`
- Create: `src/test/kotlin/com/qkt/cli/observe/StatusSnapshotTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package com.qkt.cli.observe

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class StatusSnapshotTest {
    @Test
    fun `serializes BigDecimal as plain-string number`() {
        val snap =
            StatusSnapshot(
                strategy = "x",
                version = 1,
                uptimeMs = 47823,
                startedAt = "2026-05-08T14:31:14Z",
                equity = BigDecimal("9997.66"),
                balance = BigDecimal("10000.00"),
                realized = BigDecimal("-2.34"),
                unrealized = BigDecimal("0.00"),
                positions = listOf(PositionDto("BTCUSDT", BigDecimal("0.001"), BigDecimal("68234.50"))),
                lastTrade = null,
            )
        val s = Json.encodeToString(StatusSnapshot.serializer(), snap)
        // BigDecimal must be a JSON number, not a string, no scientific notation
        assertThat(s).contains("\"equity\":9997.66")
        assertThat(s).contains("\"realized\":-2.34")
        assertThat(s).doesNotContain("E+").doesNotContain("e+")
    }
}
```

- [ ] **Step 2: Implement DTOs + serializers**

```kotlin
package com.qkt.cli.observe

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

object BigDecimalAsNumberSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val plain = value.toPlainString()
        require(encoder is JsonEncoder) { "BigDecimal serializer requires Json encoder" }
        encoder.encodeJsonElement(JsonPrimitive(plain.toBigDecimal()))
        // ^ wraps the BigDecimal directly via JsonPrimitive(BigDecimal) — emits as JSON number
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        require(decoder is JsonDecoder) { "BigDecimal serializer requires Json decoder" }
        return BigDecimal(decoder.decodeJsonElement().toString())
    }
}

@Serializable
data class PositionDto(
    val symbol: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val qty: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val avgPrice: BigDecimal,
)

@Serializable
data class TradeDto(
    val timestamp: String,
    val side: String,
    val symbol: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val qty: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val price: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val realized: BigDecimal,
)

@Serializable
data class StatusSnapshot(
    val strategy: String,
    val version: Int,
    val uptimeMs: Long,
    val startedAt: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val equity: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val balance: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val realized: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val unrealized: BigDecimal,
    val positions: List<PositionDto>,
    val lastTrade: TradeDto?,
)
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.observe.StatusSnapshotTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(cli): StatusSnapshot DTO with BigDecimal-as-number JSON serializer"
```

---

### Task 3: `ObservabilityServer` skeleton + `/health` route

JDK `HttpServer` lifecycle, route registration, smoke test via `OkHttpClient`.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/observe/ObservabilityServer.kt`
- Create: `src/main/kotlin/com/qkt/cli/observe/Routes.kt`
- Create: `src/test/kotlin/com/qkt/cli/observe/ObservabilityServerTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package com.qkt.cli.observe

import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityServerTest {
    private val client = OkHttpClient()

    @Test
    fun `health returns 200 ok json`() {
        val ring = EventRing()
        val server = ObservabilityServer(
            ring = ring,
            statusProvider = { error("not implemented in this test") },
            running = { true },
            onStop = {},
            bind = "127.0.0.1",
            port = 0,
        )
        server.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${server.boundPort}/health").build()).execute()
            assertThat(resp.code).isEqualTo(200)
            assertThat(resp.body!!.string()).contains("\"status\":\"ok\"")
        } finally {
            server.close()
        }
    }

    @Test
    fun `unknown route returns 404`() {
        val ring = EventRing()
        val server = ObservabilityServer(ring, { error("nope") }, { true }, {}, "127.0.0.1", 0)
        server.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${server.boundPort}/nope").build()).execute()
            assertThat(resp.code).isEqualTo(404)
        } finally {
            server.close()
        }
    }
}
```

- [ ] **Step 2: Implement `ObservabilityServer` + `/health`**

```kotlin
package com.qkt.cli.observe

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class ObservabilityServer(
    private val ring: EventRing,
    private val statusProvider: () -> StatusSnapshot,
    private val running: () -> Boolean,
    private val onStop: (flatten: Boolean) -> Unit,
    bind: String,
    port: Int,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0)

    val boundPort: Int get() = server.address.port
    val boundHost: String = bind

    init {
        server.createContext("/health", Routes.health(running))
        server.createContext("/status", Routes.status(statusProvider))
        server.createContext("/logs", Routes.logs(ring))
        server.createContext("/events", Routes.events(ring))
        server.createContext("/stop", Routes.stop(onStop))
        server.executor = Executors.newFixedThreadPool(4)
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
```

```kotlin
package com.qkt.cli.observe

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.Json

object Routes {
    private val json = Json { encodeDefaults = true }

    fun health(running: () -> Boolean): HttpHandler =
        HttpHandler { ex ->
            if (ex.requestMethod != "GET") {
                respond(ex, 405, """{"error":"method not allowed"}""")
                return@HttpHandler
            }
            val ok = running()
            val body = if (ok) """{"status":"ok"}""" else """{"status":"terminated"}"""
            respond(ex, if (ok) 200 else 503, body)
        }

    fun status(provider: () -> StatusSnapshot): HttpHandler = HttpHandler { ex -> TODO("Task 4") }

    fun logs(ring: EventRing): HttpHandler = HttpHandler { ex -> TODO("Task 5") }

    fun events(ring: EventRing): HttpHandler = HttpHandler { ex -> TODO("Task 6") }

    fun stop(onStop: (Boolean) -> Unit): HttpHandler = HttpHandler { ex -> TODO("Task 7") }

    internal fun respond(
        ex: HttpExchange,
        code: Int,
        body: String,
    ) {
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.observe.ObservabilityServerTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(cli): ObservabilityServer skeleton with /health route"
```

---

### Task 4: `/status` route

Wires `Routes.status(provider)` to serialize `StatusSnapshot` via `kotlinx.serialization.json`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/observe/Routes.kt`
- Modify: `src/test/kotlin/com/qkt/cli/observe/ObservabilityServerTest.kt`

- [ ] **Step 1: Add tests** for `/status` returning a fixture snapshot, GET-only (other methods 405), JSON content-type.

- [ ] **Step 2: Implement `Routes.status`**

```kotlin
fun status(provider: () -> StatusSnapshot): HttpHandler =
    HttpHandler { ex ->
        if (ex.requestMethod != "GET") {
            respond(ex, 405, """{"error":"method not allowed"}""")
            return@HttpHandler
        }
        try {
            val snap = provider()
            val body = json.encodeToString(StatusSnapshot.serializer(), snap)
            respond(ex, 200, body)
        } catch (e: Exception) {
            respond(ex, 500, """{"error":"${e.message?.replace("\"", "'")}"}""")
        }
    }
```

- [ ] **Step 3: Run + commit:** `feat(cli): /status endpoint`.

---

### Task 5: `/logs` route with `since` and `limit` query params

`GET /logs?since=<epoch-ms>&limit=<n>`. Returns ring snapshot as JSON array.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/observe/Routes.kt`
- Modify: `src/test/kotlin/com/qkt/cli/observe/ObservabilityServerTest.kt`

- [ ] **Step 1: Tests** — empty ring returns `[]`. After 5 appends, `/logs?limit=3` returns last 3. `/logs?since=<ts>` filters. Bad query returns 400.

- [ ] **Step 2: Implement** — parse query string, call `ring.snapshot`, serialize each `EventEntry` as `{"ts":…,"kind":…,"payload":…}`.

- [ ] **Step 3: Commit:** `feat(cli): /logs endpoint with since and limit query params`.

---

### Task 6: `/events` SSE route

Server-Sent Events stream. Holds one thread per connected client. Closes on client disconnect.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/observe/Routes.kt`
- Create: `src/test/kotlin/com/qkt/cli/observe/SseStreamTest.kt`

- [ ] **Step 1: Failing test** — open SSE connection on a background thread, append entries, read SSE-framed response, assert `event:` and `data:` lines for each appended entry.

```kotlin
@Test
fun `SSE streams appended entries as event-data frames`() {
    val ring = EventRing()
    val server = ObservabilityServer(ring, { error("not used") }, { true }, {}, "127.0.0.1", 0)
    server.start()
    try {
        val received = mutableListOf<String>()
        val client = OkHttpClient.Builder().readTimeout(Duration.ofSeconds(2)).build()
        val req = Request.Builder().url("http://127.0.0.1:${server.boundPort}/events").build()

        val future = java.util.concurrent.CompletableFuture.runAsync {
            try {
                client.newCall(req).execute().body!!.byteStream().bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null && received.size < 4) {
                        if (line.isNotBlank()) received.add(line)
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {}
        }

        Thread.sleep(200)  // let SSE handler subscribe
        ring.append("trade", buildJsonObject { put("v", "x") })
        ring.append("trade", buildJsonObject { put("v", "y") })

        future.get(3, TimeUnit.SECONDS)
        assertThat(received.filter { it.startsWith("event:") }).hasSize(2)
        assertThat(received.filter { it.startsWith("data:") }).hasSize(2)
    } finally {
        server.close()
    }
}
```

- [ ] **Step 2: Implement** — set SSE headers, `sendResponseHeaders(200, 0)`, subscribe to ring, write `event: ${kind}\ndata: ${json}\n\n` per entry, close subscription when client disconnects (catch `IOException` on flush).

- [ ] **Step 3: Commit:** `feat(cli): /events SSE endpoint`.

---

### Task 7: `/stop` route + `RunCommand` shutdown integration

`POST /stop[?flatten=true]`. Triggers the shutdown hook. Returns 202.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/observe/Routes.kt`
- Modify: `src/test/kotlin/com/qkt/cli/observe/ObservabilityServerTest.kt`

- [ ] **Step 1: Tests** — POST with no query body returns 202 and invokes `onStop(false)`. POST `?flatten=true` invokes `onStop(true)`. GET returns 405.

- [ ] **Step 2: Implement** — parse query, call `onStop`, return `{"status":"accepted","action":"graceful_shutdown"}`.

- [ ] **Step 3: Commit:** `feat(cli): /stop endpoint`.

---

### Task 8: `RunCommand` wiring + `PortPrinter` + new flags

Main integration. `RunCommand` parses new flags (`--port`, `--bind`, `--allow-privileged-port`, `--port-file`, `--no-observe`, `--ring-size`), validates them, instantiates `ObservabilityServer`, prints port discovery, wires `LiveSession.onTrade` (and optional `onSignal`) into the `EventRing`.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/observe/PortPrinter.kt`
- Create: `src/test/kotlin/com/qkt/cli/observe/PortPrinterTest.kt`
- Modify: `src/main/kotlin/com/qkt/cli/RunCommand.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` (optional `onSignal` callback if missing)
- Create: `src/test/kotlin/com/qkt/cli/RunCommandObservabilityTest.kt`

- [ ] **Step 1: `PortPrinter` + tests**

```kotlin
package com.qkt.cli.observe

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object PortPrinter {
    fun announce(
        host: String,
        port: Int,
        portFile: Path? = null,
        out: PrintStream = System.out,
    ) {
        out.println("[INFO] observability: http://$host:$port")
        out.println("QKT_PORT=$port")
        out.flush()
        portFile?.let { writeAtomic(it, port.toString()) }
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
```

Tests: capture `PrintStream`, assert both lines emitted. With `portFile` → assert file contains the port.

- [ ] **Step 2: Privileged-port validation in `RunCommand`**

```kotlin
val port = args.option("port")?.toIntOrNull() ?: 0
val allowPrivileged = args.flag("allow-privileged-port")
if (port in 1..1023 && !allowPrivileged) {
    System.err.println("qkt: error: port $port is privileged (< 1024); add --allow-privileged-port to override.")
    return ExitCodes.ARG_ERROR
}
val bind = args.option("bind") ?: "127.0.0.1"
val portFile = args.option("port-file")?.let { Path.of(it) }
val ringSize = args.option("ring-size")?.toIntOrNull() ?: 1000
val noObserve = args.flag("no-observe")
```

- [ ] **Step 3: Construct server (when `!noObserve`)**

```kotlin
val ring = EventRing(capacity = ringSize)
val server = if (!noObserve) {
    ObservabilityServer(
        ring = ring,
        statusProvider = { buildStatusSnapshot(session, startMs) },
        running = { session.isRunning() },
        onStop = { flatten ->
            // Trigger same shutdown path as SIGINT
            session.stop()
            if (flatten) /* ... existing flatten code ... */
        },
        bind = bind,
        port = port,
    ).also { it.start() }
} else null

server?.let { PortPrinter.announce(bind, it.boundPort, portFile) }
```

- [ ] **Step 4: Wire `onTrade` into ring**

`session.onTrade { trade, realized, _ -> ring.append("trade", tradeToJsonObject(trade, realized)) }`.

- [ ] **Step 5: Bind 0.0.0.0 warning**

```kotlin
if (bind != "127.0.0.1" && bind != "localhost") {
    System.err.println(
        "[WARN] --bind $bind: server is reachable from any host. There is NO authentication.",
    )
    System.err.println("       Front with nginx + basic auth + TLS for production exposure.")
}
```

- [ ] **Step 6: Tests** for each flag path (privileged-port reject, --no-observe disables server, --port-file writes file).

- [ ] **Step 7: Commit:** `feat(cli): wire ObservabilityServer into qkt run`.

---

### Task 9: End-to-end CLI test

Extend `EndToEndCliTest` (12a) with an observability scenario. In-process invocation, `--port 0`, scrape stdout for `QKT_PORT=`, hit `/status`, assert shape.

**Files:**
- Modify: `src/test/kotlin/com/qkt/cli/EndToEndCliTest.kt`

- [ ] **Step 1: Test** — boot `runMain(["run", fixture, "--port", "0"])` on a background thread. Capture stdout. After ~500ms, parse `QKT_PORT=` from captured output, hit `/status` with OkHttp, assert response shape. POST `/stop`. Verify thread exits cleanly.

- [ ] **Step 2: Commit:** `test(cli): end-to-end observability scenario`.

---

### Task 10: Phase 12b changelog

Per qkt SKILL.md §6.

**Files:**
- Create: `docs/phases/phase-12b-observability.md`

Sections:
1. Summary (3 sentences).
2. What's new — every new public surface (EventRing, StatusSnapshot, ObservabilityServer, new RunCommand flags).
3. Migration (none — purely additive on top of 12a).
4. Usage cookbook — terminal sessions for each endpoint, `--port-file` extraction pattern, browser SSE example.
5. Testing patterns — endpoint contract tests via OkHttp, in-process E2E with port scraping.
6. Known limitations (no auth, no TLS, no daemon, no persistent log, single-strategy).
7. References.

- [ ] **Step 1: Write the changelog**.
- [ ] **Step 2: Bump `BuildInfo.VERSION` to `0.12.0`** (12b is a meaningful surface bump — new public HTTP API).
- [ ] **Step 3: Commit:** `docs: phase 12b changelog`.

---

## Self-review checklist

After all tasks complete:

- [ ] `./gradlew build` green.
- [ ] All commit messages match `<type>(<scope>): <subject>`.
- [ ] No AI footers, no emoji.
- [ ] `./gradlew installDist && build/install/qkt/bin/qkt run <fixture>` shows `QKT_PORT=…` line.
- [ ] `curl http://127.0.0.1:<port>/health` returns `{"status":"ok"}`.
- [ ] `curl http://127.0.0.1:<port>/status` returns valid JSON snapshot.
- [ ] `curl -X POST http://127.0.0.1:<port>/stop` returns 202 and triggers shutdown.
- [ ] `--port 80` rejected without `--allow-privileged-port`.
- [ ] Phase 12b changelog `Merge commit:` filled in after merge.

---

## Merge

```bash
git checkout main
git merge --no-ff phase12b-observability -m "merge: phase 12b observability HTTP port"
./gradlew build   # verify
git add docs/phases/phase-12b-observability.md
git commit -m "docs: link phase 12b changelog to merge commit"
git branch -d phase12b-observability
```
