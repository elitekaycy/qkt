# Phase 12c — Daemon, Multi-Strategy Hosting, Docker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the daemon-shape vision: `qkt daemon` hosts many strategies, `qkt deploy/list/logs/status/stop` address them by name, all packaged in a Docker base image at `ghcr.io/elitekaycy/qkt:<tag>` published on git tag. Daemon control plane is HTTP over TCP localhost (port written to `~/.local/state/qkt/control.port`). Per-strategy logs routed via logback `SiftingAppender` keyed off MDC strategy name. Shared CandleHub at daemon scope so strategies on the same `(broker, symbol, timeframe)` key dedupe automatically.

**Architecture:** New package `com.qkt.cli.daemon` (StateDir, StrategyRegistry, StrategyHandle, ControlPlane, ControlClient, PerStrategyAppender). New CLI subcommands (`daemon`, `deploy`, `list`, `logs`, `status`, `stop`) wire into 12a's `Main`. `logback-classic` becomes a runtime dep for the daemon's per-strategy log routing. Multi-stage `Dockerfile` at repo root + GitHub Actions workflow at `.github/workflows/docker.yml`. CI publishes to ghcr on tag.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. One new runtime dep: `ch.qos.logback:logback-classic` (~700KB). One new tooling dep: Docker (CI + local).

**Spec:** `docs/superpowers/specs/2026-05-08-trading-engine-phase12c-design.md`.

**Branch:** `phase12c-daemon` — cut from `main` at start of Task 1.

---

## Design notes

### Module placement

```
src/main/kotlin/com/qkt/cli/daemon/
├── StateDir.kt                    # XDG-compliant state directory + atomic port file
├── StrategyHandle.kt              # one running strategy: LiveSession + ObservabilityServer + log appender
├── StrategyRegistry.kt            # in-memory name → StrategyHandle map
├── ControlPlane.kt                # HTTP server with deploy/list/stop/status/logs/health/shutdown routes
├── ControlClient.kt               # CLI-side client: read control.port, hit endpoints, parse JSON
├── ControlRoutes.kt               # per-route handlers
└── PerStrategyAppender.kt         # logback config helper (or just provide the XML config)

src/main/kotlin/com/qkt/cli/
├── DaemonCommand.kt               # qkt daemon [start|stop|status]
├── DeployCommand.kt
├── ListCommand.kt
├── LogsCommand.kt
├── StatusCommand.kt
├── StopCommand.kt
└── Main.kt                        # extended dispatch

src/main/resources/
└── logback.xml                    # SiftingAppender keyed off MDC.strategy

Dockerfile                          # multi-stage, daemon entrypoint
.github/workflows/docker.yml        # CI: build + push on tag
examples/docker/Dockerfile          # user template
examples/docker/README.md

src/test/kotlin/com/qkt/cli/daemon/
├── StateDirTest.kt
├── StrategyRegistryTest.kt
├── StrategyHandleTest.kt
├── ControlPlaneTest.kt
├── ControlClientTest.kt
├── PerStrategyAppenderTest.kt
├── SharedCandleHubTest.kt
└── DaemonEndToEndTest.kt
```

### Control plane endpoints

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/health` | — | `{"status":"ok","strategies":<count>,"uptimeMs":<n>}` |
| POST | `/deploy` | `{"file":"...","name":"..."}` | `{"name":"...","port":47291,"state":"running","startedAt":"..."}` |
| GET | `/list` | — | `[{"name":"...","port":...,"trades":...,"uptimeMs":...,"state":"..."},...]` |
| GET | `/status` | — | array of all strategy snapshots (composing each strategy's `/status` from 12b) |
| GET | `/status/<name>` | — | proxies to `http://127.0.0.1:<port>/status` |
| GET | `/logs/<name>?lines=<n>&since=<ts>&follow=<bool>` | — | text/plain (or chunked text/event-stream if `follow=true`) |
| POST | `/stop/<name>?flatten=<bool>&timeout=<ms>` | — | `{"name":"...","state":"stopped","trades":<n>}` |
| POST | `/shutdown` | — | `{"status":"accepted"}` then daemon exits |

All control plane endpoints are reachable only via 127.0.0.1.

### Existing infrastructure reused

