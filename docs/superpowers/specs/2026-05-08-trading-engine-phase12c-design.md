# Phase 12c — Daemon, Multi-Strategy Hosting, Docker Base Image

**Status:** Design draft.
**Predecessor:** Phase 12b (single-strategy observability HTTP port).
**Successor:** Phase 12d (package-manager distribution — Homebrew, apt).

---

## 1. Mission

After 12b, `qkt run foo.qkt` paper-trades one strategy with a per-process HTTP observability port. To run ten strategies you'd start ten terminals, manage ten PIDs, scrape ten ports. Phase 12c collapses that into a single daemon process — one machine, many strategies, each addressable by name.

The shape is **`qkt` is `docker` for trading strategies**:

```
qkt daemon                         # PID 1 in a Docker container, or backgrounded by systemd
qkt deploy ema.qkt --as ema-fast   # register + start, returns port
qkt list                           # NAME      UPTIME    PORT     TRADES   PID
qkt logs ema-fast -f               # tail ~/.local/state/qkt/logs/ema-fast.log
qkt status ema-fast                # proxies to that strategy's :PORT/status
qkt stop ema-fast                  # graceful shutdown
qkt status                         # tabular status of all strategies
```

Plus delivery: a published Docker image at `ghcr.io/elitekaycy/qkt:<version>` so users `docker run` the daemon and `docker exec` to manage it.

Architectural decisions taken before writing the spec:

1. **HTTP-over-TCP-localhost for the daemon control plane.** The plan was Unix socket (Docker's pattern) but `com.sun.net.httpserver` doesn't support `UnixDomainSocketAddress`, and pulling in `jnr-unixsocket` or rolling our own HTTP framing on a Java 16+ Unix socket is out of proportion to the value. TCP on `127.0.0.1` with the port written to `~/.local/state/qkt/control.port` (atomic write, file mode 0600) is functionally local-only and adds zero deps. The CLI reads the port file to find the daemon. Same security boundary as a Unix socket on a single-user box. Multi-user hardening is post-12c.
2. **Foreground-only daemon.** `qkt daemon` runs in the foreground; the user backgrounds it via `systemd`, `launchd`, `nohup &`, or by being PID 1 in a Docker container. Self-daemonization (`fork()`, double-fork dance) is unreliable on the JVM and reinvents what every modern init system already provides. This matches `dockerd` (which is foreground; systemd manages it).
3. **In-memory strategy registry.** Daemon restart loses all registered strategies — user redeploys. Persistent registry on disk (replay on startup) is a separate phase. The honest tradeoff: simpler 12c, occasional re-deploy after restart. Documented.

---

## 2. Goals

- **`qkt daemon [--state-dir <path>] [--load-dir <path>]`** — runs a long-running daemon process in foreground. Allocates a TCP control port (kernel-ephemeral, default), writes `~/.local/state/qkt/control.port` (atomic), accepts deploy/list/stop/etc. via HTTP. With `--load-dir`, scans the dir for `*.qkt` and auto-deploys each one at startup.
- **`qkt deploy <file> [--as <name>]`** — registers a strategy with the running daemon. Each strategy gets its own coroutine, its own 12b `ObservabilityServer` on a kernel-ephemeral port, its own log file. Returns the assigned name + port.
- **`qkt list`** — tabular listing: NAME / UPTIME / PORT / TRADES / STATE.
- **`qkt logs <name> [-f|--follow] [--since <ts>]`** — tails the per-strategy log file.
- **`qkt status <name>`** — proxies to `http://127.0.0.1:<port>/status` on that strategy. `qkt status` (no arg) calls `/status` on every running strategy and prints a tabular roll-up.
- **`qkt stop <name>`** — graceful shutdown of one strategy. The strategy's `LiveSession` drains, its observability server closes, its log handle flushes.
- **`qkt daemon stop`** — gracefully terminate the running daemon. Stops every strategy first, then exits.
- **`qkt daemon status`** — health check on the running daemon. Prints PID, uptime, control port, registered strategy count.
- **Shared CandleHub at daemon scope.** Two strategies both want `(BYBIT, BTCUSDT, 1m)`? One aggregator, one history, one source subscription. The 11e CandleHub is per-`TradingPipeline` today; in 12c it lifts to the daemon. Memory + bandwidth dedup happens automatically.
- **Per-strategy log file** — `~/.local/state/qkt/logs/<name>.log`. SLF4J output redirected per strategy via a thread-local appender or per-coroutine context.
- **Dockerfile + base image.** Multi-stage build: stage 1 runs `./gradlew installDist`, stage 2 is `eclipse-temurin:21-jre` with the tarball. Image entrypoint is `qkt daemon`. Default `WORKDIR /strategies`. With `QKT_AUTOLOAD=/strategies`, the daemon auto-deploys every `.qkt` in the directory on startup.
- **CI publishes the image** to `ghcr.io/elitekaycy/qkt:<tag>` and `:latest` on git tag push.
- **Sample user Dockerfile** under `examples/docker/Dockerfile` showing how to extend the base image with a strategy directory.

## Non-goals

- **No persistent strategy registry.** Daemon restart drops every strategy. User must redeploy. Persistent state-on-disk is a separate phase.
- **No hot reload.** `qkt deploy <file> --as <name>` for an already-running name is rejected. User must `qkt stop <name>` first. Hot reload mid-position is too dangerous; the `LiveSession` rebuild needs explicit consent.
- **No multi-tenancy.** Daemon trusts everyone with local user access — same model as `dockerd` on a single-user box. Auth + role separation is a separate phase.
- **No log rotation.** Per-strategy log files are appended forever. Use `logrotate(8)` or a Docker log driver. Built-in rotation is post-12c.
- **No remote control plane.** Control port binds `127.0.0.1` only. `--bind 0.0.0.0` is rejected for the control plane (auth needed first). Observability ports per strategy can still be `--bind 0.0.0.0` (read-only, opt-in, same as 12b).
- **No `qkt restart <name>`.** Compose of `qkt stop` + `qkt deploy`. Trivial wrapper script; not worth a built-in command in 12c.
- **No clustering.** One daemon per machine. Multi-machine orchestration is out of scope (and is what Kubernetes is for if you actually need it).
- **No package-manager distribution** (Homebrew tap, apt, .deb). Phase 12d. Docker image + tarball release are the only delivery channels in 12c.
- **No daemon self-update.** Image is replaced via `docker pull && docker restart`; no in-process upgrade.
- **No Prometheus exposition format.** `/status` per strategy is JSON; users can compose with a Prometheus exporter at the container level.

---

## 3. Worked example

```
# Start the daemon in one terminal (or under systemd / Docker)
$ qkt daemon
[INFO] qkt 0.13.0 daemon starting
[INFO] state directory: /home/dickson/.local/state/qkt
[INFO] control plane: http://127.0.0.1:39201 (state file: /home/dickson/.local/state/qkt/control.port)
[INFO] daemon ready

# In another terminal, deploy strategies
$ qkt deploy strategies/ema-crossover.qkt --as ema-fast
NAME          PORT     STATE     STARTED
ema-fast      47291    running   2026-05-08T15:02:14Z

$ qkt deploy strategies/momentum-basket.qkt --as momentum
NAME          PORT     STATE     STARTED
momentum      47298    running   2026-05-08T15:02:21Z

$ qkt list
NAME          UPTIME   PORT     TRADES   STATE
ema-fast      00:00:42 47291    3        running
momentum      00:00:35 47298    7        running

$ qkt status ema-fast | jq '.equity'
9997.66

$ qkt logs ema-fast -f
2026-05-08T15:02:14Z [INFO] strategy ema-crossover starting
2026-05-08T15:02:18Z [INFO] indicator warmup complete (49 bars)
2026-05-08T15:02:23Z [INFO] BUY BTCUSDT qty=0.001 px=68234.50
^C

$ qkt stop ema-fast
[INFO] stopping ema-fast
[INFO] terminated; 3 trades

$ qkt list
NAME          UPTIME   PORT     TRADES   STATE
momentum      00:01:48 47298    11       running

$ qkt daemon stop
[INFO] stopping daemon
[INFO] gracefully stopping 1 strategy
[INFO] daemon stopped
```

### Docker workflow

```
$ docker pull ghcr.io/elitekaycy/qkt:0.13.0
$ docker run -d --name qkt-prop \
    -v $(pwd)/strategies:/strategies \
    -p 47000-47100:47000-47100 \
    ghcr.io/elitekaycy/qkt:0.13.0

$ docker exec qkt-prop qkt list
NAME          UPTIME   PORT     TRADES   STATE
ema-fast      00:00:42 47291    3        running
momentum      00:00:35 47298    7        running

$ docker exec qkt-prop qkt logs ema-fast -f

$ curl http://localhost:47291/status   # observability port mapped through Docker
```

User Dockerfile:

```dockerfile
FROM ghcr.io/elitekaycy/qkt:0.13.0
COPY strategies/*.qkt /strategies/
COPY qkt.config.yaml /etc/qkt/qkt.config.yaml
# CMD inherited from base: qkt daemon --load-dir /strategies
```

---

## 4. Architecture

### 4.1 The three sub-architectures in one diagram

```
                       ┌───────── qkt CLI (12c additions) ──────────┐
                       │                                             │
                       │  qkt daemon                                 │
                       │  qkt deploy / list / stop / logs / status   │
                       └─────────────────────┬───────────────────────┘
                                             │ HTTP over TCP localhost
                                             ▼
       ┌───────────────────────────── qkt daemon (single JVM) ──────────────────────────────┐
       │                                                                                    │
       │   ┌─────────────────────┐                                                          │
       │   │ Control plane HTTP  │  ← 127.0.0.1:<random>, port written to control.port      │
       │   │  POST /deploy       │                                                          │
       │   │  GET  /list         │                                                          │
       │   │  POST /stop/<name>  │                                                          │
       │   │  GET  /status[/n]   │                                                          │
       │   │  GET  /logs/<name>  │                                                          │
       │   │  POST /shutdown     │                                                          │
       │   └──────────┬──────────┘                                                          │
       │              │                                                                     │
       │              ▼                                                                     │
       │   ┌─────────────────────┐                                                          │
       │   │ StrategyRegistry    │  ← in-memory map: name → StrategyHandle                  │
       │   └──────────┬──────────┘                                                          │
       │              │                                                                     │
       │              ▼                                                                     │
       │   ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐       │
       │   │ Strategy 'ema-fast' │  │ Strategy 'momentum' │  │ Strategy 'gold-rev' │       │
       │   │  • LiveSession      │  │  • LiveSession      │  │  • LiveSession      │       │
       │   │  • EventRing        │  │  • EventRing        │  │  • EventRing        │       │
       │   │  • Observability    │  │  • Observability    │  │  • Observability    │       │
       │   │    HTTP :47291      │  │    HTTP :47298      │  │    HTTP :47305      │       │
       │   │  • LogAppender →    │  │  • LogAppender →    │  │  • LogAppender →    │       │
       │   │    ema-fast.log     │  │    momentum.log     │  │    gold-rev.log     │       │
       │   └──────────┬──────────┘  └──────────┬──────────┘  └──────────┬──────────┘       │
       │              │                        │                        │                  │
       │              └────────────────────────┼────────────────────────┘                  │
       │                                       ▼                                           │
       │                       ┌─────────────────────────────┐                             │
       │                       │ Shared CandleHub             │   ← 11e infrastructure,    │
       │                       │  (broker, sym, tf) → agg    │     hosted at daemon scope │
       │                       └─────────────────────────────┘                             │
       │                                       │                                           │
       │                                       ▼                                           │
       │                       ┌─────────────────────────────┐                             │
       │                       │ Shared MarketSource(s)      │                             │
       │                       │  TradingViewMarketSource    │                             │
       │                       │  (single websocket per src) │                             │
       │                       └─────────────────────────────┘                             │
       │                                                                                    │
       └────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Module placement

```
src/main/kotlin/com/qkt/cli/daemon/
├── Daemon.kt                     # process entry point, sets up state dir + control plane
├── StrategyRegistry.kt           # in-memory name → StrategyHandle map
├── StrategyHandle.kt             # one running strategy: LiveSession + ObservabilityServer + log appender
├── ControlPlane.kt               # HTTP server with deploy/list/stop/status routes
├── ControlClient.kt              # CLI-side: read control.port, hit endpoints, parse JSON
├── StateDir.kt                   # XDG-compliant state directory resolver
└── PerStrategyAppender.kt        # routes SLF4J-shaped log records into per-strategy files

src/main/kotlin/com/qkt/cli/
├── DaemonCommand.kt              # `qkt daemon [start|stop|status]`
├── DeployCommand.kt              # `qkt deploy <file>`
├── ListCommand.kt                # `qkt list`
├── LogsCommand.kt                # `qkt logs <name> [-f]`
├── StatusCommand.kt              # `qkt status [<name>]`
├── StopCommand.kt                # `qkt stop <name>` (different from POST /stop on observability port)
└── Main.kt                       # extended dispatch

Dockerfile                         # multi-stage build, daemon entrypoint
.github/workflows/docker.yml       # CI: build + push on tag

examples/docker/
├── Dockerfile                    # user template extending the base image
└── README.md                     # how-to

src/test/kotlin/com/qkt/cli/daemon/   # unit + integration tests for the daemon machinery
```

### 4.3 Daemon lifecycle

```kotlin
fun runDaemon(args: Args): Int {
    val stateDir = StateDir.resolve(args.option("state-dir"))
    Files.createDirectories(stateDir.logsDir)

    val registry = StrategyRegistry()
    val controlPlane = ControlPlane(
        registry = registry,
        bind = "127.0.0.1",
        port = 0,  // kernel-ephemeral
    )
    controlPlane.start()
    stateDir.writeControlPort(controlPlane.boundPort)
    println("[INFO] qkt ${BuildInfo.VERSION} daemon starting")
    println("[INFO] state directory: ${stateDir.root}")
    println("[INFO] control plane: http://127.0.0.1:${controlPlane.boundPort}")

    val autoloadDir = args.option("load-dir")?.let(Path::of)
    if (autoloadDir != null && Files.isDirectory(autoloadDir)) {
        Files.list(autoloadDir).filter { it.toString().endsWith(".qkt") }.forEach { file ->
            registry.deploy(file, name = file.fileName.toString().removeSuffix(".qkt"))
        }
    }

    println("[INFO] daemon ready")

    val stopLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        println("[INFO] stopping daemon")
        registry.stopAll()
        controlPlane.close()
        stateDir.deleteControlPort()
        stopLatch.countDown()
    })
    stopLatch.await()
    return ExitCodes.SUCCESS
}
```

### 4.4 Per-strategy hosting

```kotlin
class StrategyHandle(
    val name: String,
    val ast: StrategyAst,
    val liveSession: LiveSessionHandle,
    val observability: ObservabilityServer,
    val logFile: Path,
    val startedAt: Instant,
) : AutoCloseable {
    val port: Int get() = observability.boundPort
    val tradeCount: Int get() = /* from ring or session counter */

    override fun close() {
        liveSession.stop()
        observability.close()
        // log file handle flushed by appender on close
    }
}
```

`StrategyRegistry.deploy(file, name)` parses the file, compiles the AST, constructs a `LiveSession`, starts an `ObservabilityServer` on port 0, registers a per-strategy log appender, and stores the handle keyed by name.

### 4.5 Shared CandleHub at daemon scope

11e's `CandleHub` is currently per-`TradingPipeline`. In 12c, the daemon owns one hub and passes a reference to each strategy's `LiveSession`. A strategy's `declaredStreams` register their keys with the hub on deploy; when the strategy stops, the keys are *not* unregistered (the hub size grows over the daemon lifetime; each key's retention is `max` over all consumers). This is the simple shape; per-key reference-counting is a polish for a future phase.

### 4.6 Per-strategy log appender

SLF4J + `logback` (or `slf4j-simple`) doesn't natively support per-strategy file routing. Options:

- **A. Custom appender** — implement an `Appender` that consults a thread-local "current strategy" set on coroutine entry. SLF4J calls flow through it.
- **B. Per-strategy logger names** — every strategy uses a unique logger name (e.g. `com.qkt.dsl.strategy.<name>`). Logback config routes by logger name to per-name files. No code change beyond ensuring strategies use that naming scheme.
- **C. Stream interception** — capture stdout/stderr per strategy via a thread-local `PrintStream` swap. Heavy-handed.

**Decision: B.** The DSL `AstCompiler` already creates a logger named `com.qkt.dsl.strategy.${ast.name}` (verified in 11e's CompiledStrategy code). Logback configuration with a `SiftingAppender` keyed off the logger name routes each strategy's output to its own file. Zero code change in the engine; one logback config addition. We swap `slf4j-simple` for `logback-classic` in the daemon runtime classpath.

This means: **logback-classic becomes a runtime dep when the daemon is in play**. Test/non-daemon paths continue to use `slf4j-simple` (current). The CLI's existing `qkt run` keeps `slf4j-simple` via `runtimeOnly`; only the daemon image bundles logback.

---

## 5. CLI surface

### 5.1 `qkt daemon [subcommand] [flags]`

| Subcommand | Description |
|---|---|
| `qkt daemon` (no subcommand) | start in foreground |
| `qkt daemon stop` | tell running daemon to shut down |
| `qkt daemon status` | print PID, uptime, control port, strategy count |

**Flags on `qkt daemon` (start mode):**

| Flag | Default | Description |
|---|---|---|
| `--state-dir <path>` | `$XDG_STATE_HOME/qkt` or `~/.local/state/qkt` | Where to write `control.port`, log files, etc. |
| `--load-dir <path>` | (off) | Auto-deploy every `*.qkt` file in this dir on startup. |
| `--control-port <num>` | `0` | Bind the control plane to a specific port. `0` = kernel-ephemeral. |
| `--config <path>` | `./qkt.config.yaml` | Inherits 12a's config. |

### 5.2 `qkt deploy <file> [flags]`

| Flag | Default | Description |
|---|---|---|
| `--as <name>` | (file basename without `.qkt`) | Identifier the daemon uses for this strategy. Must be unique. |

Output (text or `--json`):

```
NAME          PORT     STATE     STARTED
ema-fast      47291    running   2026-05-08T15:02:14Z
```

Errors:
- Daemon not running → `qkt: error: no daemon running (no control.port file at <state-dir>)`. Exit 1.
- Name already in use → `qkt: error: 'ema-fast' is already deployed; stop it first with 'qkt stop ema-fast'`. Exit 1.
- Parse failure → propagated from `Dsl.parseFile`. Exit 1.

### 5.3 `qkt list`

| Flag | Default | Description |
|---|---|---|
| `--json` | (off) | Machine-readable output. |

Default text output:

```
NAME          UPTIME   PORT     TRADES   STATE
ema-fast      00:00:42 47291    3        running
momentum      00:00:35 47298    7        running
```

### 5.4 `qkt logs <name> [flags]`

| Flag | Default | Description |
|---|---|---|
| `-f` / `--follow` | (off) | Stream new log lines as they arrive (`tail -f` style). |
| `--since <ts>` | (off) | Filter to lines after this ISO timestamp. |
| `--lines <n>` | `200` | How many trailing lines to print before following / exiting. |

`qkt logs` reads the per-strategy log file directly via the control plane (`GET /logs/<name>`). With `-f`, the control plane keeps the connection open and streams new lines.

### 5.5 `qkt status [<name>] [flags]`

`qkt status <name>` proxies through the control plane to that strategy's observability port `/status`. Returns the same JSON shape as 12b.

`qkt status` (no name) calls `/status` on every running strategy and prints a tabular roll-up. With `--json`, emits a JSON array.

### 5.6 `qkt stop <name>`

Sends `POST /stop/<name>` to the control plane. Daemon proxies to the strategy's `LiveSession.stop()` and removes the handle from the registry. Returns when the strategy is fully drained or after a 5s timeout.

| Flag | Default | Description |
|---|---|---|
| `--flatten` | (off) | Flatten open positions before stopping. |
| `--timeout <ms>` | `5000` | Max wait for graceful drain. |

---

## 6. State directory layout

```
~/.local/state/qkt/                         # XDG_STATE_HOME-rooted, override with --state-dir or QKT_STATE_DIR
├── control.port                            # bound TCP port of the control plane (text, single line, mode 0600)
├── daemon.pid                              # daemon's PID, useful for "is it running" checks (mode 0644)
└── logs/
    ├── ema-fast.log                        # one file per registered strategy
    ├── momentum.log
    └── ...
