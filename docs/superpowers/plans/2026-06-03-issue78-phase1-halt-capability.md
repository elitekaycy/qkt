# #78 Phase 1 — Halt Capability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the daemon a manual, operator-triggered halt/resume that pauses *new* order submission (existing positions keep being managed, strategies stay deployed) — reachable from the control-plane HTTP API and the CLI (`qkt halt`/`qkt resume`), global or per-strategy.

**Architecture:** A small `DaemonControl` facade over `StrategyRegistry` exposes `halt(target)`/`resume(target)`. Each `LiveSessionHandle` gains `halt(reason)`/`resume()` that delegate to the session's `RiskState` — whose `isStrategyHalted` already makes `RiskEngine` reject new orders. New control-plane routes and CLI commands drive the facade. This is phase 1 of the channel-pluggable-commands spec; the same `DaemonControl` is what inbound command channels call later.

**Tech Stack:** Kotlin, Gradle, JUnit 5 + AssertJ, OkHttp (control client), `com.sun.net.httpserver` (control plane).

**Spec:** `docs/superpowers/specs/2026-06-02-issue78-command-channels-design.md`

---

## File map

- Create: `src/main/kotlin/com/qkt/cli/daemon/DaemonControl.kt` — `Target`, `ControlResult`, `DaemonControl` interface, `RegistryDaemonControl` impl.
- Modify: `src/main/kotlin/com/qkt/app/LiveSessionHandle.kt` — add `halt(reason)`/`resume()` (default no-op).
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` — override `halt`/`resume` in the returned handle → `riskState`.
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt` — `/halt[/<name>]`, `/resume[/<name>]` routes + handlers.
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt` — `halt(name?)`, `resume(name?)`.
- Create: `src/main/kotlin/com/qkt/cli/HaltCommand.kt`, `src/main/kotlin/com/qkt/cli/ResumeCommand.kt`.
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt` — dispatch `halt`/`resume`.
- Test: `src/test/kotlin/com/qkt/cli/daemon/DaemonControlTest.kt`, `.../ControlRoutesHaltTest.kt`, `src/test/kotlin/com/qkt/app/LiveSessionHaltTest.kt`.

---

## Task 1: `DaemonControl` over the registry + `LiveSessionHandle.halt/resume`

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/DaemonControl.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSessionHandle.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/DaemonControlTest.kt`

- [ ] **Step 1: Add `halt`/`resume` to `LiveSessionHandle` with no-op defaults**

In `src/main/kotlin/com/qkt/app/LiveSessionHandle.kt`, add inside the interface (next to `fun flatten()`):

```kotlin
    /**
     * Operator halt: stop submitting NEW orders for this session (existing positions keep
     * being managed). Default is a no-op so non-daemon handles (tests, replay) need not
     * implement it; the live daemon session overrides it to drive its risk state.
     */
    fun halt(reason: String) {}

    /** Reverse [halt]: re-enable new-order submission. Default no-op. */
    fun resume() {}