- `com.qkt.cli.observe.ObservabilityServer` (12b) — one per strategy.
- `com.qkt.cli.observe.EventRing` (12b) — one per strategy.
- `com.qkt.app.LiveSession` (12a + 12b) — one per strategy.
- `com.qkt.dsl.compile.CandleHub` (11e) — **lifted** to daemon scope.
- `com.qkt.dsl.parse.Dsl.parseFile` (11f) — used by `POST /deploy`.
- `com.qkt.cli.Args`, `Main`, `ExitCodes` (12a) — extended.

### State directory layout (XDG)

```
$XDG_STATE_HOME/qkt/  (default ~/.local/state/qkt/)
├── control.port             # text, single line, mode 0600
├── daemon.pid               # text, single line
└── logs/
    ├── <name>.log           # one file per registered strategy
    └── ...
```

`StateDir.resolve(override?)` consults `--state-dir`, `QKT_STATE_DIR`, `$XDG_STATE_HOME`, `~/.local/state/qkt/` in that order.

---

## Tasks

### Task 1: `StateDir` resolver + atomic port file

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt`
- Create: `src/test/kotlin/com/qkt/cli/daemon/StateDirTest.kt`

- [ ] **Step 1: Cut branch**

```bash
git checkout -b phase12c-daemon
```

- [ ] **Step 2: Failing test**

```kotlin
package com.qkt.cli.daemon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StateDirTest {
    @Test
    fun `resolve uses override when given`(@TempDir tmp: Path) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.root).isEqualTo(tmp)
        assertThat(dir.logsDir).isEqualTo(tmp.resolve("logs"))
    }

    @Test
    fun `writeControlPort then readControlPort round-trips`(@TempDir tmp: Path) {
        val dir = StateDir.resolve(tmp.toString())
        dir.writeControlPort(47291)
        assertThat(dir.readControlPort()).isEqualTo(47291)
    }

    @Test
    fun `readControlPort returns null when absent`(@TempDir tmp: Path) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.readControlPort()).isNull()
    }

    @Test
    fun `deleteControlPort removes the file`(@TempDir tmp: Path) {
        val dir = StateDir.resolve(tmp.toString())
        dir.writeControlPort(47291)
        dir.deleteControlPort()
        assertThat(dir.readControlPort()).isNull()
    }
}
```

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class StateDir private constructor(val root: Path) {
    val logsDir: Path = root.resolve("logs")
    val controlPortFile: Path = root.resolve("control.port")
    val pidFile: Path = root.resolve("daemon.pid")

    init {
        Files.createDirectories(root)
        Files.createDirectories(logsDir)
    }

    fun writeControlPort(port: Int) {
        val tmp = controlPortFile.resolveSibling("control.port.tmp")
        Files.writeString(tmp, port.toString())
        Files.move(tmp, controlPortFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun readControlPort(): Int? {
        if (!Files.exists(controlPortFile)) return null
        return runCatching { Files.readString(controlPortFile).trim().toInt() }.getOrNull()
    }

    fun deleteControlPort() {
        Files.deleteIfExists(controlPortFile)
    }

    fun logFile(name: String): Path = logsDir.resolve("$name.log")

    companion object {
        fun resolve(override: String? = null): StateDir {
            val root = when {
                override != null -> Path.of(override)
                System.getenv("QKT_STATE_DIR") != null -> Path.of(System.getenv("QKT_STATE_DIR"))
                System.getenv("XDG_STATE_HOME") != null -> Path.of(System.getenv("XDG_STATE_HOME"), "qkt")
                else -> Path.of(System.getProperty("user.home"), ".local", "state", "qkt")
            }
            return StateDir(root)
        }
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.daemon.StateDirTest
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/cli/daemon/ src/test/kotlin/com/qkt/cli/daemon/
git commit -m "feat(cli): StateDir with atomic control-port write"
```

---

### Task 2: `StrategyRegistry` + `StrategyHandle` (in-memory, no HTTP)

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`
- Create: `src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt`
- Create: `src/test/kotlin/com/qkt/cli/daemon/StrategyRegistryTest.kt`

- [ ] **Step 1: Tests** for deploy + list + stop + duplicate-name rejection. Use a fake `StrategyHandle.Factory` so the test doesn't actually start a websocket / observability server — pure registry semantics.

- [ ] **Step 2: Implement `StrategyHandle`**

```kotlin
package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.dsl.ast.StrategyAst
import java.nio.file.Path
import java.time.Instant