```

`StateDir.writeControlPort(port)` writes via the same temp+rename atomic write pattern as 12b's `--port-file`. `StateDir.readControlPort()` (used by the CLI) returns `null` if the file is missing — no daemon running.

**Cleanup:** the daemon deletes `control.port` and `daemon.pid` on graceful shutdown. Stale files after a hard crash are tolerated — `StateDir.readControlPort()` validates with a `GET /health` probe before trusting the port.

---

## 7. Docker

### 7.1 `Dockerfile` at repo root

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre AS runtime
RUN useradd -r -m -s /usr/sbin/nologin qkt
COPY --from=build /src/build/install/qkt /opt/qkt
ENV PATH="/opt/qkt/bin:$PATH"
ENV QKT_STATE_DIR=/var/lib/qkt
RUN mkdir -p /var/lib/qkt /strategies && chown -R qkt:qkt /var/lib/qkt /strategies
USER qkt
WORKDIR /strategies
EXPOSE 40000-50000
ENTRYPOINT ["qkt", "daemon", "--load-dir", "/strategies"]
```

### 7.2 CI: `.github/workflows/docker.yml`

Triggers on git tag push (`v*`). Steps:

1. Checkout.
2. Login to ghcr.io with `${{ secrets.GITHUB_TOKEN }}`.
3. `docker buildx build` with `--push` and tags `ghcr.io/elitekaycy/qkt:<tag>` and `:latest`.
4. (Optional, skipped in 12c) Multi-arch manifest (`linux/amd64,linux/arm64`).