```

- [ ] **Step 2: Write the failing test for `RegistryDaemonControl`**

Create `src/test/kotlin/com/qkt/cli/daemon/DaemonControlTest.kt`:

```kotlin
package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonControlTest {
    private class RecordingHandle : LiveSessionHandle {
        val halts = mutableListOf<String>()
        var resumes = 0

        override fun halt(reason: String) {
            halts.add(reason)
        }

        override fun resume() {
            resumes++
        }
    }

    private fun registryWith(
        tmp: Path,
        names: List<String>,
    ): Pair<StrategyRegistry, Map<String, RecordingHandle>> {
        val handlesByName = names.associateWith { RecordingHandle() }
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { name, _, _ ->
                    StrategyHandle(
                        name = name,
                        version = 1,
                        live = handlesByName.getValue(name),
                        logFile = tmp.resolve("$name.log"),
                        startedAt = Instant.EPOCH,
                    )
                },
            )
        names.forEach { registry.deploy(it, tmp.resolve("$it.qkt")) }
        return registry to handlesByName
    }

    @Test
    fun `halt All halts every strategy`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).halt(Target.All)
        assertThat(result.affected).containsExactlyInAnyOrder("alpha", "beta")
        assertThat(result.unknown).isEmpty()
        assertThat(handles.getValue("alpha").halts).hasSize(1)
        assertThat(handles.getValue("beta").halts).hasSize(1)
    }

    @Test
    fun `halt one strategy halts only it`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).halt(Target.Strategy("alpha"))
        assertThat(result.affected).containsExactly("alpha")
        assertThat(handles.getValue("alpha").halts).hasSize(1)
        assertThat(handles.getValue("beta").halts).isEmpty()
    }

    @Test
    fun `halt unknown name reports it and changes nothing`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha"))
        val result = RegistryDaemonControl(registry).halt(Target.Strategy("ghost"))
        assertThat(result.affected).isEmpty()
        assertThat(result.unknown).containsExactly("ghost")
        assertThat(handles.getValue("alpha").halts).isEmpty()
    }

    @Test
    fun `resume All resumes every strategy`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).resume(Target.All)
        assertThat(result.affected).containsExactlyInAnyOrder("alpha", "beta")
        assertThat(handles.getValue("alpha").resumes).isEqualTo(1)
        assertThat(handles.getValue("beta").resumes).isEqualTo(1)
    }
}
```

Note: `StrategyHandle.Factory` is a `fun interface` (single method `create(name, file, ignoreMismatches)`), so the lambda form above works. Confirm the `StrategyHandle` constructor parameter names (`name`, `version`, `live`, `logFile`, `startedAt`, `childMeta`) against `StrategyHandle.kt` and adjust if the version/logFile/startedAt args differ.

- [ ] **Step 3: Run the test, verify it FAILS to compile**

Run: `./gradlew test --tests "com.qkt.cli.daemon.DaemonControlTest"`
Expected: FAIL — `Target`, `RegistryDaemonControl` unresolved.

- [ ] **Step 4: Create `DaemonControl.kt`**

Create `src/main/kotlin/com/qkt/cli/daemon/DaemonControl.kt`:

```kotlin
package com.qkt.cli.daemon

/** What a control action targets: every strategy, or one by name. */
sealed interface Target {
    data object All : Target

    data class Strategy(
        val name: String,
    ) : Target
}

/**
 * Outcome of a halt/resume. [affected] are the strategy names actually acted on;
 * [unknown] are requested names that weren't deployed.
 */
data class ControlResult(
    val affected: List<String>,
    val unknown: List<String> = emptyList(),
)

/**
 * In-process control surface over the running daemon. The HTTP control routes, the CLI
 * (via HTTP), and (later) inbound command channels all go through this one type so the
 * halt/resume logic lives in exactly one place.
 */
interface DaemonControl {
    fun halt(target: Target): ControlResult

    fun resume(target: Target): ControlResult
}

/** [DaemonControl] backed by the live [StrategyRegistry]. */
class RegistryDaemonControl(
    private val registry: StrategyRegistry,
) : DaemonControl {
    override fun halt(target: Target): ControlResult = apply(target) { it.live.halt("operator") }

    override fun resume(target: Target): ControlResult = apply(target) { it.live.resume() }

    private fun apply(
        target: Target,
        action: (StrategyHandle) -> Unit,
    ): ControlResult =
        when (target) {
            Target.All -> {
                val all = registry.list()
                all.forEach(action)
                ControlResult(affected = all.map { it.name })
            }
            is Target.Strategy -> {
                val handle = registry.get(target.name)
                if (handle == null) {
                    ControlResult(affected = emptyList(), unknown = listOf(target.name))
                } else {
                    action(handle)
                    ControlResult(affected = listOf(target.name))
                }
            }
        }
}
```

- [ ] **Step 5: Run the test, verify it PASSES**

Run: `./gradlew test --tests "com.qkt.cli.daemon.DaemonControlTest"`
Expected: PASS — 4 tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/DaemonControl.kt \
        src/main/kotlin/com/qkt/app/LiveSessionHandle.kt \
        src/test/kotlin/com/qkt/cli/daemon/DaemonControlTest.kt
git commit -m "feat(daemon): DaemonControl halt/resume over the strategy registry"
```

---

## Task 2: Wire the real session's `halt`/`resume` to its `RiskState`

The default no-op must be overridden by the actual daemon session so a halt really rejects orders. `RiskState.halt(reason)` publishes `RiskEvent.Halted` and flips `isStrategyHalted`, which `RiskEngine` already uses to reject new `OrderRequest`s.

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Test: `src/test/kotlin/com/qkt/app/LiveSessionHaltTest.kt`

