# Daemon State-Dir Unification Implementation Plan (#227)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the daemon persist strategy state under the `StateDir`-resolved root (which honors `QKT_STATE_DIR`), so state lands on the persistent volume and survives container recreates.

**Architecture:** Remove `Config`'s separate `state.dir` resolution. `Config.statePersistor()` takes the root as a parameter; `DaemonCommand` passes `stateDir.stateRoot` (the env-aware root the daemon already resolves for logs/control). Delete the now-redundant dead `StateDir.stateFor`. State lands at `<QKT_STATE_DIR>/state/<strategy>/<file>`, beside `logs/`.

**Tech Stack:** Kotlin, Gradle, JUnit 5 + AssertJ, ktlint.

**Spec:** `docs/superpowers/specs/2026-06-02-issue227-state-dir-unify-design.md`

---

## File map

- Modify: `src/main/kotlin/com/qkt/cli/Config.kt` — drop `stateDir` property + `state.dir` key; `statePersistor()` → `statePersistor(stateRoot: Path)`; KDoc.
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt` — pass `stateDir.stateRoot`; add startup log line.
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt` — delete dead `stateFor`; keep `stateRoot`.
- Modify/Test: `src/test/kotlin/com/qkt/cli/ConfigStateTest.kt` — update call sites, drop `stateDir` assertions + removed-behavior test, add root-honored behavior test.
- Rename/Test: `src/test/kotlin/com/qkt/cli/daemon/StateDirStateForTest.kt` → `StateDirStateRootTest.kt` — keep `stateRoot` case only.
- Docs: `docs/phases/phase-29-engine-state-persistence.md` — follow-up entry; fix known-limitations wording.

---

## Task 1: Route the persistor root through `StateDir`

This is the core change. The `statePersistor` signature change, its only production caller (`DaemonCommand`), and its test all move together because Kotlin won't compile a partial change.

**Files:**
- Test: `src/test/kotlin/com/qkt/cli/ConfigStateTest.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Config.kt`
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`

- [ ] **Step 1: Confirm `Config.stateDir` has no callers beyond the two we're changing**

Run:
```bash
grep -rn "\.stateDir\b\|cfg\.stateDir\|config\.stateDir" src/main src/test | grep -iv "StateDir\.\|stateDir =\|stateDir:\|stateDir)\|stateDir\." || echo "clean"
```
Expected: only `Config.kt` (the property itself) and `ConfigStateTest.kt`. If anything else references the `Config.stateDir` property, stop and reassess — the plan assumes only these two.

- [ ] **Step 2: Rewrite the failing test file**

Replace the entire contents of `src/test/kotlin/com/qkt/cli/ConfigStateTest.kt` with:

```kotlin
package com.qkt.cli

import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.NoopStatePersistor
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigStateTest {
    @Test
    fun `state defaults to enabled`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: tv\n")
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isTrue
    }

    @Test
    fun `state enabled false produces NoopStatePersistor`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: false
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isFalse
        assertThat(c.statePersistor(tmp.resolve("state"))).isInstanceOf(NoopStatePersistor::class.java)
    }

    @Test
    fun `state enabled true produces FileStatePersistor`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: true
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isTrue
        assertThat(c.statePersistor(tmp.resolve("state"))).isInstanceOf(FileStatePersistor::class.java)
    }

    @Test
    fun `statePersistor writes strategy files under the given root`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: tv\n")
        val c = Config.load(cfg)
        val root = tmp.resolve("state")
        val persistor = c.statePersistor(root)

        persistor.saveBracketPairs("hedge-straddle", emptyList())

        assertThat(root.resolve("hedge-straddle").resolve("bracket-pairs.json")).exists()
    }
}
```

Notes for the engineer:
- `state` defaults to enabled + synchronous, so the write in the last test hits disk immediately (no async flush to await).
- `saveBracketPairs(strategyId, pairs)` writes `<root>/<strategyId>/bracket-pairs.json` unconditionally, even for an empty list — the simplest real on-disk proof that the passed `root` is honored. No domain objects to construct.

- [ ] **Step 3: Run the test, verify it FAILS to compile**

Run: `./gradlew test --tests "com.qkt.cli.ConfigStateTest"`
Expected: FAIL — compilation error, `statePersistor` expects no arguments (current signature is `statePersistor()`).

- [ ] **Step 4: Update `Config.kt` — remove `stateDir`, parameterize `statePersistor`**

In `src/main/kotlin/com/qkt/cli/Config.kt`:

Replace the `state` field KDoc (the block immediately above `val state: Map<String, String> = emptyMap(),`):

```kotlin
    /**
     * Engine state persistence settings (Phase 29). Knobs:
     *   - `enabled` — `true` (default) wires [com.qkt.persistence.FileStatePersistor]; `false`
     *     uses [com.qkt.persistence.NoopStatePersistor] (no disk I/O, no restart recovery).
     *   - `dir` — root directory for state files. `~/` is expanded against `$HOME`.
     *     Default: `~/.qkt/state`.
     */
    val state: Map<String, String> = emptyMap(),