Single-arch (amd64) is the 12c default. Multi-arch is a polish.

### 7.3 Sample user Dockerfile

`examples/docker/Dockerfile`:

```dockerfile
FROM ghcr.io/elitekaycy/qkt:0.13.0
COPY strategies/ /strategies/
COPY qkt.config.yaml /etc/qkt/qkt.config.yaml
ENV QKT_CONFIG=/etc/qkt/qkt.config.yaml
# CMD inherited: qkt daemon --load-dir /strategies
```

`examples/docker/README.md` walks through `docker build`, `docker run`, `docker exec qkt list`, port mapping, log volume mounting.

### 7.4 Local image build verification

`./gradlew dockerBuild` (new Gradle task) runs `docker build .` and tags `qkt:local`. Used by the distribution test (Phase 12c Task N) to verify the image starts and `qkt --version` works inside it.

---

## 8. Testing strategy

Per qkt convention: real types, no mocks, JUnit 5 + AssertJ.

- **`StateDirTest`** — atomic write of `control.port`, read returns the port, missing file returns null.
- **`StrategyRegistryTest`** — deploy/stop/list lifecycle. Duplicate name rejection. `stopAll` drains everything.
- **`StrategyHandleTest`** — handle wires `LiveSession` + `ObservabilityServer` + log appender; close drains all three.
- **`ControlPlaneTest`** — boot a control plane on `port=0`, hit each endpoint with `OkHttpClient`, assert response shape and registry side effects.
- **`ControlClientTest`** — read `control.port` from a fixture state dir, confirm the client builds the right URL and parses JSON responses.
- **`PerStrategyAppenderTest`** — deploy two strategies, assert each strategy's log file contains only that strategy's entries (smoke test for logback `SiftingAppender` config).
- **`SharedCandleHubTest`** — deploy two strategies that both reference `(BACKTEST, BTCUSDT, 1m)`; assert one aggregator at the hub level, both strategies see the same candles.
- **`DaemonEndToEndTest`** — boot `Daemon.runDaemon(...)` on a background thread; from the test thread, exercise the full `qkt deploy / list / stop / daemon stop` flow via `ControlClient`. In-process; no real fork.
- **`DockerImageTest`** — gated behind a `dockerSmoke` JUnit tag (skipped on CI by default). When enabled, runs `./gradlew dockerBuild`, then `docker run --rm qkt:local --version`, asserts exit 0 + version match.

