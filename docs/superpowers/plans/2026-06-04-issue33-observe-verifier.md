# `qkt observe` Verifier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only `qkt observe` subcommand that queries the running daemon's control plane and emits a per-gate go/no-go report for a live strategy (#33).

**Architecture:** `qkt observe` is a client of the existing HTTP control plane (`ControlClient`). It pulls `/status` (JSON → `StatusSnapshot`), `/logs/<name>` (per-strategy log text), and `/metrics` (Prometheus). Pure `GateEvaluator` functions over typed snapshots produce a report. One-shot, stateless, read-only. A `--control-port` override lets it target a tunneled prod daemon.

**Tech Stack:** Kotlin, OkHttp (existing `ControlClient`), kotlinx.serialization (existing `StatusSnapshot`), JUnit 5 + AssertJ (no mocks — anonymous `object` fakes).

Spec: `docs/superpowers/specs/2026-06-04-issue33-observe-verifier-design.md`

---

## File Structure

- `cli/observe/LogScan.kt` (new) — parse `/logs` text into typed `LogLine`s + classifiers (submit / error / trade+realized).
- `cli/observe/GateEvaluator.kt` (new) — `GateResult`, `ObserveReport`, the three pure gate functions, overall verdict.
- `cli/observe/ObserveReportRenderer.kt` (new) — text + JSON rendering.
- `cli/ObserveCommand.kt` (new) — arg parsing + orchestration (StateDir+port → ControlClient → pull → evaluate → render).
- `cli/daemon/ControlClient.kt` (modify) — add `open fun metrics()`; add an explicit-port override to `baseUrl()`.
- `cli/Main.kt` (modify) — register `"observe"` + usage text.
- `cli/daemon/TradeLog.kt` (new) — shared `logTrade(log, trade, realized)` helper.
- `cli/daemon/StrategyHandle.kt` + `cli/daemon/portfolio/PortfolioDeployer.kt` (modify) — call `logTrade(...)` in `onTrade` so `/logs` carries per-fill realized.
- `docs/how-to/observe-strategy.md` (new) — usage + SSH-tunnel runbook.

Reference facts (already verified in the code):
- `StatusSnapshot` (`cli/observe/StatusSnapshot.kt`): `@Serializable` with `strategy, version, uptimeMs, startedAt, equity, balance, realized, unrealized, positions: List<PositionDto>, lastTrade: TradeDto?`. `TradeDto(timestamp, side, symbol, qty, price, realized)`. Deserialize with `kotlinx.serialization.json.Json { ignoreUnknownKeys = true }`.
- `ControlClient(stateDir: StateDir, http)` — `open fun status(name): String`, `fun logs(name, lines, since, follow): okhttp3.Response`, `open fun health(): String`. `baseUrl()` = `http://127.0.0.1:${stateDir.readControlPort()}`.
- `Args`: `option(name): String?`, `requireOption(name): String`, `flag(name): Boolean`, `positional(idx): String?`, `subcommand`. `ArgError` on missing required.
- `ExitCodes`: `SUCCESS=0`, `USER_ERROR=1`, `ARG_ERROR=2`.
- Per-strategy `/logs` line format: `2026-06-04T07:55:01.234 [INFO] submit Stop dsl-hedge_straddle--1 EXNESS:XAUUSDm BUY stopPrice=… lastPrice=…` — `ISO8601 [LEVEL] <msg>`.
- Placement submit message starts with `submit ` (`TradingPipeline.kt:325`).
- Daemon `onTrade` (`StrategyHandle.kt:132`, `PortfolioDeployer.kt:141`) currently only `ring.append("trade", tradeToJson(trade, realized))` inside `withMdc("strategy", name) { … }` — no log line.

Run tests: `./gradlew test --tests "<FQN>"`; ktlint: `./gradlew ktlintFormat ktlintMainSourceSetCheck ktlintTestSourceSetCheck`. PRs target `dev`; commits are subject-only.

---