class StrategyHandle(
    val name: String,
    val ast: StrategyAst,
    val live: LiveSessionHandle,
    val observability: ObservabilityServer,
    val ring: EventRing,
    val logFile: Path,
    val startedAt: Instant,
) : AutoCloseable {
    val port: Int get() = observability.boundPort
    val tradeCount: Int get() = ring.size()

    fun isRunning(): Boolean = live.isRunning()

    override fun close() {
        live.stop()
        observability.close()
    }
}
```

- [ ] **Step 3: Implement `StrategyRegistry`**

```kotlin
package com.qkt.cli.daemon

import java.util.concurrent.ConcurrentHashMap

class StrategyRegistry(
    private val factory: StrategyHandle.Factory,
) {
    private val handles = ConcurrentHashMap<String, StrategyHandle>()

    fun deploy(name: String, file: java.nio.file.Path): StrategyHandle {
        require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "invalid strategy name: $name" }
        check(name !in handles) { "strategy '$name' already deployed" }
        val handle = factory.create(name, file)
        handles[name] = handle
        return handle
    }

    fun stop(name: String): Boolean {
        val h = handles.remove(name) ?: return false
        h.close()
        return true
    }

    fun get(name: String): StrategyHandle? = handles[name]
    fun list(): List<StrategyHandle> = handles.values.toList()

    fun stopAll() {
        for (h in handles.values) runCatching { h.close() }
        handles.clear()
    }
}
```

`StrategyHandle.Factory` is an interface so tests can substitute. Production factory wires up everything in Task 5; for Task 2 just define the interface and a fake.

- [ ] **Step 4: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.daemon.StrategyRegistryTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(cli): StrategyRegistry and StrategyHandle skeletons"
```

---

### Task 3: `ControlPlane` skeleton + `/health`

Mirror 12b's `ObservabilityServer` shape, but at daemon scope.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/ControlPlane.kt`
- Create: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Create: `src/test/kotlin/com/qkt/cli/daemon/ControlPlaneTest.kt`

- [ ] **Step 1: Tests** for `GET /health` returning 200 with strategy count + uptime. Unknown route → 404.

- [ ] **Step 2: Implement `ControlPlane`** (jdk.httpserver bound 127.0.0.1:0; routes registered via `ControlRoutes`).

- [ ] **Step 3: Implement `ControlRoutes.health(registry, startedAt)`**.

- [ ] **Step 4: Run + commit:** `feat(cli): ControlPlane skeleton with /health route`.

---

### Task 4: `POST /deploy` + `DeployCommand`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Create: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`
- Create: `src/main/kotlin/com/qkt/cli/DeployCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt` (add `deploy` dispatch)
- Create: `src/test/kotlin/com/qkt/cli/daemon/DeployCommandTest.kt`

- [ ] **Step 1: Tests** for `/deploy` accepting `{file, name}`, returning JSON, 400 on bad input, 409 on duplicate, 500 on parse failure.

- [ ] **Step 2: Implement `ControlClient`** — reads `StateDir.readControlPort()`, raises a clear error if no daemon running, exposes `deploy(name, file)` + `list()` + `stop(name)` + `status(name?)` + `logs(name, params)`. Uses OkHttp.

- [ ] **Step 3: Implement `DeployCommand`** — parses `<file> [--as <name>]`, calls `ControlClient.deploy`, prints tabular result (or `--json`).

- [ ] **Step 4: Wire into `Main`** dispatch: `"deploy" -> DeployCommand(args).run()`.

- [ ] **Step 5: Commit:** `feat(cli): qkt deploy and POST /deploy route`.

---

### Task 5: Production `StrategyHandle.Factory`