---

## 9. Risk

**Risk: Medium-High.** This is the largest 12-series phase. Three sub-systems (daemon, multi-strategy hosting, Docker) and a dep change (logback). Mitigations:

- **Phased decomposition.** The plan splits into ~25 tasks, each ≤4 LoC of public API surface or one file. Daemon machinery before observability changes; Docker last.
- **Reuse of existing infrastructure.** `LiveSession` (12a), `ObservabilityServer` (12b), `EventRing` (12b), `CandleHub` (11e). 12c is wiring + lifecycle, not new core logic.
- **In-memory registry** (no persistence) keeps the failure surface bounded — daemon crashes drop everything, but state corruption is impossible.

**Risk: logback dep + per-strategy log routing.** The `SiftingAppender` configuration must thread the strategy name through MDC (Mapped Diagnostic Context). If MDC isn't set at the right point, all strategies log to the wrong file. Mitigated by:
- A single `MDC.put("strategy", name)` call inside `StrategyHandle`'s entry point + `MDC.clear()` on exit.
- `PerStrategyAppenderTest` verifies isolation explicitly.

**Risk: Shared CandleHub key leakage across strategy lifecycle.** Stopping a strategy doesn't remove its hub registrations; a long-running daemon accumulates keys forever. Mitigation: documented limitation in 12c; per-key ref-counting added in a follow-up phase.