- [ ] **Step 1: Write the failing integration test**

Create `src/test/kotlin/com/qkt/app/LiveSessionHaltTest.kt`. Mirror the session construction in `src/test/kotlin/com/qkt/app/LiveSessionTest.kt` (copy its setup/helper that builds and `start()`s a `LiveSession` with a fake `MarketSource` and a simple strategy). Then assert halt publishes `RiskEvent.Halted` on the bus:

```kotlin
// package + imports per LiveSessionTest.kt (com.qkt.app; EventBus, RiskEvent, the test's
// fake MarketSource + strategy builders). Reuse LiveSessionTest's helper to get a started
// `handle: LiveSessionHandle` plus the `bus` it was built with.

    @Test
    fun `handle halt publishes RiskEvent Halted and resume publishes Resumed`() {
        val halted = mutableListOf<RiskEvent.Halted>()
        val resumed = mutableListOf<RiskEvent.Resumed>()
        bus.subscribe<RiskEvent.Halted> { halted.add(it) }
        bus.subscribe<RiskEvent.Resumed> { resumed.add(it) }

        handle.halt("operator")
        assertThat(halted).hasSize(1)
        assertThat(halted.first().reason).isEqualTo("operator")

        handle.resume()
        assertThat(resumed).hasSize(1)
    }
```

If `LiveSessionTest.kt` exposes the bus through a different name, adapt; the assertion is the contract: `handle.halt(reason)` → one `RiskEvent.Halted(reason)`, `handle.resume()` → one `RiskEvent.Resumed`.

- [ ] **Step 2: Run the test, verify it FAILS**

Run: `./gradlew test --tests "com.qkt.app.LiveSessionHaltTest"`
Expected: FAIL — no `RiskEvent.Halted` published (default no-op halt). `halted` is empty.

- [ ] **Step 3: Override `halt`/`resume` in the session handle**

In `src/main/kotlin/com/qkt/app/LiveSession.kt`, inside the `object : LiveSessionHandle { … }` returned from `start()` (where `override fun flatten()` and `override fun streamBrokers()` live, and `riskState` is in lexical scope from its `val riskState = RiskState(...)` declaration earlier in `start()`), add:

```kotlin
            override fun halt(reason: String) {
                riskState.halt(reason)
            }

            override fun resume() {
                riskState.resume()
            }
```

- [ ] **Step 4: Run the test, verify it PASSES**

Run: `./gradlew test --tests "com.qkt.app.LiveSessionHaltTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/LiveSession.kt \
        src/test/kotlin/com/qkt/app/LiveSessionHaltTest.kt
git commit -m "feat(app): live session handle halt/resume drives risk state"
```

---

## Task 3: Control-plane `/halt` and `/resume` routes

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/ControlRoutesHaltTest.kt`

- [ ] **Step 1: Write the failing route test**

Create `src/test/kotlin/com/qkt/cli/daemon/ControlRoutesHaltTest.kt`. Build the same recording-handle registry as `DaemonControlTest` (copy the `RecordingHandle` + `registryWith` helpers — they are small and the engineer may read tasks out of order), start an `HttpServer` bound to `127.0.0.1:0` with `ControlRoutes.dispatch(...)`, and POST:

```kotlin
package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ControlRoutesHaltTest {
    private class RecordingHandle : LiveSessionHandle {
        var halts = 0
        var resumes = 0

        override fun halt(reason: String) {
            halts++
        }

        override fun resume() {
            resumes++
        }
    }

    private fun post(
        port: Int,
        path: String,
    ): HttpResponse<String> {
        val req =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `POST halt halts all, POST halt name halts one`(
        @TempDir tmp: Path,
    ) {
        val handles = listOf("alpha", "beta").associateWith { RecordingHandle() }
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { name, _, _ ->
                    StrategyHandle(
                        name = name,
                        version = 1,
                        live = handles.getValue(name),
                        logFile = tmp.resolve("$name.log"),
                        startedAt = Instant.EPOCH,
                    )
                },
            )
        handles.keys.forEach { registry.deploy(it, tmp.resolve("$it.qkt")) }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            ControlRoutes.dispatch(registry, Instant.EPOCH, stateDir = null, shutdown = {}),
        )
        server.start()
        try {
            val port = server.address.port
            assertThat(post(port, "/halt").statusCode()).isEqualTo(200)
            assertThat(handles.getValue("alpha").halts).isEqualTo(1)
            assertThat(handles.getValue("beta").halts).isEqualTo(1)

            assertThat(post(port, "/resume/alpha").statusCode()).isEqualTo(200)
            assertThat(handles.getValue("alpha").resumes).isEqualTo(1)
            assertThat(handles.getValue("beta").resumes).isEqualTo(0)

            assertThat(post(port, "/halt/ghost").statusCode()).isEqualTo(404)
        } finally {
            server.stop(0)
        }
    }
}
```

- [ ] **Step 2: Run the test, verify it FAILS**

Run: `./gradlew test --tests "com.qkt.cli.daemon.ControlRoutesHaltTest"`
Expected: FAIL — `/halt` returns 404 (route not wired).

- [ ] **Step 3: Add the routes + handlers**

In `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`, add to the `when` block in `dispatch` (next to the `/start/` line):

```kotlin
                    method == "POST" && path == "/halt" -> handleHalt(ex, registry, null)
                    method == "POST" && path.startsWith("/halt/") ->
                        handleHalt(ex, registry, path.removePrefix("/halt/").trim('/').ifBlank { null })
                    method == "POST" && path == "/resume" -> handleResume(ex, registry, null)
                    method == "POST" && path.startsWith("/resume/") ->
                        handleResume(ex, registry, path.removePrefix("/resume/").trim('/').ifBlank { null })