```

with:

```kotlin
    /**
     * Engine state persistence settings (Phase 29). Knobs:
     *   - `enabled` — `true` (default) wires [com.qkt.persistence.FileStatePersistor]; `false`
     *     uses [com.qkt.persistence.NoopStatePersistor] (no disk I/O, no restart recovery).
     *   - `async` — see [stateAsync].
     *
     * The state directory is not set here: [statePersistor] receives its root from the
     * caller. The daemon passes [com.qkt.cli.daemon.StateDir.stateRoot], which honors
     * `QKT_STATE_DIR` (and `--state-dir`), so state lands on the operator's volume.
     */
    val state: Map<String, String> = emptyMap(),
```

Delete the `stateDir` property entirely:

```kotlin
    /** Effective `state.dir`; defaults to `~/.qkt/state`. `~/` expanded against `$HOME`. */
    val stateDir: String
        get() = (state["dir"] ?: "~/.qkt/state").replaceFirst("~/", System.getProperty("user.home") + "/")
```

Replace the `statePersistor()` method:

```kotlin
    /**
     * Returns the [com.qkt.persistence.StatePersistor] for this config. Layered by flag:
     *   - `state.enabled = false`              → [com.qkt.persistence.NoopStatePersistor].
     *   - `state.enabled = true`, `async = false` → [com.qkt.persistence.FileStatePersistor] (synchronous).
     *   - `state.enabled = true`, `async = true`  → [com.qkt.persistence.AsyncStatePersistor] wrapping [com.qkt.persistence.FileStatePersistor].
     */
    fun statePersistor(): com.qkt.persistence.StatePersistor {
        if (!stateEnabled) return com.qkt.persistence.NoopStatePersistor()
        val file =
            com.qkt.persistence.FileStatePersistor(
                java.nio.file.Path
                    .of(stateDir),
            )
        return if (stateAsync) com.qkt.persistence.AsyncStatePersistor(file) else file
    }
```

with:

```kotlin
    /**
     * Returns the [com.qkt.persistence.StatePersistor] for this config, writing under
     * [stateRoot]. Layered by flag:
     *   - `state.enabled = false`              → [com.qkt.persistence.NoopStatePersistor].
     *   - `state.enabled = true`, `async = false` → [com.qkt.persistence.FileStatePersistor] (synchronous).
     *   - `state.enabled = true`, `async = true`  → [com.qkt.persistence.AsyncStatePersistor] wrapping [com.qkt.persistence.FileStatePersistor].
     *
     * @param stateRoot directory the file persistor writes under (`<stateRoot>/<strategyId>/...`).
     */
    fun statePersistor(stateRoot: Path): com.qkt.persistence.StatePersistor {
        if (!stateEnabled) return com.qkt.persistence.NoopStatePersistor()
        val file = com.qkt.persistence.FileStatePersistor(stateRoot)
        return if (stateAsync) com.qkt.persistence.AsyncStatePersistor(file) else file
    }
```

(`Path` is already imported at `Config.kt:6`.)

- [ ] **Step 5: Update `DaemonCommand.kt` — pass the StateDir root + log it**

In `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`, find:

```kotlin
        val statePersistor = cfg.statePersistor()
```

Replace with:

```kotlin
        val statePersistor = cfg.statePersistor(stateDir.stateRoot)
```

(`stateDir` is the local `StateDir` already resolved at the top of `startDaemon()`.)

Then find the startup log block:

```kotlin
        println("[INFO] qkt ${BuildInfo.VERSION} daemon starting")
        println("[INFO] state directory: ${stateDir.root}")
```

Replace with:

```kotlin
        println("[INFO] qkt ${BuildInfo.VERSION} daemon starting")
        println("[INFO] state directory: ${stateDir.root}")
        println("[INFO] strategy state: ${stateDir.stateRoot}")
```

- [ ] **Step 6: Run the test, verify it PASSES**

Run: `./gradlew test --tests "com.qkt.cli.ConfigStateTest"`
Expected: PASS — 4 tests green. (This compiles the whole main + test source sets, so it also proves `DaemonCommand` compiles against the new signature.)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/Config.kt \
        src/main/kotlin/com/qkt/cli/DaemonCommand.kt \
        src/test/kotlin/com/qkt/cli/ConfigStateTest.kt
git commit -m "fix(app): persist daemon state under the StateDir-resolved root"
```