Builds a real `StrategyHandle` by parsing + compiling + starting a `LiveSession` + `ObservabilityServer` + per-strategy log file. Used by the daemon at runtime.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`
- Modify: `src/test/kotlin/com/qkt/cli/daemon/StrategyHandleTest.kt`

- [ ] **Step 1: Implement `StrategyHandle.RealFactory(stateDir, marketSourceProvider, candleHub)`** — wires existing `Dsl.parseFile → AstCompiler → LiveSession + ObservabilityServer + EventRing`. Mirrors `RunCommand`'s setup minus the SIGINT hook (daemon owns the lifecycle).

- [ ] **Step 2: Tests** that the factory produces a handle whose port is non-zero, ring is wired to receive trade events, log file exists at `<state>/logs/<name>.log`.

- [ ] **Step 3: Commit:** `feat(cli): production StrategyHandle factory`.

---

### Task 6: `GET /list` + `ListCommand`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Create: `src/main/kotlin/com/qkt/cli/ListCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`

- [ ] **Step 1: Tests** for `/list` returning a JSON array. After two deploys via the registry, list returns both names with non-overlapping ports.

- [ ] **Step 2: Implement** route + CLI command. Default text output is tabular; `--json` emits the JSON array verbatim.

- [ ] **Step 3: Commit:** `feat(cli): qkt list and GET /list route`.

---

### Task 7: `POST /stop/<name>` + `StopCommand`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Create: `src/main/kotlin/com/qkt/cli/StopCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`

- [ ] **Step 1: Tests** — POST `/stop/foo` for known name returns 200 + `{"name":"foo","state":"stopped"}`. Unknown name returns 404. Optional `?flatten=true` and `?timeout=<ms>` query params.

- [ ] **Step 2: Implement** route (path-param parsing) + CLI.

- [ ] **Step 3: Commit:** `feat(cli): qkt stop and POST /stop/<name> route`.

---

### Task 8: `qkt daemon` foreground entry point

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`
- Create: `src/test/kotlin/com/qkt/cli/daemon/DaemonStartupTest.kt`

- [ ] **Step 1: Implement `DaemonCommand`** — sets up `StateDir`, constructs `CandleHub` (placeholder until Task 16 lifts it), constructs `StrategyHandle.RealFactory`, constructs `StrategyRegistry`, starts `ControlPlane`, writes `control.port`, prints the `[INFO]` lines from spec §3, parks on a `CountDownLatch` until shutdown hook fires.

- [ ] **Step 2: Test** — boot `DaemonCommand(args).run()` on a background thread, wait for `control.port` file to appear, hit `/health` via OkHttp, assert 200 + `{"status":"ok"}`. Trigger graceful shutdown via `Runtime.exit` simulation or by closing the registry directly.

- [ ] **Step 3: Commit:** `feat(cli): qkt daemon foreground entry point`.

---

### Task 9: `qkt daemon stop` + `qkt daemon status`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt` (add `POST /shutdown` and use `/health` for status)
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt` (sub-subcommand dispatch: `stop`, `status`)
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`

- [ ] **Step 1: Tests** — `qkt daemon stop` POSTs to `/shutdown`, daemon process exits cleanly with 0. `qkt daemon status` calls `/health`, prints PID + uptime + port + strategy count.

- [ ] **Step 2: Implement.**

- [ ] **Step 3: Commit:** `feat(cli): qkt daemon stop and status subcommands`.

---

### Task 10: `--load-dir` auto-deployment

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`
- Modify: `src/test/kotlin/com/qkt/cli/daemon/DaemonStartupTest.kt`

- [ ] **Step 1: Test** — fixture dir with two `.qkt` files; `qkt daemon --load-dir <dir>` registers both at startup. `/list` returns both.

- [ ] **Step 2: Implement** — after `ControlPlane` starts, scan the dir, call `registry.deploy(name, file)` for each.

- [ ] **Step 3: Commit:** `feat(cli): qkt daemon --load-dir auto-deployment`.

---

### Task 11: Add `logback-classic` runtime dep + `logback.xml` SiftingAppender

**Files:**
- Modify: `gradle/libs.versions.toml` (add logback version)
- Modify: `build.gradle.kts` (add `runtimeOnly(libs.logback.classic)` — replaces or augments `slf4j-simple`)
- Create: `src/main/resources/logback.xml`

- [ ] **Step 1: Add dep**

```toml
[versions]
logback = "1.5.6"

