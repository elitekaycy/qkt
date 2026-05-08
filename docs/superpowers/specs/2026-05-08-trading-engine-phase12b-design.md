# Phase 12b — Single-Strategy Observability HTTP Port

**Status:** Design draft.
**Predecessor:** Phase 12a (`qkt` CLI binary — `parse`, `backtest`, `run`).
**Successor:** Phase 12c (daemon + multi-strategy + Docker base image).

---

## 1. Mission

After 12a, `qkt run foo.qkt` paper-trades a strategy against live TradingView ticks and prints trade events to stdout. To monitor a long-running strategy you need to keep the terminal attached, scroll back through console output, and have no way to ask "what's the equity right now?" without restarting. Phase 12b adds an embedded HTTP server to `qkt run` that exposes a small read-only JSON API plus a Server-Sent Events stream of live events. Three big wins:

1. **Watch from a browser**: `curl localhost:40909/status` returns equity, positions, last trade — at any moment.
2. **Tail without `tee`**: `curl -N localhost:40909/events` streams every fill / signal as it happens, structured JSON, browser-friendly.
3. **Programmatic control**: `POST localhost:40909/stop` triggers graceful shutdown; tooling can scrape `/status` for monitoring (Grafana, Prometheus pull, custom dashboards).

The HTTP server is single-strategy and single-process — exactly one strategy per `qkt run` invocation, one HTTP port per process. Multi-strategy hosting is the daemon's job in 12c.

Three architectural decisions taken before writing the spec:

1. **`com.sun.net.httpserver` (built-in JDK).** No new dependencies. The endpoint surface is small (5 endpoints, all read-only or fire-and-forget), throughput requirements are low (a quant + a Grafana scraper, not 10K RPS), and the JDK module is well-supported on JDK 21. Ktor / Spring / OkHttp-server all overshoot.
2. **`kotlinx.serialization.json` for the JSON.** Already a project dep — used by `kotlinx-serialization-json`. Zero new deps. Hand-rolled JSON (12a's pattern) doesn't scale to nested DTOs the way `/status` needs.
3. **Always-on, kernel-assigned ephemeral port, localhost-only by default.** The HTTP port is the *whole point* of 12b — making it opt-in defeats the purpose. `--port 0` (default) hands the port choice to the kernel's ephemeral allocator (Linux: 32768-60999; macOS: 49152-65535) — atomic find-and-bind, **collision-free by construction**, never overlaps any well-known service port. The chosen port is printed to stdout in two forms (human and machine-parseable). `--port <n>` (>= 1024) binds explicitly. `--port <n>` (< 1024) is rejected unless `--allow-privileged-port` is also set, since binding privileged ports needs root and conflicts with real system services. `--bind 0.0.0.0` opt-in for remote scraping. No auth in 12b — `localhost`-only binding is the security boundary.

---

## 2. Goals

- **`qkt run foo.qkt`** starts an embedded HTTP server on a kernel-assigned ephemeral port (or `--port <n>`) bound to `127.0.0.1` (or `--bind <ip>`). The kernel's ephemeral allocator guarantees no collision with any other process at the moment of bind — there is no race, no retry loop, no "what if the port is taken" failure mode. With `--port 0` (default) you can run hundreds of concurrent `qkt` instances on one host and never get a port conflict.
- **`GET /status`** — JSON snapshot: strategy name/version, uptime, current equity / balance / realized / unrealized, open positions per symbol, last trade.
- **`GET /events`** — Server-Sent Events stream of `trade` / `signal` / `log` events as they happen. Auto-reconnect-friendly. Each event is a JSON object on its own line.
- **`GET /logs?since=<ts>&limit=<n>`** — bounded ring-buffer query. Returns recent log entries as JSON list. `since` is an epoch-ms timestamp; `limit` defaults to 100, max 1000.
- **`POST /stop`** — triggers graceful shutdown (same code path as SIGINT). Returns 202 Accepted. Optional `?flatten=true` query param flattens positions before exit.
- **`GET /health`** — always 200 OK with `{"status":"ok"}` while the strategy is running. Liveness probe.
- **In-memory event ring buffer** — bounded, default 1000 events. Drives `/events` (live) and `/logs` (historical).
- **Port discovery printed to stdout in two forms.** A human line `[INFO] observability: http://127.0.0.1:47291` and a machine-parseable line `QKT_PORT=47291` so wrapper scripts can `eval $(qkt run … 2>&1 | grep ^QKT_PORT=)` or grep for it. Optional `--port-file <path>` writes the port number to a file (atomic write — temp + rename) so external tooling can `cat` it without grepping logs.
- **Graceful shutdown** — `POST /stop` and SIGINT both drain the bus, close the HTTP server, exit.

## Non-goals

- **No auth, no TLS.** `localhost`-only by default. Cross-machine scraping is opt-in via `--bind` and the user's responsibility (front with nginx + auth + TLS if exposed).
- **No mutation endpoints beyond `/stop`.** No `POST /flatten`, `POST /pause`, `PATCH /strategy`. Read-only API surface; control plane lives in 12c with the daemon.
- **No multi-strategy.** One process, one strategy, one port. Daemon is 12c.
- **No WebSocket.** SSE covers the live-tail use case with less code and less protocol surface. WebSocket would add a dep.
- **No Prometheus exposition format.** `/status` returns plain JSON. Users wanting Prometheus can transform with `jsonexporter` or write a small adapter. We'll add `/metrics` if a real demand surfaces.
- **No HTML dashboard.** No `/`, no static files, no JS UI. The API is the deliverable; UIs are tooling layers.
- **No persistent log storage.** The ring buffer is in-memory, capped at 1000 entries. For longer history users redirect stdout to a file. Persistent logging is 12c+.
- **No event replay.** `/events` streams from the moment the connection opens. Past events are not replayed (use `/logs` for the recent past).
- **No CORS configuration.** All responses include `Access-Control-Allow-Origin: *` for `localhost` development. Tightening for `--bind 0.0.0.0` is the user's responsibility.

---

## 3. Worked example

```
$ qkt run strategies/momentum.qkt
[INFO] qkt 0.11.6 — strategy momentum_basket v1 — paper-trading
[INFO] subscribed: BYBIT:BTCUSDT, INTERACTIVE:XAUUSD, ALPACA:AAPL
[INFO] observability: http://127.0.0.1:47291
QKT_PORT=47291
[INFO] running, Ctrl+C to stop

# Or extract the port from a wrapper script:
$ port=$(qkt run strategies/momentum.qkt --port-file /tmp/qkt.port & sleep 1; cat /tmp/qkt.port)
$ curl -s http://127.0.0.1:$port/status
[INFO] 2026-05-08T14:32:01Z BUY BTCUSDT qty=0.001 px=68234.50 realized=0.00
...

# In another terminal:

$ curl -s http://127.0.0.1:47291/status | jq
{
  "strategy": "momentum_basket",
  "version": 1,
  "uptimeMs": 47823,
  "startedAt": "2026-05-08T14:31:14Z",
  "equity": 9997.66,
  "balance": 10000.00,
  "realized": -2.34,
  "unrealized": 0.00,
  "positions": [
    {"symbol": "BTCUSDT", "qty": 0.000, "avgPrice": 0.00}
  ],
  "lastTrade": {
    "timestamp": "2026-05-08T14:38:05Z",
    "side": "SELL",
    "symbol": "BTCUSDT",
    "qty": 0.001,
    "price": 68000.00,
    "realized": -2.34
  }
}

$ curl -s http://127.0.0.1:47291/health
{"status":"ok"}

$ curl -N http://127.0.0.1:47291/events
event: trade
data: {"timestamp":"2026-05-08T14:42:11Z","side":"BUY","symbol":"BTCUSDT","qty":0.001,"price":68150.00}

event: trade
data: {"timestamp":"2026-05-08T14:48:23Z","side":"SELL","symbol":"BTCUSDT","qty":0.001,"price":68250.00,"realized":1.00}

^C

$ curl -s "http://127.0.0.1:47291/logs?limit=5" | jq
[
  {"ts":"2026-05-08T14:42:11Z","kind":"trade","payload":{"side":"BUY","symbol":"BTCUSDT",...}},
  {"ts":"2026-05-08T14:48:23Z","kind":"trade","payload":{"side":"SELL",...}},
  ...
]

$ curl -s -X POST http://127.0.0.1:47291/stop
{"status":"accepted","action":"graceful_shutdown"}

# Original terminal, after a moment:
[INFO] graceful shutdown initiated
[INFO] terminated; 4 trades
$
```

---

## 4. Architecture

```
            qkt run foo.qkt --port 47291
                      │
                      ▼
            ┌────────────────────────┐
            │ RunCommand             │   12a-shipped
            │  • parse + compile     │
            │  • build LiveSession   │
            │  • SIGINT hook         │
            └────────────┬───────────┘
                         │ + new in 12b:
                         ▼
            ┌────────────────────────┐
            │ ObservabilityServer    │   ← new in 12b
            │  • jdk.httpserver      │
            │  • bound 127.0.0.1     │
            │  • routes:             │
            │     GET  /status       │
            │     GET  /events (SSE) │
            │     GET  /logs         │
            │     GET  /health       │
            │     POST /stop         │
            └────────────┬───────────┘
                         │
                         ▼
            ┌────────────────────────┐
            │ EventRing              │   ← new in 12b
            │  • bounded ring buffer │
            │  • thread-safe append  │
            │  • SSE-listener fan-out│
            └────────────────────────┘
                         ▲
                         │ writes from
                         │
            LiveSession.onTrade callback (12a-shipped)
            + log adapter (new in 12b)
```

### 4.1 Module placement

New code lives in `com.qkt.cli.observe`:

```
src/main/kotlin/com/qkt/cli/observe/
├── ObservabilityServer.kt       # jdk.httpserver setup, route dispatch
├── EventRing.kt                 # bounded ring buffer + SSE fan-out
├── StatusSnapshot.kt            # /status DTO
├── EventDto.kt                  # /events and /logs DTO
└── Routes.kt                    # per-route handlers (small functions)

src/main/kotlin/com/qkt/cli/RunCommand.kt    # modified: instantiate + wire ObservabilityServer
```

### 4.2 EventRing

```kotlin
class EventRing(private val capacity: Int = 1000) {
    private data class Entry(val ts: Long, val kind: String, val payload: JsonObject)

    private val buf = ArrayDeque<Entry>(capacity)
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()
    private val lock = ReentrantLock()

    fun append(kind: String, payload: JsonObject) {
        val entry = Entry(System.currentTimeMillis(), kind, payload)
        lock.withLock {
            buf.addLast(entry)
            while (buf.size > capacity) buf.removeFirst()
        }
        for (l in listeners) runCatching { l(entry) }   // best-effort SSE fan-out
    }

    fun snapshot(since: Long, limit: Int): List<Entry> = lock.withLock {
        buf.filter { it.ts >= since }.takeLast(limit.coerceIn(1, capacity))
    }

    fun subscribe(listener: (Entry) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
}
```

Single-writer-many-readers. Append is O(1); snapshot is O(n) over the buffer; SSE fan-out is best-effort and won't block writes.

### 4.3 ObservabilityServer

```kotlin
class ObservabilityServer(
    private val ring: EventRing,
    private val statusProvider: () -> StatusSnapshot,
    private val onStop: (flatten: Boolean) -> Unit,
    bind: String = "127.0.0.1",
    port: Int = 0,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0)
    val boundPort: Int get() = server.address.port

    init {
        server.createContext("/status", ::handleStatus)
        server.createContext("/events", ::handleEvents)   // SSE
        server.createContext("/logs", ::handleLogs)
        server.createContext("/health", ::handleHealth)
        server.createContext("/stop", ::handleStop)        // POST only
        server.executor = Executors.newFixedThreadPool(4)
    }

    fun start() = server.start()

    override fun close() = server.stop(0)

    // handlers ~10 LoC each, total ~80 LoC
}
```

JDK's `HttpServer` is built-in. `Executors.newFixedThreadPool(4)` is enough for our concurrency profile (one or two scrapers, occasional curl).

### 4.4 SSE handler shape

```kotlin
private fun handleEvents(ex: HttpExchange) {
    ex.responseHeaders.add("Content-Type", "text/event-stream")
    ex.responseHeaders.add("Cache-Control", "no-cache")
    ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
    ex.sendResponseHeaders(200, 0)
    val out = ex.responseBody.bufferedWriter()
    val close = ring.subscribe { entry ->
        try {
            out.write("event: ${entry.kind}\n")
            out.write("data: ${entry.payload.toJsonString()}\n\n")
            out.flush()
        } catch (e: IOException) { /* client gone, will be closed below */ }
    }
    // Block until the connection closes (handler thread parks).
    try {
        ex.requestBody.readAllBytes()  // returns when client closes
    } finally {
        close.close()
        runCatching { out.close() }
    }
}
```

Holds one thread per connected SSE client. With a `newFixedThreadPool(4)` executor, that means up to 4 concurrent SSE clients (or fewer if other endpoints are in flight). Acceptable for single-strategy single-user use. If we need more, bump to `newCachedThreadPool` (post-12b polish).

### 4.5 RunCommand wiring

```kotlin
class RunCommand(...) {
    fun run(): Int {
        // ... existing parse + compile + LiveSession setup ...

        val ring = EventRing()
        val server = ObservabilityServer(
            ring = ring,
            statusProvider = { buildStatusSnapshot(session) },
            onStop = { flatten -> session.stop(flattenOnStop = flatten) },
            bind = args.option("bind") ?: "127.0.0.1",
            port = args.option("port")?.toInt() ?: 0,
        )
        server.start()
        println("[INFO] observability: http://${server.bind}:${server.boundPort}")

        // wire LiveSession callbacks to push into ring:
        session.onTrade { trade, realized, _ -> ring.append("trade", tradeToJson(trade, realized)) }
        // (extend LiveSession to also expose onSignal / onLog hooks if missing)

        // ... existing await-termination + shutdown hook ...

        server.close()
        return ExitCodes.SUCCESS
    }
}
```

The HTTP server is constructed only by `RunCommand`. `qkt parse` and `qkt backtest` don't get a port (12a behaviour preserved).

---

## 5. CLI surface

### 5.1 New flags on `qkt run`

| Flag | Default | Description |
|---|---|---|
| `--port <num>` | `0` | TCP port for the observability server. `0` = kernel-assigned ephemeral port (collision-free by construction). |
| `--allow-privileged-port` | (off) | Required to use `--port <n>` with n < 1024. Without this flag, `--port 80` etc. is rejected at startup. |
| `--bind <addr>` | `127.0.0.1` | Bind address. `0.0.0.0` for cross-host (no auth — opt-in only). |
| `--port-file <path>` | (off) | Write the bound port number to this file as soon as the server starts. Atomic write (temp + rename). External tooling `cat`s it instead of grepping stdout. |
| `--no-observe` | (off) | Disables the embedded HTTP server entirely. For minimal footprint runs. |
| `--ring-size <n>` | `1000` | Event ring buffer size. Affects `/logs` history depth. |

The chosen port is **always printed to stdout twice** when the server starts:

```
[INFO] observability: http://127.0.0.1:47291
QKT_PORT=47291
```

The `QKT_PORT=…` line is positionally stable (always at the start of the line, no prefix) so shell scripts can extract it without parsing log timestamps.

Existing 12a flags (`--source`, `--starting-balance`, `--shutdown-timeout`, `--flatten-on-stop`, `--log-level`, `--debug`, `--config`) are unchanged.

### 5.2 No changes to `qkt parse` or `qkt backtest`

They don't run long enough to need observability. Phase 12b is a `qkt run`-only concern.

---

## 6. Endpoints

### 6.1 `GET /status`

Returns 200 with `application/json`:

```json
{
  "strategy": "momentum_basket",
  "version": 1,
  "uptimeMs": 47823,
  "startedAt": "2026-05-08T14:31:14Z",
  "equity": 9997.66,
  "balance": 10000.00,
  "realized": -2.34,
  "unrealized": 0.00,
  "positions": [
    {"symbol": "BTCUSDT", "qty": 0.000, "avgPrice": 0.00},
    {"symbol": "XAUUSD", "qty": 1.000, "avgPrice": 2400.00}
  ],
  "lastTrade": {
    "timestamp": "2026-05-08T14:38:05Z",
    "side": "SELL",
    "symbol": "BTCUSDT",
    "qty": 0.001,
    "price": 68000.00,
    "realized": -2.34
  }
}
```

Built fresh on every request from the live `LiveSession` state. No caching.

### 6.2 `GET /events`

`Content-Type: text/event-stream`. Streams each new event as:

```
event: <kind>
data: <json>

```

Where `<kind>` is `trade`, `signal`, or `log`. The connection stays open until the client closes or the server stops.

### 6.3 `GET /logs?since=<epoch-ms>&limit=<n>`

Returns 200 with `application/json` — array of historical events:

```json
[
  {"ts": 1715177525000, "kind": "trade", "payload": {"side": "BUY", "symbol": "BTCUSDT", ...}},
  {"ts": 1715177530000, "kind": "trade", "payload": {"side": "SELL", ...}}
]
```

`since` defaults to `0` (start of run). `limit` defaults to `100`, max `1000` (= ring capacity).

### 6.4 `GET /health`

Returns 200 with `{"status":"ok"}` while the strategy is running. Returns 503 if the strategy has terminated but the server is briefly still responding.

### 6.5 `POST /stop[?flatten=true]`

Returns 202 with `{"status":"accepted","action":"graceful_shutdown"}`. Triggers the same shutdown hook SIGINT does. `?flatten=true` flattens positions before exit.

`GET /stop` returns 405 Method Not Allowed.

### 6.6 Errors

All errors return JSON: `{"error": "<message>"}`.

- 400: malformed query params (`?since=abc`).
- 404: unknown route.
- 405: wrong method.
- 500: internal error (caught and wrapped; never leaks stack traces in 12b).

---

## 7. Security model

**Default**: bound to `127.0.0.1`. Only the local machine can connect. No auth needed.

**With `--bind 0.0.0.0`**: the server is reachable from anywhere on the network. There is **no auth** in 12b. The user is responsible for fronting it with a reverse proxy + auth + TLS if exposed. The CLI prints a warning when `--bind` is non-loopback:

```
[WARN] --bind 0.0.0.0: server is reachable from any host. There is NO authentication.
       Front with nginx + basic auth + TLS for production exposure.
```

**Privileged port rejection**: `--port <n>` with n < 1024 is rejected at startup unless `--allow-privileged-port` is also set. The intent is twofold:

1. Privileged ports require root on POSIX. Most users running `qkt run` do so as a normal user; binding to port 80 would silently fail with a confusing error.
2. Privileged ports are reserved for system services (SSH on 22, HTTP on 80, HTTPS on 443). A `qkt` strategy has no business there.

The escape hatch (`--allow-privileged-port`) exists for the rare case where a user wants to run `qkt` behind a reverse proxy as PID 1 in a Docker container with `CAP_NET_BIND_SERVICE`.

Auth lands in 12c (where the daemon's control plane needs it anyway).

---

## 8. Testing strategy

Per qkt convention: real types, no mocks, JUnit 5 + AssertJ, deterministic.

- **`EventRingTest`** — append, eviction at capacity, `snapshot(since, limit)`, listener fan-out under concurrent appends.
- **`ObservabilityServerTest`** — spin up a real server on `port=0`, hit each endpoint with `OkHttpClient` (already a dep — used elsewhere in the project), assert response shape and status code.
- **`SseStreamTest`** — open `/events`, append entries, read and assert the SSE-framed bytes.
- **`StopEndpointTest`** — POST `/stop`, assert 202 + `onStop` callback was invoked with the expected `flatten` flag.
- **`RunCommandObservabilityTest`** — extends 12a's `RunCommandTest` with `--port 0`; asserts the printed `[INFO] observability: …` line includes a real port; hits `/status` once with the bounded fixture; verifies the strategy still terminates cleanly.

End-to-end via `EndToEndCliTest` extension: invoke `runMain(arrayOf("run", fixture, "--port", "0"))` in-process, scrape the captured stdout for the port number, hit `/status`, verify shape.

---

## 9. Risk

**Risk: Low.** The HTTP server is a JDK module, the JSON serialization is an existing dep, the wiring sits inside `RunCommand` only. Mitigations:

- `ObservabilityServer` is a single class with a clear lifecycle (`start` / `close`). Failure modes are localized — a server crash doesn't kill the strategy (we catch in handler bodies, log, return 500).
- Tests cover endpoint contract, SSE framing, ring eviction, `/stop` semantics, and the `RunCommand` integration.
- `--no-observe` flag exists as a kill-switch if the server ever misbehaves in a deployment.

**Risk: SSE thread-pool exhaustion.** Fixed-size pool of 4 means 4 concurrent SSE clients block other endpoint handlers. Mitigated for 12b by: (a) typical use is 0-2 SSE clients, (b) `--ring-size` and `/logs` cover the offline-tooling use case without requiring SSE, (c) post-12b we can switch to a `Selector`-based event loop if real load shows up. Acceptable for a single-user single-strategy CLI runner.

**Risk: `/stop` race condition during in-flight orders.** Mitigated by routing `/stop` through the same shutdown hook SIGINT uses — the existing 12a shutdown drain (5s timeout) applies. Document explicitly: graceful shutdown is best-effort.

**Risk: In-memory ring loses events on crash.** By design — 12b is in-memory only. Persistent event logs are a 12c concern alongside the daemon. Document.

---

## 10. Phase decomposition (preview for the plan)

Approximately 8 tasks.

1. **`EventRing` + `EventRingTest`** — bounded ring with listener fan-out. Standalone, no HTTP.
2. **`StatusSnapshot` DTO + JSON serialization.** Reuses `kotlinx.serialization.json`.
3. **`ObservabilityServer` skeleton + `/health` route.** Spin up jdk.httpserver, route dispatch, smoke test.
4. **`/status` route** + `RunCommandObservabilityTest` integration.
5. **`/logs` route** with `since` / `limit` query params.
6. **`/events` SSE route** + `SseStreamTest`.
7. **`/stop` route** + `RunCommand` shutdown integration.
8. **`RunCommand` wiring** — instantiate server, log port, propagate `--port` / `--bind` / `--no-observe` / `--ring-size` flags.
9. **End-to-end test** — extend `EndToEndCliTest` to exercise `qkt run --port 0` + scrape `/status` + verify shutdown.
10. **Phase 12b changelog.**

---

## 11. References

- Spec for predecessor (Phase 12a): `docs/superpowers/specs/2026-05-08-trading-engine-phase12a-design.md`
- Master spec (Phase 12 roadmap): `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §10.
- Phase 8 (`StrategyContext`, `LiveSession` source-of-truth for live execution): `docs/superpowers/specs/2026-05-06-trading-engine-phase8-design.md`
- jdk.httpserver: `com.sun.net.httpserver.HttpServer` — built into JDK 21, no module imports needed beyond `requires jdk.httpserver` if we ever go modular.
- SSE spec: <https://html.spec.whatwg.org/multipage/server-sent-events.html>