```

Then add the handlers (near `handleStop`). Each builds the control from its `registry`
param and maps an unknown name to 404:

```kotlin
    private fun handleHalt(
        ex: HttpExchange,
        registry: StrategyRegistry,
        name: String?,
    ) {
        val target = if (name == null) Target.All else Target.Strategy(name)
        val result = RegistryDaemonControl(registry).halt(target)
        if (result.unknown.isNotEmpty()) {
            return respond(ex, 404, """{"error":"unknown strategy: ${result.unknown.first()}"}""")
        }
        respond(ex, 200, """{"state":"halted","affected":${jsonArray(result.affected)}}""")
    }

    private fun handleResume(
        ex: HttpExchange,
        registry: StrategyRegistry,
        name: String?,
    ) {
        val target = if (name == null) Target.All else Target.Strategy(name)
        val result = RegistryDaemonControl(registry).resume(target)
        if (result.unknown.isNotEmpty()) {
            return respond(ex, 404, """{"error":"unknown strategy: ${result.unknown.first()}"}""")
        }
        respond(ex, 200, """{"state":"resumed","affected":${jsonArray(result.affected)}}""")
    }

    private fun jsonArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
```

- [ ] **Step 4: Run the test, verify it PASSES**

Run: `./gradlew test --tests "com.qkt.cli.daemon.ControlRoutesHaltTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt \
        src/test/kotlin/com/qkt/cli/daemon/ControlRoutesHaltTest.kt
git commit -m "feat(daemon): control-plane /halt and /resume routes"
```

---

## Task 4: `ControlClient` methods + `qkt halt`/`qkt resume` CLI

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`
- Create: `src/main/kotlin/com/qkt/cli/HaltCommand.kt`, `src/main/kotlin/com/qkt/cli/ResumeCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`

- [ ] **Step 1: Add `halt`/`resume` to `ControlClient`**

In `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`, add (mirroring `start`):

```kotlin
    fun halt(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/halt" else "${baseUrl()}/halt/$name"
        val resp =
            http
                .newCall(Request.Builder().url(url).post("".toRequestBody(JSON_MEDIA)).build())
                .execute()
        return readOrThrow(resp)
    }

    fun resume(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/resume" else "${baseUrl()}/resume/$name"
        val resp =
            http
                .newCall(Request.Builder().url(url).post("".toRequestBody(JSON_MEDIA)).build())
                .execute()
        return readOrThrow(resp)
    }
```

- [ ] **Step 2: Create `HaltCommand` and `ResumeCommand`**

Create `src/main/kotlin/com/qkt/cli/HaltCommand.kt`:

```kotlin
package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

/** `qkt halt [name]` — pause new-order submission for one strategy, or all if no name. */
class HaltCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.positionalOrNull(0)
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.halt(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: halt failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        if (args.flag("json")) println(body) else println("[INFO] halted ${name ?: "all strategies"}")
        return ExitCodes.SUCCESS
    }
}
```

Create `src/main/kotlin/com/qkt/cli/ResumeCommand.kt` (identical shape, `client.resume(name)`, message `"resumed ..."`):

```kotlin
package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

/** `qkt resume [name]` — re-enable new-order submission for one strategy, or all if no name. */
class ResumeCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.positionalOrNull(0)
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.resume(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: resume failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        if (args.flag("json")) println(body) else println("[INFO] resumed ${name ?: "all strategies"}")
        return ExitCodes.SUCCESS
    }
}
```

Note: confirm the optional-positional accessor name in `Args` (`StopCommand` uses `args.requirePositional(0, "<name>")` for a required one). If there is no `positionalOrNull`, use the existing optional-arg accessor (check `Args.kt`; e.g. `args.positional(0)` returning null, or `args.positionals.getOrNull(0)`). The command must treat "no name" as `null` → all.

- [ ] **Step 3: Wire into `Main.kt`**

In `src/main/kotlin/com/qkt/cli/Main.kt`, add to the `when (args.subcommand)` block (next to `"stop"`/`"start"`):

```kotlin
            "halt" -> HaltCommand(args).run()
            "resume" -> ResumeCommand(args).run()
```

- [ ] **Step 4: Write + run a client test**

Create `src/test/kotlin/com/qkt/cli/HaltCommandTest.kt` using a fake `ControlClient` (subclass overriding `halt`/`resume`, constructed with a temp `StateDir`) to assert the command calls `halt(name)` / `halt(null)` and maps a `DaemonError(404)` to exit `USER_ERROR`. Mirror an existing command test (e.g. `StatusCommandDeepTest` or `RunCommandTest`) for the `Args` construction and `clientFactory` injection.

Run: `./gradlew test --tests "com.qkt.cli.HaltCommandTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt \
        src/main/kotlin/com/qkt/cli/HaltCommand.kt \
        src/main/kotlin/com/qkt/cli/ResumeCommand.kt \
        src/main/kotlin/com/qkt/cli/Main.kt \
        src/test/kotlin/com/qkt/cli/HaltCommandTest.kt
git commit -m "feat(cli): qkt halt and qkt resume commands"
```

---

## Task 5: Format, verify, push, PR

- [ ] **Step 1: ktlintFormat** — `./gradlew ktlintFormat`; if it changed files, `git add -u && git commit -m "style: apply ktlint formatting"`.
- [ ] **Step 2: Run the phase's tests** — `./gradlew test --tests "com.qkt.cli.daemon.DaemonControlTest" --tests "com.qkt.app.LiveSessionHaltTest" --tests "com.qkt.cli.daemon.ControlRoutesHaltTest" --tests "com.qkt.cli.HaltCommandTest"`. Expected: all green. (Full build is CI's job per the project's push-and-let-CI-verify workflow.)
- [ ] **Step 3: Push** — `git push -u origin issue78-command-channels`.
- [ ] **Step 4: PR** — `gh pr create --base dev --title "feat(daemon): manual halt/resume capability (#78 phase 1)"`. Body: summary (manual halt pauses new orders via RiskState; control-plane routes + CLI), test plan, and `Refs #78` (NOT `Closes` — #78 has two more phases). Note in the PR that this is phase 1 of the command-channels spec.

---

## Self-review notes
- **Spec coverage (phase 1 scope):** `DaemonControl`+`Target` (Task 1), `LiveSession.halt/resume`→RiskState (Task 2), `/halt`·`/resume` routes (Task 3), `ControlClient`+CLI (Task 4). Status(), the provider model, and inbound channels are phases 2–3, out of scope here.
- **No placeholders:** new-unit code is complete; the two integration tests (Task 2 session harness, Task 4 command test) reference the named existing harness to copy setup from rather than duplicating ~50 lines — the assertions are spelled out.
- **Type consistency:** `Target`/`ControlResult`/`DaemonControl`/`RegistryDaemonControl` names are identical across tasks; `LiveSessionHandle.halt(reason: String)`/`resume()` signatures match between the interface (Task 1), the override (Task 2), the recording fakes (Tasks 1, 3), and the client/CLI (Task 4).