[libraries]
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
```

`build.gradle.kts`:

```kotlin
runtimeOnly(libs.logback.classic)
// keep slf4j-simple OR remove; logback-classic implements slf4j too. Choose one.
```

**Decision: remove `slf4j-simple`.** Logback is a full-featured backend; running both is redundant and confusing.

- [ ] **Step 2: Logback config**

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="STRATEGY_FILE" class="ch.qos.logback.classic.sift.SiftingAppender">
        <discriminator>
            <key>strategy</key>
            <defaultValue>main</defaultValue>
        </discriminator>
        <sift>
            <appender name="FILE-${strategy}" class="ch.qos.logback.core.FileAppender">
                <file>${QKT_STATE_DIR:-${user.home}/.local/state/qkt}/logs/${strategy}.log</file>
                <encoder>
                    <pattern>%d{ISO8601} [%level] %msg%n</pattern>
                </encoder>
                <append>true</append>
            </appender>
        </sift>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <appender-ref ref="STRATEGY_FILE"/>
    </root>
</configuration>
```

- [ ] **Step 3: Verify** existing tests still pass (slf4j-simple removal).

- [ ] **Step 4: Commit:** `build(cli): swap slf4j-simple for logback-classic with SiftingAppender`.

---

### Task 12: MDC plumbing in `StrategyHandle`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`
- Create: `src/test/kotlin/com/qkt/cli/daemon/PerStrategyAppenderTest.kt`

- [ ] **Step 1: Test** — deploy two strategies, call `org.slf4j.LoggerFactory.getLogger("com.qkt.dsl.strategy.<name>").info("hello from <name>")` from a thread that has `MDC.put("strategy", name)` set. Assert each strategy's log file at `<state>/logs/<name>.log` contains exactly its own line.

- [ ] **Step 2: Implement** — `StrategyHandle.RealFactory` wraps the strategy's `LiveSession` start/stop in `MDC.put("strategy", name)` / `MDC.clear()`. Likely needs a coroutine context wrapper so MDC propagates across context switches inside the LiveSession.

```kotlin
private fun runWithMdc(name: String, block: () -> Unit) {
    org.slf4j.MDC.put("strategy", name)
    try { block() } finally { org.slf4j.MDC.clear() }
}
```

For coroutine-based LiveSessions, use the `MDCContext` element from `kotlinx-coroutines-slf4j` — adds a tiny dep. **Decision**: avoid the new dep; instead set MDC at every callback entry point (`onTrade`, `onSignal`) and at `LiveSession.start`. Verbose but no new dep.

- [ ] **Step 3: Commit:** `feat(cli): per-strategy MDC plumbing for log routing`.

---

### Task 13: `GET /logs/<name>` route + `qkt logs` CLI

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`
- Create: `src/main/kotlin/com/qkt/cli/LogsCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`

- [ ] **Step 1: Tests** — `/logs/foo?lines=10` returns the last 10 lines of `<state>/logs/foo.log` as `text/plain`. `?since=<iso>` filters. `?follow=true` streams via chunked transfer (use the same SSE-ish pattern as 12b's `/events` but with newline-framed text).

- [ ] **Step 2: Implement** route (file read, query parsing, follow-streaming via `Files.newBufferedReader` on a polling loop).

- [ ] **Step 3: Implement `LogsCommand`** with `-f / --follow / --since / --lines` flags.

- [ ] **Step 4: Commit:** `feat(cli): qkt logs and GET /logs/<name> route`.

---

### Task 14: `GET /status[/<name>]` proxy + `StatusCommand`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`
- Create: `src/main/kotlin/com/qkt/cli/StatusCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`

- [ ] **Step 1: Tests** — `/status/foo` proxies to that strategy's observability port `/status`, returns the same JSON. `/status` (no arg) iterates all registered strategies and returns an array.

- [ ] **Step 2: Implement** — daemon-side proxy uses an internal OkHttpClient to call the per-strategy observability endpoint and stream the response back.

- [ ] **Step 3: CLI** with optional name arg + `--json`.

- [ ] **Step 4: Commit:** `feat(cli): qkt status and GET /status routes`.

---

### Task 15: Lift `CandleHub` to daemon scope

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` (accept an externally-provided `CandleHub`)
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt` (construct one hub, pass to factory)
- Create: `src/test/kotlin/com/qkt/cli/daemon/SharedCandleHubTest.kt`

- [ ] **Step 1: Test** — deploy two strategies that both reference `(BACKTEST, BTCUSDT, 1m)`. Assert: hub has exactly one aggregator for that key, both strategies receive the same closed candles.