**Risk: TCP-localhost control plane is more permissive than a Unix socket.** Any local user can hit `127.0.0.1:<port>`. Mitigation: same-machine trust is the assumed model; the alternative (Unix socket) costs disproportionate complexity for the value. For multi-user hosts, document that the daemon should run inside Docker with proper user isolation.

**Risk: Docker build size.** Multi-stage build with `eclipse-temurin:21-jre` is ~250MB compressed. Acceptable for a JVM-based product. Smaller alternatives (`distroless`, JLink) are post-12c polish.

---

## 10. Phase decomposition (preview for the plan)

Approximately 25 tasks, ordered to ship pieces incrementally.

**Daemon foundations (Tasks 1-6)**

1. `StateDir` resolver (XDG-compliant, atomic port file writes).
2. `StrategyRegistry` + `StrategyHandle` (in-memory, no HTTP yet).
3. `ControlPlane` skeleton + `GET /health` route.
4. `POST /deploy` route + `DeployCommand` CLI subcommand.
5. `GET /list` route + `ListCommand`.
6. `POST /stop/<name>` route + `StopCommand`.

**Daemon lifecycle (Tasks 7-9)**

7. `qkt daemon` foreground entry point + shutdown hook.
8. `qkt daemon stop` + `qkt daemon status`.
9. `--load-dir <path>` auto-deployment.