---

## Task 2: Delete dead `StateDir.stateFor`

`stateFor` reimplements `FileStatePersistor`'s `<root>/<strategy>/<file>` join in the wrong layer (persistence must not depend on `cli.daemon`) and has zero production callers. Remove it; keep `stateRoot`, which Task 1 now consumes.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt`
- Rename: `src/test/kotlin/com/qkt/cli/daemon/StateDirStateForTest.kt` → `StateDirStateRootTest.kt`

- [ ] **Step 1: Replace the `stateFor` test file with a `stateRoot`-only test**

Delete the old file and create the new one:

```bash
git rm src/test/kotlin/com/qkt/cli/daemon/StateDirStateForTest.kt
```

Create `src/test/kotlin/com/qkt/cli/daemon/StateDirStateRootTest.kt`:

```kotlin
package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateDirStateRootTest {
    @Test
    fun `stateRoot exposes the state subdir under root`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.stateRoot).isEqualTo(tmp.resolve("state"))
    }
}
```

- [ ] **Step 2: Run the new test, verify it PASSES (stateFor still present, unused)**

Run: `./gradlew test --tests "com.qkt.cli.daemon.StateDirStateRootTest"`
Expected: PASS — 1 test green. (`stateFor` still exists but is no longer referenced by any test.)

- [ ] **Step 3: Delete `stateFor` from `StateDir.kt`**

In `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt`, delete the KDoc + method (the `stateFor` block), leaving `stateRoot` intact:

```kotlin
    /**
     * Resolves a per-strategy state file path under `<root>/<strategyName>/<fileName>`.
     *
     * Portfolio children embed `/` in their name (`parent/alias`); kept as-is to make the
     * on-disk layout mirror the in-memory naming. Caller is responsible for creating the
     * parent directory before writing.
     */
    fun stateFor(
        name: String,
        fileName: String,
    ): Path = root.resolve("state").resolve(name).resolve(fileName)

    val stateRoot: Path = root.resolve("state")
```

becomes:

```kotlin
    val stateRoot: Path = root.resolve("state")
```

- [ ] **Step 4: Verify nothing else referenced `stateFor`, then run tests**

Run:
```bash
grep -rn "\.stateFor(" src/main src/test || echo "no callers"
./gradlew test --tests "com.qkt.cli.daemon.StateDirStateRootTest"
```
Expected: `no callers` for the grep (the `stateFor` in `AggregateBinding`/`ExprCompiler`/`NoopStatePersistor` are unrelated same-named methods on other types — the dotted `.stateFor(` on a `StateDir` should be gone); test PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/StateDir.kt \
        src/test/kotlin/com/qkt/cli/daemon/StateDirStateRootTest.kt
git commit -m "refactor(app): drop dead StateDir.stateFor helper"
```

---

## Task 3: Phase 29 changelog follow-up entry

Per qkt conventions, a bug fix that reveals a design flaw adds a follow-up entry to the affected phase changelog. The Phase 29 changelog documents the now-removed `state.dir` key in several places; add one dated note marking it superseded rather than rewriting the historical sections.

**Files:**
- Modify: `docs/phases/phase-29-engine-state-persistence.md`

- [ ] **Step 1: Insert the follow-up note above "## What's new"**

In `docs/phases/phase-29-engine-state-persistence.md`, find the line:

```markdown
## What's new
```

Insert immediately before it:

```markdown
## Update — 2026-06-02 (#227)

The `state.dir` config key documented below has been **removed**. The state directory is
no longer set in `qkt.config.yaml`; it is the daemon state dir resolved by `StateDir`
(`--state-dir` > `QKT_STATE_DIR` > `XDG_STATE_HOME/qkt` > `~/.local/state/qkt`), and
strategy files live under `<stateDir>/state/<strategyId>/`. This fixed a prod bug where
`QKT_STATE_DIR` was silently ignored and state landed on ephemeral container storage. The
`state.dir` lines in the Configuration and "Custom state directory" sections below are
superseded — set `QKT_STATE_DIR` (or `--state-dir`) instead. `Config.statePersistor()`
now takes the root as a parameter. See
`docs/superpowers/specs/2026-06-02-issue227-state-dir-unify-design.md`.