- [ ] **Step 2: Refactor `LiveSession`** — add an optional `candleHub: CandleHub? = null` constructor parameter. When provided, register the strategy's keys with it (additive); when null, construct a new hub (preserves 12b behavior for `qkt run`).

- [ ] **Step 3: `DaemonCommand`** constructs one `CandleHub`, passes it to `StrategyHandle.RealFactory`. Factory threads it into each `LiveSession`.

- [ ] **Step 4: Commit:** `feat(cli): shared CandleHub at daemon scope`.

---

### Task 16: `DaemonEndToEndTest`

**Files:**
- Create: `src/test/kotlin/com/qkt/cli/daemon/DaemonEndToEndTest.kt`

- [ ] **Step 1: Test** — full lifecycle. Start daemon on background thread, wait for control.port, deploy a fixture strategy, list it, hit `/status`, stop it, daemon stop. Assert no orphaned threads, no leaked file handles, exit code 0.

```kotlin
@Test
fun `full daemon lifecycle in-process`() {
    val tmp = Files.createTempDirectory("qkt-daemon-e2e")
    val fakeSource = SequenceMarketSource(/* tiny tick fixture */)
    val daemonThread = Thread {
        DaemonCommand(
            Args(arrayOf("daemon", "--state-dir", tmp.toString())),
            sourceFactory = { fakeSource },
        ).run()
    }
    daemonThread.start()

    val stateDir = StateDir.resolve(tmp.toString())
    waitForFile(stateDir.controlPortFile)
    val client = ControlClient(stateDir)

    client.deploy("ema", Path.of("src/test/resources/cli/valid_strategy.qkt"))
    assertThat(client.list()).hasSize(1)
    val port = client.list()[0].port

    val statusBody = OkHttpClient().newCall(
        Request.Builder().url("http://127.0.0.1:$port/status").build()
    ).execute().body!!.string()
    assertThat(statusBody).contains("\"strategy\"")

    client.stop("ema")
    assertThat(client.list()).isEmpty()

    client.shutdownDaemon()
    daemonThread.join(Duration.ofSeconds(5).toMillis())
    assertThat(daemonThread.isAlive).isFalse
}
```

- [ ] **Step 2: Commit:** `test(cli): daemon end-to-end lifecycle`.

---

### Task 17: `Dockerfile` (multi-stage) at repo root

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 1: Author Dockerfile** (verbatim from spec §7.1).

- [ ] **Step 2: `.dockerignore`** excludes `build/`, `.gradle/`, `.git/`, `.idea/`, `*.iml`, etc. Keeps build context small.

- [ ] **Step 3: Local smoke**

```bash
docker build -t qkt:local .
docker run --rm qkt:local --version
# expected: qkt 0.13.0
```

- [ ] **Step 4: Commit:** `build(cli): Dockerfile for qkt daemon image`.

---