## Task 1: `LogScan` — typed log lines

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/observe/LogScan.kt`
- Test: `src/test/kotlin/com/qkt/cli/observe/LogScanTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli.observe

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogScanTest {
    private val raw =
        """
        2026-06-04T07:55:01.200 [INFO] submit Stop dsl-hedge_straddle--1 EXNESS:XAUUSD BUY stopPrice=2350 lastPrice=2349
        2026-06-04T07:55:01.260 [INFO] submit Stop dsl-hedge_straddle--2 EXNESS:XAUUSD SELL stopPrice=2340 lastPrice=2349
        2026-06-04T08:01:10.000 [INFO] trade SELL EXNESS:XAUUSD qty=0.20 px=2351 realized=12.50
        2026-06-04T08:02:00.000 [ERROR] broker rejected order code=10018
        not-a-log-line continuation
        """.trimIndent()

    @Test
    fun `parses level, timestamp, and message; ignores non-log lines`() {
        val lines = LogScan.parse(raw)
        assertThat(lines).hasSize(4)
        assertThat(lines[0].level).isEqualTo("INFO")
        assertThat(lines[0].timestamp).isEqualTo(Instant.parse("2026-06-04T07:55:01.200Z"))
        assertThat(lines[0].message).startsWith("submit Stop")
    }

    @Test
    fun `classifies submit, error, and trade-with-realized lines`() {
        val lines = LogScan.parse(raw)
        assertThat(lines.count { it.isSubmit }).isEqualTo(2)
        assertThat(lines.count { it.isError }).isEqualTo(1)
        val trade = lines.single { it.realized != null }
        assertThat(trade.realized).isEqualByComparingTo("12.50")
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.observe.LogScanTest"`. Expected: compile error (`LogScan` missing).

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.cli.observe

import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException

// File-level so both LogLine and LogScan share one definition.
private val LINE = Regex("""^(\S+)\s+\[(\w+)]\s+(.*)$""")
private val REALIZED = Regex("""realized=(-?\d+(?:\.\d+)?)""")

/**
 * One parsed per-strategy log line. The daemon writes the per-strategy log as
 * `<ISO8601> [<LEVEL>] <message>` (e.g. `2026-06-04T07:55:01.200 [INFO] submit Stop …`).
 */
data class LogLine(
    val timestamp: Instant,
    val level: String,
    val message: String,
) {
    /** An order was submitted (the placement signal); message begins `submit `. */
    val isSubmit: Boolean get() = message.startsWith("submit ")

    /** An engine-side error line. */
    val isError: Boolean get() = level == "ERROR"

    /** Realized P&L on a fill, parsed from a `trade … realized=<n>` line; null otherwise. */
    val realized: BigDecimal? get() =
        if (!message.startsWith("trade ")) {
            null
        } else {
            REALIZED.find(message)?.groupValues?.get(1)?.toBigDecimalOrNull()
        }
}

/** Parses raw `/logs` text into [LogLine]s, skipping lines without the `<ts> [LEVEL]` prefix. */
object LogScan {
    fun parse(raw: String): List<LogLine> =
        raw.lineSequence().mapNotNull { line ->
            val m = LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val ts =
                try {
                    Instant.parse(normalizeTs(m.groupValues[1]))
                } catch (e: DateTimeParseException) {
                    return@mapNotNull null
                }
            LogLine(ts, m.groupValues[2], m.groupValues[3])
        }.toList()

    // The logback file pattern emits a local-naive ISO8601 with no zone; treat it as UTC.
    private fun normalizeTs(s: String): String = if (s.endsWith("Z")) s else "${s}Z"
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. Then `./gradlew ktlintFormat ktlintMainSourceSetCheck ktlintTestSourceSetCheck`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/observe/LogScan.kt src/test/kotlin/com/qkt/cli/observe/LogScanTest.kt
git commit -m "feat(app): parse per-strategy daemon logs into typed lines"
```

---

## Task 2: `GateEvaluator` types + Gate 1 (placement fired)

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/observe/GateEvaluator.kt`
- Test: `src/test/kotlin/com/qkt/cli/observe/GateEvaluatorPlacementTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli.observe

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GateEvaluatorPlacementTest {
    private val schedule = PlacementSchedule(hours = setOf(6, 7), minute = 55)

    private fun submit(ts: String) = LogLine(Instant.parse(ts), "INFO", "submit Stop dsl-x--1 S BUY x")

    @Test
    fun `passes when every expected window has a submit near 55`() {
        val from = Instant.parse("2026-06-04T05:00:00Z")
        val to = Instant.parse("2026-06-04T08:00:00Z")
        val logs = listOf(submit("2026-06-04T06:55:02Z"), submit("2026-06-04T07:55:01Z"))
        val r = GateEvaluator.placement(logs, schedule, from, to)
        assertThat(r.status).isEqualTo(GateStatus.PASS)
        assertThat(r.detail.filter { it.contains("MISSED") }).isEmpty()
    }

    @Test
    fun `fails when an expected window has no submit`() {
        val from = Instant.parse("2026-06-04T05:00:00Z")
        val to = Instant.parse("2026-06-04T08:00:00Z")
        val logs = listOf(submit("2026-06-04T06:55:02Z")) // 07:55 missing
        val r = GateEvaluator.placement(logs, schedule, from, to)
        assertThat(r.status).isEqualTo(GateStatus.FAIL)
        assertThat(r.detail).anyMatch { it.contains("07:55") && it.contains("MISSED") }
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.observe.GateEvaluatorPlacementTest"`. Expected: compile error.

- [ ] **Step 3: Implement** (`GateEvaluator.kt` — shared types + gate 1)

```kotlin
package com.qkt.cli.observe

import java.time.Instant
import java.time.ZoneOffset

enum class GateStatus { PASS, FAIL, REVIEW }

/** One gate's verdict plus human-readable detail lines. */
data class GateResult(
    val name: String,
    val status: GateStatus,
    val detail: List<String>,
)

/** Expected placement windows: the given UTC [hours] at [minute] (e.g. hedge-straddle 6,7,12,13,14,15 @55). */
data class PlacementSchedule(
    val hours: Set<Int>,
    val minute: Int,
)

object GateEvaluator {
    /**
     * Gate 1 — for each expected window (scheduled hour at [PlacementSchedule.minute]) inside
     * `[from, to)`, require at least one `submit` log line in the minute..minute+2 band.
     */
    fun placement(
        logs: List<LogLine>,
        schedule: PlacementSchedule,
        from: Instant,
        to: Instant,
    ): GateResult {
        val submits = logs.filter { it.isSubmit }
        val detail = mutableListOf<String>()
        var missed = 0
        var window = from.atZone(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0)
        while (window.toInstant().isBefore(to)) {
            if (window.hour in schedule.hours) {
                val open = window.withMinute(schedule.minute).toInstant()
                val close = open.plusSeconds(180)
                if (open >= from && open < to) {
                    val fired = submits.any { !it.timestamp.isBefore(open) && it.timestamp.isBefore(close) }
                    val label = "%02d:%02d".format(window.hour, schedule.minute)
                    detail.add(if (fired) "$label FIRED" else "$label MISSED")
                    if (!fired) missed++
                }
            }
            window = window.plusHours(1)
        }
        if (detail.isEmpty()) detail.add("no expected windows in range")
        return GateResult("placement", if (missed == 0) GateStatus.PASS else GateStatus.FAIL, detail)
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/observe/GateEvaluator.kt src/test/kotlin/com/qkt/cli/observe/GateEvaluatorPlacementTest.kt
git commit -m "feat(app): observe gate 1 — placement-window verification"
```

---

## Task 3: Gate 2 (engine errors + benign-WARN allowlist)

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/observe/GateEvaluator.kt`
- Test: `src/test/kotlin/com/qkt/cli/observe/GateEvaluatorErrorsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli.observe

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GateEvaluatorErrorsTest {
    private fun line(level: String, msg: String) = LogLine(Instant.parse("2026-06-04T08:00:00Z"), level, msg)

    @Test
    fun `passes when there are no error lines`() {
        val r = GateEvaluator.errors(listOf(line("INFO", "submit Stop x"), line("WARN", "MT5 exness seeding orphan ticket=1")))
        assertThat(r.status).isEqualTo(GateStatus.PASS)
    }

    @Test
    fun `fails on an ERROR line and samples it`() {
        val r = GateEvaluator.errors(listOf(line("ERROR", "broker rejected order code=10018")))
        assertThat(r.status).isEqualTo(GateStatus.FAIL)
        assertThat(r.detail).anyMatch { it.contains("broker rejected") }
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.observe.GateEvaluatorErrorsTest"`. Expected: compile error (`errors` missing).

- [ ] **Step 3: Implement** (append to `GateEvaluator`)

```kotlin
    // Benign WARNs that recur in normal operation and must not fail the gate.
    private val BENIGN_WARN = listOf(
        "seeding orphan ticket",          // MT5 truncated-prefix attribution note
        "already exists but was not created by Docker Compose",
    )

    /** Gate 2 — fail on any ERROR line (and any non-benign WARN); report up to 10 samples. */
    fun errors(logs: List<LogLine>): GateResult {
        val bad = logs.filter { it.isError || (it.level == "WARN" && BENIGN_WARN.none { b -> it.message.contains(b) }) }
        val detail =
            if (bad.isEmpty()) {
                listOf("no engine-side errors")
            } else {
                bad.take(10).map { "${it.timestamp} [${it.level}] ${it.message.take(120)}" }
            }
        return GateResult("errors", if (bad.isEmpty()) GateStatus.PASS else GateStatus.FAIL, detail)
    }
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/observe/GateEvaluator.kt src/test/kotlin/com/qkt/cli/observe/GateEvaluatorErrorsTest.kt
git commit -m "feat(app): observe gate 2 — engine-error scan"
```

---

## Task 4: Gate 3 (PnL — daily reconstruction + consistency)

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/observe/GateEvaluator.kt`
- Test: `src/test/kotlin/com/qkt/cli/observe/GateEvaluatorPnlTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli.observe

import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GateEvaluatorPnlTest {
    private fun trade(ts: String, realized: String) =
        LogLine(Instant.parse(ts), "INFO", "trade SELL EXNESS:XAUUSD qty=0.2 px=2351 realized=$realized")

    @Test
    fun `reviews and reconciles when fills sum to status realized`() {
        val logs = listOf(trade("2026-06-04T08:00:00Z", "10.0"), trade("2026-06-04T08:30:00Z", "5.0"))
        val r = GateEvaluator.pnl(logs, statusRealized = BigDecimal("15.0"), statusUnrealized = BigDecimal("-1"))
        assertThat(r.status).isEqualTo(GateStatus.REVIEW)
        assertThat(r.detail).anyMatch { it.contains("consistent") }
        assertThat(r.detail).anyMatch { it.contains("2026-06-04") && it.contains("15") }
    }

    @Test
    fun `fails when fills do not reconcile with status realized`() {
        val logs = listOf(trade("2026-06-04T08:00:00Z", "10.0"))
        val r = GateEvaluator.pnl(logs, statusRealized = BigDecimal("999"), statusUnrealized = BigDecimal.ZERO)
        assertThat(r.status).isEqualTo(GateStatus.FAIL)
        assertThat(r.detail).anyMatch { it.contains("MISMATCH") }
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.observe.GateEvaluatorPnlTest"`. Expected: compile error (`pnl` missing).

- [ ] **Step 3: Implement** (append to `GateEvaluator`; add imports `java.math.BigDecimal`, `java.time.ZoneOffset`)

```kotlin
    /**
     * Gate 3 — report cumulative + per-UTC-day realized (reconstructed from `trade … realized=` log lines)
     * and check `Σ fills.realized ≈ statusRealized`. The value never auto-fails (a red day is the operator's
     * call); only a reconciliation MISMATCH fails, since it signals a P&L-accounting bug.
     */
    fun pnl(
        logs: List<LogLine>,
        statusRealized: BigDecimal,
        statusUnrealized: BigDecimal,
    ): GateResult {
        val fills = logs.mapNotNull { l -> l.realized?.let { l.timestamp to it } }
        val byDay =
            fills.groupBy { it.first.atZone(ZoneOffset.UTC).toLocalDate() }
                .toSortedMap()
                .map { (day, ts) -> "$day realized=${ts.sumOf { it.second }}" }
        val sum = fills.sumOf { it.second }
        val consistent = (sum - statusRealized).abs() <= BigDecimal("0.01")
        val detail = mutableListOf("cumulative realized=$statusRealized unrealized=$statusUnrealized")
        detail.addAll(byDay)
        detail.add(if (consistent) "Σfills=$sum consistent with status realized" else "Σfills=$sum MISMATCH vs status realized=$statusRealized")
        return GateResult("pnl", if (consistent) GateStatus.REVIEW else GateStatus.FAIL, detail)
    }
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/observe/GateEvaluator.kt src/test/kotlin/com/qkt/cli/observe/GateEvaluatorPnlTest.kt
git commit -m "feat(app): observe gate 3 — PnL report + fill reconciliation"
```

---

## Task 5: Report assembly + rendering + overall verdict

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/observe/ObserveReportRenderer.kt`
- Test: `src/test/kotlin/com/qkt/cli/observe/ObserveReportRendererTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli.observe

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObserveReportRendererTest {
    private val gates =
        listOf(
            GateResult("placement", GateStatus.PASS, listOf("06:55 FIRED")),
            GateResult("errors", GateStatus.PASS, listOf("no engine-side errors")),
            GateResult("pnl", GateStatus.REVIEW, listOf("cumulative realized=15")),
        )

    @Test
    fun `overall verdict is GO when gates 1-2 pass and pnl is not FAIL`() {
        assertThat(ObserveReportRenderer.verdict(gates)).isEqualTo("GO")
    }

    @Test
    fun `text render lists every gate and the verdict`() {
        val text = ObserveReportRenderer.text("hedge-straddle", "7d", gates)
        assertThat(text).contains("placement", "errors", "pnl", "GO")
    }

    @Test
    fun `verdict is NO-GO when a gate FAILs`() {
        val failed = gates.map { if (it.name == "errors") it.copy(status = GateStatus.FAIL) else it }
        assertThat(ObserveReportRenderer.verdict(failed)).isEqualTo("NO-GO")
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.observe.ObserveReportRendererTest"`. Expected: compile error.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.cli.observe

/** Renders the gate results as a text go/no-go report (and computes the overall verdict). */
object ObserveReportRenderer {
    /** GO iff no gate FAILed (placement/errors must PASS; pnl is REVIEW). NO-GO on any FAIL. */
    fun verdict(gates: List<GateResult>): String =
        if (gates.any { it.status == GateStatus.FAIL }) "NO-GO" else "GO"

    fun text(
        strategy: String,
        period: String,
        gates: List<GateResult>,
    ): String =
        buildString {
            appendLine("qkt observe — $strategy — last $period")
            appendLine("=".repeat(48))
            for (g in gates) {
                appendLine("[${g.status}] ${g.name}")
                for (d in g.detail) appendLine("    $d")
            }
            appendLine("=".repeat(48))
            appendLine("VERDICT: ${verdict(gates)}")
        }
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/observe/ObserveReportRenderer.kt src/test/kotlin/com/qkt/cli/observe/ObserveReportRendererTest.kt
git commit -m "feat(app): observe report rendering + overall verdict"
```

---

## Task 6: `ControlClient` — `metrics()` + explicit-port override

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/ControlClientPortTest.kt`

- [ ] **Step 1: Write the failing test** (uses a tiny embedded `com.sun.net.httpserver.HttpServer` — same approach as existing daemon tests, no mocks)

```kotlin
package com.qkt.cli.daemon

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ControlClientPortTest {
    @Test
    fun `metrics hits the explicit port, bypassing the control-port file`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/metrics") { ex ->
            val body = "qkt_daemon_uptime_seconds 42".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            // stateDir has no control.port file; the explicit port must be used instead.
            val client = ControlClient(stateDir = StateDir.at(java.nio.file.Files.createTempDirectory("sd")), explicitPort = server.address.port)
            assertThat(client.metrics()).contains("qkt_daemon_uptime_seconds")
        } finally {
            server.stop(0)
        }
    }
}
```

(If `StateDir` has no public `at(path)` factory, use its existing test factory — check `StateDir.companion` and mirror how `ControlClientTest`/daemon tests construct it.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.daemon.ControlClientPortTest"`. Expected: compile error (`explicitPort`, `metrics` missing).

- [ ] **Step 3: Implement** — add the constructor param + `metrics()`; route `baseUrl()` through the override:

```kotlin
open class ControlClient(
    private val stateDir: StateDir,
    private val http: OkHttpClient = OkHttpClient(),
    private val explicitPort: Int? = null,
) {
    // ... existing exceptions ...

    private fun baseUrl(): String {
        val port =
            explicitPort
                ?: stateDir.readControlPort()
                ?: throw NoDaemonRunningException(
                    "no daemon running (no control.port file at ${stateDir.controlPortFile})",
                )
        return "http://127.0.0.1:$port"
    }

    open fun metrics(): String {
        val resp = http.newCall(Request.Builder().url("${baseUrl()}/metrics").build()).execute()
        return readOrThrow(resp)
    }
    // ... rest unchanged ...
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt src/test/kotlin/com/qkt/cli/daemon/ControlClientPortTest.kt
git commit -m "feat(app): ControlClient metrics endpoint + explicit-port targeting"
```

---

## Task 7: `ObserveCommand` + Main wiring (FakeControlClient integration)

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/ObserveCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`
- Test: `src/test/kotlin/com/qkt/cli/ObserveCommandTest.kt`

- [ ] **Step 1: Write the failing test** (drive via a `FakeControlClient` subclass — no mocks)

```kotlin
package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.observe.GateStatus
import com.qkt.cli.observe.ObserveRunner
import java.nio.file.Files
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObserveCommandTest {
    private fun fakeClient(logs: String, status: String, metrics: String): ControlClient =
        object : ControlClient(StateDir.at(Files.createTempDirectory("sd"))) {
            override fun status(name: String?) = status
            override fun metrics() = metrics
            // logs() is not open in the base; ObserveRunner reads it via a seam — see Step 3.
        }

    @Test
    fun `produces a GO verdict for a clean run`() {
        val status = """{"strategy":"hedge-straddle","version":1,"uptimeMs":1,"startedAt":"2026-06-04T00:00:00Z","equity":15,"balance":15,"realized":15,"unrealized":0,"positions":[],"lastTrade":null}"""
        val logs =
            "2026-06-04T06:55:01.000 [INFO] submit Stop dsl-h--1 X BUY p\n" +
                "2026-06-04T08:00:00.000 [INFO] trade SELL X qty=0.2 px=1 realized=15"
        val report =
            ObserveRunner.run(
                strategy = "hedge-straddle",
                from = Instant.parse("2026-06-04T06:00:00Z"),
                to = Instant.parse("2026-06-04T09:00:00Z"),
                schedule = com.qkt.cli.observe.PlacementSchedule(setOf(6), 55),
                logsText = logs,
                statusJson = status,
            )
        assertThat(report.single { it.name == "placement" }.status).isEqualTo(GateStatus.PASS)
        assertThat(report.single { it.name == "pnl" }.status).isEqualTo(GateStatus.REVIEW)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.ObserveCommandTest"`. Expected: compile error (`ObserveRunner` missing).

- [ ] **Step 3: Implement** — split pure orchestration (`ObserveRunner`, testable on strings) from I/O (`ObserveCommand`):

`src/main/kotlin/com/qkt/cli/observe/ObserveRunner.kt`:
```kotlin
package com.qkt.cli.observe

import java.time.Instant
import kotlinx.serialization.json.Json

/** Pure: turns the raw control-plane responses + a window/schedule into gate results. */
object ObserveRunner {
    private val json = Json { ignoreUnknownKeys = true }

    fun run(
        strategy: String,
        from: Instant,
        to: Instant,
        schedule: PlacementSchedule,
        logsText: String,
        statusJson: String,
    ): List<GateResult> {
        val logs = LogScan.parse(logsText)
        val status = json.decodeFromString(StatusSnapshot.serializer(), statusJson)
        return listOf(
            GateEvaluator.placement(logs, schedule, from, to),
            GateEvaluator.errors(logs),
            GateEvaluator.pnl(logs, status.realized, status.unrealized),
        )
    }
}
```

`src/main/kotlin/com/qkt/cli/ObserveCommand.kt` (I/O shell — pulls the strings, calls `ObserveRunner`, renders):
```kotlin
package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.observe.ObserveReportRenderer
import com.qkt.cli.observe.ObserveRunner
import com.qkt.cli.observe.PlacementSchedule
import java.time.Duration
import java.time.Instant

/** `qkt observe --strategy <name> [--since 7d] [--control-port <p>] [--windows 6,7@55] [--json]`. */
class ObserveCommand(
    private val args: Args,
) {
    fun run(): Int {
        val strategy =
            try {
                args.requireOption("strategy")
            } catch (e: ArgError) {
                System.err.println("qkt: ${e.message}")
                return ExitCodes.ARG_ERROR
            }
        val to = Instant.now()
        val from = to.minus(parseSince(args.option("since") ?: "24h"))
        val schedule = parseWindows(args.option("windows"))
        val client =
            ControlClient(
                stateDir = StateDir.default(),
                explicitPort = args.option("control-port")?.toIntOrNull(),
            )
        val statusJson = client.status(strategy)
        val logsText = client.logs(strategy, lines = 100_000, since = from.toString()).use { it.body?.string().orEmpty() }
        val gates = ObserveRunner.run(strategy, from, to, schedule, logsText, statusJson)
        print(ObserveReportRenderer.text(strategy, args.option("since") ?: "24h", gates))
        return if (gates.any { it.status.name == "FAIL" }) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
    }

    private fun parseSince(s: String): Duration {
        val n = s.dropLast(1).toLong()
        return when (s.last()) {
            'd' -> Duration.ofDays(n)
            'h' -> Duration.ofHours(n)
            'm' -> Duration.ofMinutes(n)
            else -> throw ArgError("bad --since '$s' (use 7d/24h/30m)")
        }
    }

    // Default = hedge-straddle's schedule; override with `--windows 6,7,12,13,14,15@55`.
    private fun parseWindows(spec: String?): PlacementSchedule {
        if (spec == null) return PlacementSchedule(setOf(6, 7, 12, 13, 14, 15), 55)
        val (hours, minute) = spec.split("@")
        return PlacementSchedule(hours.split(",").map { it.trim().toInt() }.toSet(), minute.toInt())
    }
}
```

Wire `Main.kt` — add to the `when (args.subcommand)`:
```kotlin
            "observe" -> ObserveCommand(args).run()
```
and add a usage line under STRATEGY AUTHORING / DAEMON OPERATIONS: `observe --strategy <name>   verify go-live gates for a live strategy`.

Confirm `StateDir.default()` exists (the daemon CLI uses it). If the factory is named differently (e.g. `StateDir.fromEnv()`), use that — check `DaemonCommand` for the exact call.

- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.qkt.cli.ObserveCommandTest"`. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/ObserveCommand.kt src/main/kotlin/com/qkt/cli/observe/ObserveRunner.kt src/main/kotlin/com/qkt/cli/Main.kt src/test/kotlin/com/qkt/cli/ObserveCommandTest.kt
git commit -m "feat(app): qkt observe subcommand"
```

---

## Task 8: Daemon addition — log per-fill realized

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/TradeLog.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt:132`, `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt:141`
- Test: `src/test/kotlin/com/qkt/cli/daemon/TradeLogTest.kt`

The daemon `onTrade` only `ring.append`s; `/logs` therefore lacks per-fill realized, which gate 3's daily reconstruction needs. Add a structured log line inside the existing `withMdc("strategy", name)` blocks (so it routes to the per-strategy log file).

- [ ] **Step 1: Write the failing test** (assert the rendered line shape — a pure formatter, no logging framework needed)

```kotlin
package com.qkt.cli.daemon

import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradeLogTest {
    @Test
    fun `formats a trade line with side, symbol, qty, price and realized`() {
        val t = Trade(orderId = "o1", symbol = "EXNESS:XAUUSD", price = BigDecimal("2351"), quantity = BigDecimal("0.2"), side = Side.SELL, timestamp = 0L)
        val line = TradeLog.line(t, BigDecimal("12.50"))
        assertThat(line).isEqualTo("trade SELL EXNESS:XAUUSD qty=0.2 px=2351 realized=12.50")
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.daemon.TradeLogTest"`. Expected: compile error.

- [ ] **Step 3: Implement** `TradeLog.kt`:

```kotlin
package com.qkt.cli.daemon

import com.qkt.execution.Trade
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/** Structured per-fill log line so `/logs` carries realized P&L for the observe verifier (#33). */
object TradeLog {
    private val log = LoggerFactory.getLogger("qkt.trade")

    fun line(
        trade: Trade,
        realized: BigDecimal,
    ): String =
        "trade ${trade.side} ${trade.symbol} qty=${trade.quantity.toPlainString()} " +
            "px=${trade.price.toPlainString()} realized=${realized.toPlainString()}"

    /** Emit the line at INFO under the current strategy MDC. */
    fun emit(
        trade: Trade,
        realized: BigDecimal,
    ) = log.info(line(trade, realized))
}
```

Then in `StrategyHandle.kt` `onTrade` (inside `withMdc("strategy", name) { … }`), add `com.qkt.cli.daemon.TradeLog.emit(trade, realized)` alongside the existing `ring.append(...)`. Same in `PortfolioDeployer.kt` `onTrade` (inside its nested `withMdc` blocks).

- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.qkt.cli.daemon.TradeLogTest"`. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/TradeLog.kt src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt src/test/kotlin/com/qkt/cli/daemon/TradeLogTest.kt
git commit -m "feat(app): log per-fill realized in the daemon for observe reconstruction"
```

---

## Task 9: Docs — usage + SSH-tunnel runbook

**Files:**
- Create: `docs/how-to/observe-strategy.md`

- [ ] **Step 1: Write the doc** — cover: what `qkt observe` checks (the three gates), the local usage (`qkt observe --strategy hedge-straddle --since 7d`), and the **prod** usage via SSH tunnel:

```markdown
# Verify a live strategy with `qkt observe`

`qkt observe` reads the running daemon's control plane and prints a go/no-go report over a period:
- **placement** — the scheduled `:55` windows each fired (a `submit` appeared),
- **errors** — no engine-side `[ERROR]` (benign WARNs allowlisted),
- **pnl** — daily realized per UTC day, cumulative, and a `Σ fills ≈ status.realized` reconciliation (review, not a hard fail on a red day).

## Local daemon
    qkt observe --strategy hedge-straddle --since 7d

## Production daemon (control plane is localhost-only)
Tunnel to the daemon's control port, then point `--control-port` at the local end:
    PORT=$(ssh root@<prod> 'cat $QKT_STATE_DIR/control.port')
    ssh -N -L 9999:127.0.0.1:$PORT root@<prod> &
    qkt observe --strategy hedge-straddle --since 7d --control-port 9999

Exit code is non-zero on a NO-GO (a FAILed gate), so it composes in scripts/cron.
```

- [ ] **Step 2: Commit**

```bash
git add docs/how-to/observe-strategy.md
git commit -m "docs: how-to for qkt observe verifier"
```

---

## Final verification

- [ ] `./gradlew test --tests "com.qkt.cli.observe.*" --tests "com.qkt.cli.ObserveCommandTest" --tests "com.qkt.cli.daemon.ControlClientPortTest" --tests "com.qkt.cli.daemon.TradeLogTest"` — green.
- [ ] `./gradlew ktlintMainSourceSetCheck ktlintTestSourceSetCheck` — clean.
- [ ] PR to `dev` (`Refs #33` — #33 is a prod-observation gate that stays open until the operator completes the week; this ships the tool).

## Notes for the implementer

- **DRY:** `LogScan` is the single log parser; gates consume `LogLine`s, never re-parse text. `ObserveRunner` is the single pure orchestrator; `ObserveCommand` is only I/O.
- **Read-only:** never call `halt`/`stop`/`deploy`/`resume` from `observe`.
- **Pin the real shapes before trusting the parsers:** the `submit` line (`TradingPipeline.kt:325`) and (after Task 8) the `trade … realized=` line are the two the parser depends on — the tests above encode their exact shape; if the live format differs, fix the test and the regex together.
- **Immediacy:** gates 1, 2, and cumulative PnL work against the deployed v0.29.13 daemon now (over the tunnel). Gate-3 *daily* lines appear only after Task 8 ships in the next deploy; until then the daily table is empty and the report says so — that's expected, not a bug.