```

- [ ] **Step 2: Fix the known-limitations wording**

Find:

```markdown
- **No multi-instance support.** Single-writer assumption. Two daemons writing to the same `state.dir` simultaneously will race on file writes. Out of scope indefinitely.
```

Replace `state.dir` with `state root`:

```markdown
- **No multi-instance support.** Single-writer assumption. Two daemons writing to the same state root simultaneously will race on file writes. Out of scope indefinitely.
```

- [ ] **Step 3: Verify the docs build (mkdocs strict)**

Run: `mkdocs build --strict 2>&1 | tail -5`
Expected: build succeeds, no warnings. (If `mkdocs` is unavailable locally, skip — CI runs it.)

- [ ] **Step 4: Commit**

```bash
git add docs/phases/phase-29-engine-state-persistence.md
git commit -m "docs: note state.dir removal in phase 29 changelog (#227)"
```

---

## Task 4: Format, verify, push, open PR

**Files:** none (tooling + VCS).

- [ ] **Step 1: Apply ktlint formatting**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL. (Mandatory before any qkt push — `ktlintCheck` is the most common avoidable CI failure.)

- [ ] **Step 2: If ktlintFormat changed anything, commit it**

Run: `git status --short`
If any files are modified:
```bash
git add -u
git commit -m "style: apply ktlint formatting"
```

- [ ] **Step 3: Run the two touched test classes once more**

Run: `./gradlew test --tests "com.qkt.cli.ConfigStateTest" --tests "com.qkt.cli.daemon.StateDirStateRootTest"`
Expected: PASS — 5 tests green. (Full `build` is left to CI per the project's push-and-let-CI-verify workflow.)

- [ ] **Step 4: Push the branch**

Run:
```bash
git status --short
git push -u origin fix-daemon-state-dir
```
Expected: clean tree, branch pushed.

- [ ] **Step 5: Open the PR against `dev`**

Run:
```bash
gh pr create --base dev --title "fix(app): persist daemon state under the StateDir-resolved root" --body "$(cat <<'EOF'
## Phase
Bug fix (Phase 29 surface). Spec: docs/superpowers/specs/2026-06-02-issue227-state-dir-unify-design.md. Plan: docs/superpowers/plans/2026-06-02-issue227-state-dir-unify.md.

## Summary
The daemon wrote strategy state via a `Config.stateDir` resolution that ignored `QKT_STATE_DIR`, landing it on ephemeral container storage. Route the persistor through the env-aware `StateDir.stateRoot` the daemon already resolves, so state survives container recreates. Fixes #227.

## Changes
- `Config.statePersistor()` takes the root as a parameter; removed the divergent `state.dir` key and `Config.stateDir`.
- `DaemonCommand` passes `stateDir.stateRoot`; logs the resolved strategy-state dir.
- Deleted dead `StateDir.stateFor` (redundant with `FileStatePersistor`'s own path join).
- Phase 29 changelog follow-up entry.

## Tests
- `ConfigStateTest`: persistor honors the passed root (real on-disk write); call sites updated; removed-behavior test dropped.
- `StateDirStateRootTest`: `stateRoot` resolution.
- Local: targeted tests green; full build via CI.

## Docs
- Phase 29 changelog: `state.dir` removal note + known-limitations wording.

## Backwards compatibility
Removes the `state.dir` config key (acceptable per qkt no-compat posture; prod does not set it). Local-dev default state location moves from `~/.qkt/state` to `~/.local/state/qkt/state`.

## Out of scope
- `StateFileWriter` shared-temp concurrent-write race — #228.
- Prod verification that state survives a container recreate — operator-run; the #33 unblock.

## Risk
Medium — money-handling persistence wiring. Mitigated: behavior test proves the root is honored; backtest path does not persist (invariant untouched).
EOF
)"
```
Expected: PR URL printed.

- [ ] **Step 6: Report the PR URL and the prod-verification follow-up**

State plainly: the code change is merged-ready once CI is green, but #227 is only *closed* after the operator confirms on prod that `/var/lib/qkt/state/<strategy>/*.json` populates and survives a container recreate (the #33 unblock). That step is operator-run and outside this PR.

---

## Self-review notes

- **Spec coverage:** persistor root via StateDir (Task 1), drop `state.dir`/`stateDir` (Task 1), delete dead `stateFor` (Task 2), startup log of resolved root (Task 1, Step 5), changelog follow-up (Task 3), prod-verify called out as operator-run (Task 4, Step 6). All spec sections covered.
- **No placeholders:** every code/test block is complete and copy-pasteable.
- **Type consistency:** `statePersistor(stateRoot: Path)` defined in Task 1 is the signature used in every test call and the `DaemonCommand` caller; `stateRoot` is the `StateDir` property kept in Task 2.
