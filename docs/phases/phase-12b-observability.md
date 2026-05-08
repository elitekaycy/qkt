# Phase 12b — Single-Strategy Observability HTTP Port

## Summary

Phase 12b adds an embedded HTTP server to `qkt run`. A long-running paper-trade can now be inspected from a browser, scraped by Grafana, or remote-stopped via curl — without keeping the terminal attached. Five JSON endpoints (`GET /status`, `GET /events` SSE, `GET /logs`, `GET /health`, `POST /stop`) cover the read-side observability surface and graceful shutdown. Default port `0` uses the kernel's ephemeral allocator (collision-free by construction); the chosen port prints to stdout in two forms (`[INFO] observability: http://...` and `QKT_PORT=…`) and optionally writes to a `--port-file <path>` for external tooling. Bind defaults to `127.0.0.1`; `--bind 0.0.0.0` is opt-in with a stderr warning. Privileged ports (< 1024) are rejected unless `--allow-privileged-port` is set. No new external dependencies — `com.sun.net.httpserver` is JDK built-in and `kotlinx.serialization.json` was already a project dep.

## What's new

- `com.qkt.cli.observe.EventRing(capacity)` — bounded ring buffer with `append(kind, payload)`, `snapshot(since, limit)`, and `subscribe(listener)`. Listener fan-out is best-effort (one bad listener can't break others). Default capacity 1000.
- `com.qkt.cli.observe.EventEntry(ts, kind, payload)` — ring entry with epoch-ms timestamp, string kind (`trade` / `signal` / `log`), and `JsonObject` payload.
- `com.qkt.cli.observe.StatusSnapshot` + `PositionDto` + `TradeDto` — `kotlinx.serialization`-annotated DTOs for `/status`. `BigDecimalAsNumberSerializer` emits `BigDecimal` as a JSON number with `toPlainString()` (no scientific notation, preserves scale via `JsonUnquotedLiteral`).
- `com.qkt.cli.observe.ObservabilityServer(ring, statusProvider, running, onStop, bind, port)` — `com.sun.net.httpserver`-backed server with `start()` / `close()`. Routes `/status`, `/events`, `/logs`, `/health`, `/stop`. Fixed thread pool of 4 (one slot per concurrent SSE client; three for short-lived endpoints).
- `com.qkt.cli.observe.Routes` — top-level handler functions, one per route. Hand-rolled JSON for the small endpoints; `kotlinx.serialization` for `/status`.
- `com.qkt.cli.observe.PortPrinter.announce(host, port, portFile?, out?)` — emits the two stdout lines (`[INFO] observability: …` and `QKT_PORT=…`) and optionally writes `port` to a file via temp-then-rename atomic write.
- New flags on `qkt run`:
  - `--port <num>` — default `0` (kernel-assigned ephemeral). Privileged ports rejected without `--allow-privileged-port`.
  - `--bind <addr>` — default `127.0.0.1`. `0.0.0.0` opt-in with stderr warning.
  - `--allow-privileged-port` — escape hatch for `--port < 1024` (requires root or `CAP_NET_BIND_SERVICE`).
  - `--port-file <path>` — atomic-write the bound port to the file; useful for shell wrappers.
  - `--no-observe` — disables the embedded server entirely.
  - `--ring-size <n>` — event ring capacity. Default 1000.
- `LiveSession.onSignal` — optional callback (default no-op) wired via `bus.subscribe<SignalEvent>`. Lets `RunCommand` push signal events into the ring alongside trades.
- `BuildInfo.VERSION` bumped to `0.12.0` (new public HTTP API surface).

## Migration from previous phase

Purely additive on top of 12a. Existing 12a invocations (`qkt run foo.qkt`) gain the embedded HTTP server transparently — same default behaviour as before, plus an `[INFO] observability: …` line and a `QKT_PORT=…` line printed at startup. To preserve 12a's pre-observability behaviour exactly, pass `--no-observe`.

`LiveSession`'s constructor gains an optional `onSignal: ((Signal) -> Unit)? = null` parameter. Existing callers that don't supply it are unaffected.

## Usage cookbook

### Default (kernel-assigned port, localhost-only)

```
$ qkt run strategies/momentum.qkt
[INFO] qkt 0.12.0 — strategy momentum_basket v1 — paper-trading
[INFO] subscribed: BYBIT:BTCUSDT, INTERACTIVE:XAUUSD, ALPACA:AAPL
[INFO] observability: http://127.0.0.1:47291
QKT_PORT=47291
[INFO] running, Ctrl+C to stop
```

### Inspect status from another terminal

```
$ curl -s http://127.0.0.1:47291/status | jq
{
  "strategy": "momentum_basket",
  "version": 1,
  "uptimeMs": 47823,
  "startedAt": "2026-05-08T14:31:14Z",
  "equity": 0,
  "balance": 0,
  "realized": 0,
  "unrealized": 0,
  "positions": [],
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
```

(Equity / balance / realized / unrealized return zeros pending full PnL plumbing through `LiveSessionHandle` — see "Known limitations" below.)

### Tail live events via SSE

```
$ curl -N http://127.0.0.1:47291/events
event: trade
data: {"side":"BUY","symbol":"BTCUSDT","qty":"0.001","price":"68234.50","realized":"0.00"}

event: trade
data: {"side":"SELL","symbol":"BTCUSDT","qty":"0.001","price":"68000.00","realized":"-2.34"}

^C
```

Browser-friendly: `EventSource('http://localhost:47291/events').onmessage = e => console.log(e.data)`.

### Query historical events

```
$ curl -s "http://127.0.0.1:47291/logs?limit=5" | jq
[
  {"ts": 1715177525000, "kind": "trade", "payload": {"side": "BUY", ...}},
  {"ts": 1715177530000, "kind": "trade", "payload": {"side": "SELL", ...}}
]

$ curl -s "http://127.0.0.1:47291/logs?since=1715177528000&limit=10"
```

### Graceful remote shutdown

```
$ curl -s -X POST http://127.0.0.1:47291/stop
{"status":"accepted","action":"graceful_shutdown"}

# Original terminal:
[INFO] graceful shutdown initiated
[INFO] terminated; 4 trades
```

`?flatten=true` flattens positions before exit:

```
$ curl -s -X POST 'http://127.0.0.1:47291/stop?flatten=true'
```

### Extract the port for a wrapper script

Two options:

```bash
# Via grep on stdout
qkt run strategies/momentum.qkt 2>&1 | tee qkt.log &
port=$(grep -m1 '^QKT_PORT=' qkt.log | cut -d= -f2)
echo "monitoring on $port"
```

```bash
# Via --port-file (cleaner)
qkt run strategies/momentum.qkt --port-file /tmp/qkt.port &
sleep 1
port=$(cat /tmp/qkt.port)
curl http://127.0.0.1:$port/status
```

### Pin a port (e.g. for a stable Grafana scrape)

```
$ qkt run strategies/momentum.qkt --port 47291
```

If port 47291 is in use, the bind fails fast with a clear error.

### Privileged port (Docker + `CAP_NET_BIND_SERVICE`)

```
$ qkt run strategies/momentum.qkt --port 80
qkt: error: port 80 is privileged (< 1024); add --allow-privileged-port to override.
$ qkt run strategies/momentum.qkt --port 80 --allow-privileged-port
[INFO] observability: http://127.0.0.1:80
```

### Minimal-footprint mode

```
$ qkt run strategies/momentum.qkt --no-observe
# No HTTP server. No QKT_PORT line. Same as 12a behaviour.
```

## Testing patterns

### Endpoint contract via OkHttp

```kotlin
val server = ObservabilityServer(ring, statusProvider, { true }, {}, "127.0.0.1", 0)
server.start()
try {
    val resp = client.newCall(
        Request.Builder().url("http://127.0.0.1:${server.boundPort}/status").build()
    ).execute()
    assertThat(resp.code).isEqualTo(200)
    assertThat(resp.body!!.string()).contains("\"strategy\"")
} finally {
    server.close()
}
```

### SSE framing

Open `/events`, feed entries to the ring on the test thread, read the streamed bytes via OkHttp's `byteStream().bufferedReader()`. The SSE handler subscribes synchronously on connection open — use a `CountDownLatch` keyed off the prelude `:` line (the heartbeat) to avoid races between the test's `subscribe` and the test's `append`.

### `--port-file` atomicity

```kotlin
val tmp = Files.createTempDirectory("qkt-test").resolve("port.txt")
PortPrinter.announce("127.0.0.1", 47291, tmp, PrintStream(ByteArrayOutputStream()))
assertThat(Files.readString(tmp)).isEqualTo("47291")
```

### End-to-end via `runMain`

Boot `Thread { runMain(arrayOf("run", fixture, "--port", "0")) }.start()`. Capture stdout, parse `QKT_PORT=`, hit `/status`, POST `/stop`, join the thread.

## Known limitations

- **`/status` returns zeros for equity / balance / realized / unrealized.** `LiveSession`'s public surface (`LiveSessionHandle`) doesn't yet expose live PnL or positions. Strategy / version / uptime / last trade are real; the financial fields are stubbed at `BigDecimal.ZERO`. Full PnL plumbing lands when 12c wires `StrategyPnL` through the daemon's status snapshot.
- **No auth, no TLS.** Localhost-only by default. `--bind 0.0.0.0` is opt-in and the user's responsibility to front with nginx + auth + TLS. Auth lands in 12c's daemon control plane.
- **No persistent log.** Ring is in-memory; ancient events drop off at capacity. For long-term history, redirect stdout to a file. Persistent logging is 12c+.
- **No event replay.** `/events` streams from connection-open; past events use `/logs`.
- **Fixed thread pool of 4.** Up to 4 concurrent SSE clients (or fewer if other endpoints in flight). Acceptable for single-user CLI; 12c will switch to a `Selector`-based event loop if real load shows up.
- **No multi-strategy.** One strategy per process. Daemon-shape (`qkt deploy / list / logs / status / stop <name>`) is 12c.
- **No Docker base image.** Phase 12c.
- **No Prometheus exposition format.** `/status` is plain JSON; users wanting Prometheus can wrap with a JSON exporter.
- **No HTML dashboard.** API is the deliverable; UIs are tooling-layer concerns.

## References

- Spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase12b-design.md`
- Plan: `docs/superpowers/plans/2026-05-08-trading-engine-phase12b.md`
- Phase 12a (CLI binary): `docs/superpowers/specs/2026-05-08-trading-engine-phase12a-design.md`
- jdk.httpserver: `com.sun.net.httpserver.HttpServer`
- SSE spec: <https://html.spec.whatwg.org/multipage/server-sent-events.html>
- Merge commit: 232e7b2