**Per-strategy logging (Tasks 10-13)**

10. Add `logback-classic` runtime dep; configure `SiftingAppender` with MDC-keyed strategy name.
11. `MDC.put("strategy", name)` plumbing in `StrategyHandle`.
12. `GET /logs/<name>` route serving the per-strategy log file.
13. `qkt logs <name> [-f] [--since] [--lines]` CLI.

**Multi-strategy status (Tasks 14-15)**

14. `GET /status[/<name>]` proxies to per-strategy observability ports.
15. `qkt status [<name>]` CLI.

**Shared CandleHub at daemon scope (Tasks 16-17)**

16. Lift `CandleHub` ownership from per-`TradingPipeline` to the daemon. `StrategyHandle` gets a hub reference instead of constructing its own.
17. Test: two strategies sharing a hub key receive identical candle streams from a single aggregator.

**End-to-end + Docker (Tasks 18-23)**

18. `DaemonEndToEndTest` full flow.
19. `Dockerfile` (multi-stage build, runtime user, EXPOSE).
20. Local `./gradlew dockerBuild` task + smoke test (`dockerSmoke` JUnit tag).
21. `examples/docker/Dockerfile` + README.
22. `.github/workflows/docker.yml` — build + push to `ghcr.io/elitekaycy/qkt:<tag>` on git tag.
23. `BuildInfo.VERSION` bump to `0.13.0`.

**Polish (Tasks 24-25)**

24. Phase 12c changelog under `docs/phases/`.
25. Update `README.md` quick-start to lead with Docker, foreground `qkt run`, and daemon-mode side-by-side.

---

## 11. Out of scope (explicit)

- **Persistent strategy registry.** Restart loses everything.
- **Hot reload.** `qkt deploy` for an existing name = error.
- **Auth on the control plane.** Localhost-trust only.
- **Multi-arch Docker manifest.** amd64 only in 12c.
- **Log rotation.** `logrotate(8)` is the user's tool.
- **`qkt restart`.** Compose `stop` + `deploy` from a wrapper.
- **Clustering / multi-machine.** Out of scope; use Kubernetes.
- **Package-manager distribution.** Phase 12d.
- **Daemon-side metrics** (`/metrics` Prometheus). Polish.
- **Hub key ref-counting.** Hub grows over daemon lifetime. Polish.
- **In-process JVM upgrade.** Replace the image, restart the container.

---

## 12. References

- Spec for predecessor (Phase 12b): `docs/superpowers/specs/2026-05-08-trading-engine-phase12b-design.md`
- Phase 12a (CLI binary): `docs/superpowers/specs/2026-05-08-trading-engine-phase12a-design.md`
- Phase 11e (CandleHub, multi-stream): `docs/superpowers/specs/2026-05-08-trading-engine-phase11e-design.md`
- Master spec (Phase 12 roadmap): `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §10.
- Phase 8 (LiveSession): `docs/superpowers/specs/2026-05-06-trading-engine-phase8-design.md`
- XDG Base Directory Specification: <https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html>
- SLF4J `MDC` + logback `SiftingAppender`: <https://logback.qos.ch/manual/appenders.html#SiftingAppender>