### Task 18: Local `dockerBuild` Gradle task + smoke test

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/kotlin/com/qkt/cli/daemon/DockerImageTest.kt`

- [ ] **Step 1: Add Gradle task**

```kotlin
tasks.register("dockerBuild") {
    group = "distribution"
    description = "Build the qkt Docker image at qkt:local"
    doLast {
        exec { commandLine("docker", "build", "-t", "qkt:local", ".") }
    }
}
```

- [ ] **Step 2: Smoke test** gated behind a `dockerSmoke` JUnit tag (skipped on CI by default to avoid Docker dependency in the standard test path).

```kotlin
@Tag("dockerSmoke")
class DockerImageTest {
    @Test
    fun `qkt --version inside container returns matching version`() {
        // assumes ./gradlew dockerBuild has been run
        val process = ProcessBuilder("docker", "run", "--rm", "qkt:local", "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        assertThat(process.exitValue()).isEqualTo(0)
        assertThat(output.trim()).isEqualTo("qkt ${BuildInfo.VERSION}")
    }
}
```

- [ ] **Step 3: Test config** — `tasks.test { useJUnitPlatform { excludeTags("dockerSmoke") } }` (extending the existing exclusion list).

- [ ] **Step 4: Commit:** `build(cli): dockerBuild task and smoke test`.

---

### Task 19: `examples/docker/` user template

**Files:**
- Create: `examples/docker/Dockerfile`
- Create: `examples/docker/README.md`
- Create: `examples/docker/strategies/sample.qkt` (smallest valid strategy as a starting point)

- [ ] **Step 1: User Dockerfile** (verbatim from spec §7.3).

- [ ] **Step 2: README** walks through:
  1. Build base image locally (`./gradlew dockerBuild`) or pull from ghcr.
  2. Write a `Dockerfile` extending the base.
  3. `docker build -t my-prop:0.1 .`
  4. `docker run -d -p 47000-47100:47000-47100 my-prop:0.1`
  5. `docker exec my-prop qkt list`
  6. `docker exec my-prop qkt logs sample -f`

- [ ] **Step 3: Sample strategy** (smallest valid: declares one stream, one rule, one buy).

- [ ] **Step 4: Commit:** `docs(cli): docker example with sample strategy`.

---

### Task 20: GitHub Actions workflow — Docker build + push on tag

**Files:**
- Create: `.github/workflows/docker.yml`

- [ ] **Step 1: Workflow**

```yaml
name: docker

on:
  push:
    tags: ['v*']

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/${{ github.repository_owner }}/qkt
          tags: |
            type=ref,event=tag
            type=raw,value=latest,enable={{is_default_branch}}
      - uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

- [ ] **Step 2: Verify** workflow YAML lints (`act` or `actionlint`) — optional sanity check.

- [ ] **Step 3: Commit:** `ci: build and push qkt docker image on tag push`.

---

### Task 21: `BuildInfo.VERSION` bump to `0.13.0`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt`

- [ ] **Step 1: Bump.**

- [ ] **Step 2: Commit:** `chore(cli): bump version to 0.13.0`.

---

### Task 22: README.md update — Docker quick-start, daemon mode

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a "Quick start" section** showing three deployment shapes:
  1. **Foreground**: `qkt run foo.qkt`.
  2. **Daemon mode**: `qkt daemon` + `qkt deploy/list/logs/status/stop`.
  3. **Docker**: `docker run ghcr.io/elitekaycy/qkt:latest` + `docker exec` for management.

- [ ] **Step 2: Move historical content** (mock-tick demo, `gradle run`) to a "Legacy" section near the bottom.

- [ ] **Step 3: Commit:** `docs: README quick-start covers run, daemon, docker`.

---

### Task 23: Phase 12c changelog

**Files:**
- Create: `docs/phases/phase-12c-daemon.md`

Sections (per qkt SKILL.md §6):
1. Summary (3-5 sentences).
2. What's new — every new public surface (commands, daemon classes, Dockerfile, CI workflow).
3. Migration — slf4j-simple → logback-classic; existing `qkt run` unchanged.
4. Usage cookbook — daemon session, multi-strategy hosting, Docker workflow, `--load-dir`, MDC log routing.
5. Testing patterns — `DaemonEndToEndTest`, `dockerSmoke`-tagged tests.
6. Known limitations — no persistent registry, no hot reload, no auth, no log rotation, hub keys leak.
7. References.

- [ ] **Step 1: Write changelog**.
- [ ] **Step 2: Commit:** `docs: phase 12c changelog`.

---

## Self-review checklist

After all tasks complete:

- [ ] `./gradlew build` green (excluding `dockerSmoke` tag).
- [ ] `./gradlew dockerBuild && docker run --rm qkt:local --version` works.
- [ ] All commit messages match `<type>(<scope>): <subject>`.
- [ ] No AI footers, no emoji.
- [ ] `qkt daemon` foreground starts and writes `control.port`.
- [ ] `qkt deploy / list / stop / logs / status` all round-trip via the control plane.
- [ ] Two strategies on the same `(broker, sym, tf)` share one hub aggregator.
- [ ] Per-strategy log files isolate output (no cross-contamination).
- [ ] Phase 12c changelog `Merge commit:` filled in after merge.

---

## Merge

```bash
git checkout main
git merge --no-ff phase12c-daemon -m "merge: phase 12c daemon multi-strategy and docker"
./gradlew build   # verify
git add docs/phases/phase-12c-daemon.md
git commit -m "docs: link phase 12c changelog to merge commit"
git branch -d phase12c-daemon
```
