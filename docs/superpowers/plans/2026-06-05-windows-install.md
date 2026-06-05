# Windows Install (Full Parity) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Windows user install qkt and run it fully (backtest + live daemon) exactly as on Linux, via a self-contained zip, a PowerShell one-liner, and winget.

**Architecture:** The qkt engine is already cross-platform JVM. We add (1) a small `UserDirs` helper so state/config land in native Windows dirs, (2) a `jpackage` app-image (`qkt.exe` + bundled jlink runtime) zipped per release, (3) `install.ps1`, (4) winget automation, (5) `windows-latest` CI that builds and smoke-tests the real artifact. Linux/Mac behavior is unchanged.

**Tech Stack:** Kotlin/JVM 21, Gradle (`application` + custom `Exec`/`Zip` tasks), JDK `jpackage`/`jlink`, GitHub Actions (`windows-latest`), PowerShell, winget (`winget-releaser`).

**Spec:** `docs/superpowers/specs/2026-06-05-windows-install-design.md`
**Issue:** #262  **Branch:** `issue262-windows-install`

**Conventions (qkt):** Conventional Commits, **subject line only — no body, no footer, no AI attribution.** Reference `(#262)`. ktlint runs in `check.yml`; a known gotcha is "needs a blank line before a comment" — leave a blank line before any standalone comment. PRs target `dev`.

---

## Task 1: `UserDirs` helper

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/UserDirs.kt`
- Test: `src/test/kotlin/com/qkt/cli/UserDirsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/cli/UserDirsTest.kt`:

```kotlin
package com.qkt.cli

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserDirsTest {
    private val winEnv = mapOf("LOCALAPPDATA" to "/fake/Local", "APPDATA" to "/fake/Roaming")

    @Test
    fun `windows state home uses LOCALAPPDATA`() {
        val ud = UserDirs(osName = "windows 11", env = winEnv, home = Path.of("/fake/home"))
        assertThat(ud.isWindows).isTrue
        assertThat(ud.stateHome()).isEqualTo(Path.of("/fake/Local/qkt"))
    }

    @Test
    fun `windows config home uses APPDATA`() {
        val ud = UserDirs(osName = "windows 11", env = winEnv, home = Path.of("/fake/home"))
        assertThat(ud.configHome()).isEqualTo(Path.of("/fake/Roaming/qkt"))
    }

    @Test
    fun `windows falls back to home AppData when env vars absent`() {
        val ud = UserDirs(osName = "windows 10", env = emptyMap(), home = Path.of("/fake/home"))
        assertThat(ud.stateHome()).isEqualTo(Path.of("/fake/home/AppData/Local/qkt"))
        assertThat(ud.configHome()).isEqualTo(Path.of("/fake/home/AppData/Roaming/qkt"))
    }

    @Test
    fun `linux state home honors XDG_STATE_HOME`() {
        val ud = UserDirs(osName = "linux", env = mapOf("XDG_STATE_HOME" to "/x/state"), home = Path.of("/home/me"))
        assertThat(ud.isWindows).isFalse
        assertThat(ud.stateHome()).isEqualTo(Path.of("/x/state/qkt"))
    }

    @Test
    fun `linux state home falls back to dot-local-state`() {
        val ud = UserDirs(osName = "linux", env = emptyMap(), home = Path.of("/home/me"))
        assertThat(ud.stateHome()).isEqualTo(Path.of("/home/me/.local/state/qkt"))
    }

    @Test
    fun `linux config home honors XDG_CONFIG_HOME then falls back to dot-config`() {
        val withXdg = UserDirs(osName = "linux", env = mapOf("XDG_CONFIG_HOME" to "/x/cfg"), home = Path.of("/home/me"))
        assertThat(withXdg.configHome()).isEqualTo(Path.of("/x/cfg/qkt"))
        val noXdg = UserDirs(osName = "linux", env = emptyMap(), home = Path.of("/home/me"))
        assertThat(noXdg.configHome()).isEqualTo(Path.of("/home/me/.config/qkt"))
    }

    @Test
    fun `blank env values are ignored`() {
        val ud = UserDirs(osName = "windows 11", env = mapOf("LOCALAPPDATA" to "  "), home = Path.of("/fake/home"))
        assertThat(ud.stateHome()).isEqualTo(Path.of("/fake/home/AppData/Local/qkt"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.cli.UserDirsTest'`
Expected: FAIL — compilation error, `UserDirs` is unresolved.

- [ ] **Step 3: Implement `UserDirs`**

Create `src/main/kotlin/com/qkt/cli/UserDirs.kt`:

```kotlin
package com.qkt.cli

import java.nio.file.Path

/**
 * OS-correct base directories for qkt's per-user state and config.
 *
 * Windows uses the platform conventions (%LOCALAPPDATA%, %APPDATA%); Linux and
 * macOS keep the XDG / dotfile layout qkt has always used. Injectable for tests:
 * drive [osName] + [env] + [home] to assert resolution without running on the
 * target OS.
 *
 * e.g. on Windows stateHome() -> C:\Users\me\AppData\Local\qkt ;
 *      on Linux   stateHome() -> ~/.local/state/qkt
 */
class UserDirs(
    private val osName: String = System.getProperty("os.name", "").lowercase(),
    private val env: Map<String, String> = System.getenv(),
    private val home: Path = Path.of(System.getProperty("user.home")),
) {
    val isWindows: Boolean = osName.contains("win")

    /** Per-machine mutable state (daemon runtime dir, logs, persisted engine state). */
    fun stateHome(): Path =
        when {
            isWindows -> localAppData().resolve("qkt")
            envPath("XDG_STATE_HOME") != null -> envPath("XDG_STATE_HOME")!!.resolve("qkt")
            else -> home.resolve(".local").resolve("state").resolve("qkt")
        }

    /** Per-user configuration directory (holds qkt.config.yaml, editor-install.json). */
    fun configHome(): Path =
        when {
            isWindows -> appData().resolve("qkt")
            envPath("XDG_CONFIG_HOME") != null -> envPath("XDG_CONFIG_HOME")!!.resolve("qkt")
            else -> home.resolve(".config").resolve("qkt")
        }

    private fun localAppData(): Path =
        envPath("LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local")

    private fun appData(): Path =
        envPath("APPDATA") ?: home.resolve("AppData").resolve("Roaming")

    private fun envPath(key: String): Path? =
        env[key]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.cli.UserDirsTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/UserDirs.kt src/test/kotlin/com/qkt/cli/UserDirsTest.kt
git commit -m "feat: add UserDirs for OS-correct state and config paths (#262)"
```

---

## Task 2: Route `StateDir` through `UserDirs`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt:38-49`
- Test: `src/test/kotlin/com/qkt/cli/daemon/StateDirTest.kt` (existing — must stay green)

- [ ] **Step 1: Replace the `resolve` platform branches with a `UserDirs` delegation**

In `StateDir.kt`, add the import (with the other imports near the top):

```kotlin
import com.qkt.cli.UserDirs
```

Replace the `companion object` `resolve` body (currently lines 38-49):

```kotlin
    companion object {
        fun resolve(override: String? = null): StateDir {
            val root =
                when {
                    override != null -> Path.of(override)
                    System.getenv("QKT_STATE_DIR") != null -> Path.of(System.getenv("QKT_STATE_DIR"))
                    else -> UserDirs().stateHome()
                }
            return StateDir(root)
        }
    }
```

This preserves the existing precedence (`--state-dir` override, then `QKT_STATE_DIR`) and moves the `XDG_STATE_HOME` / `~/.local/state/qkt` ladder into `UserDirs.stateHome()` (already covered by `UserDirsTest`), adding the Windows `%LOCALAPPDATA%\qkt` branch.

- [ ] **Step 2: Run the existing StateDir tests to verify nothing regressed**

Run: `./gradlew test --tests 'com.qkt.cli.daemon.StateDirTest' --tests 'com.qkt.cli.daemon.StateDirStateRootTest'`
Expected: PASS (all existing tests — they exercise the `override` path, which is unchanged).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/StateDir.kt
git commit -m "refactor: route StateDir default through UserDirs (#262)"
```

---

## Task 3: Route `Config.defaultSearchPaths` through `UserDirs`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/Config.kt:105-110`
- Test: `src/test/kotlin/com/qkt/cli/ConfigLocateTest.kt:32-41` (update existing) + new Windows case

- [ ] **Step 1: Update the over-specified existing test and add a Windows test**

`ConfigLocateTest.kt` is `package com.qkt.cli`, the same package as `UserDirs`, so no new import is needed. Replace the test `default search list includes pwd etc-qkt and home-qkt locations` (lines 32-41) with these two tests:

```kotlin
    @Test
    fun `non-windows search list is pwd, etc-qkt, config-home, home-qkt`() {
        val home = Path.of("/home/me")
        val ud = UserDirs(osName = "linux", env = emptyMap(), home = home)
        val paths = Config.defaultSearchPaths(userDirs = ud, home = home)
        assertThat(paths.map { it.toString() }).containsExactly(
            "./qkt.config.yaml",
            "/etc/qkt/qkt.config.yaml",
            "/home/me/.config/qkt/qkt.config.yaml",
            "/home/me/.qkt/qkt.config.yaml",
        )
    }

    @Test
    fun `windows search list uses APPDATA and omits etc-qkt`() {
        val home = Path.of("/fake/home")
        val ud = UserDirs(osName = "windows 11", env = mapOf("APPDATA" to "/fake/Roaming"), home = home)
        val paths = Config.defaultSearchPaths(userDirs = ud, home = home).map { it.toString() }
        assertThat(paths).containsExactly(
            "./qkt.config.yaml",
            "/fake/Roaming/qkt/qkt.config.yaml",
            "/fake/home/.qkt/qkt.config.yaml",
        )
        assertThat(paths).noneMatch { it.contains("/etc/qkt") }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.cli.ConfigLocateTest'`
Expected: FAIL — `defaultSearchPaths` does not accept `userDirs`/`home` params yet.

- [ ] **Step 3: Implement the OS-aware search list**

`Config.kt` is `package com.qkt.cli`, the same package as `UserDirs`, so no new import is needed. Replace `defaultSearchPaths()` (lines 105-110):

```kotlin
        fun defaultSearchPaths(
            userDirs: UserDirs = UserDirs(),
            home: Path = Path.of(System.getProperty("user.home")),
        ): List<Path> {
            val paths = mutableListOf(Path.of("./qkt.config.yaml"))
            if (!userDirs.isWindows) paths += Path.of("/etc/qkt/qkt.config.yaml")
            paths += userDirs.configHome().resolve("qkt.config.yaml")
            paths += home.resolve(".qkt").resolve("qkt.config.yaml")
            return paths
        }
```

On Linux this is purely additive (gains `~/.config/qkt/qkt.config.yaml`; `/etc/qkt` and `~/.qkt` retained, original order preserved). On Windows it uses `%APPDATA%\qkt` and omits the meaningless `/etc/qkt`. Prod is unaffected — qkt-prod passes `--config` explicitly (`../qkt-prod/compose.yml:101`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.cli.ConfigLocateTest' --tests 'com.qkt.cli.ConfigTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/Config.kt src/test/kotlin/com/qkt/cli/ConfigLocateTest.kt
git commit -m "refactor: route config search through UserDirs (#262)"
```

---

## Task 4: Route `EditorManifest.defaultPath` through `UserDirs`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/editor/EditorManifest.kt:51-59`
- Test: `src/test/kotlin/com/qkt/cli/editor/EditorManifestTest.kt` (existing stays green; add Windows case)

- [ ] **Step 1: Add the failing Windows test**

In `EditorManifestTest.kt`, add:

```kotlin
    @Test
    fun `defaultPath on windows uses APPDATA-qkt`(
        @TempDir tmp: Path,
    ) {
        val appData = tmp.resolve("Roaming")
        val p =
            EditorManifest.defaultPath(
                env = mapOf("APPDATA" to appData.toString()),
                home = tmp,
                osName = "windows 11",
            )
        assertThat(p).isEqualTo(appData.resolve("qkt/editor-install.json"))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.cli.editor.EditorManifestTest'`
Expected: FAIL — `defaultPath` has no `osName` parameter.

- [ ] **Step 3: Reimplement `defaultPath` on top of `UserDirs`**

In `EditorManifest.kt`, add the import:

```kotlin
import com.qkt.cli.UserDirs
```

Replace `defaultPath` (lines 51-59):

```kotlin
        fun defaultPath(
            env: Map<String, String> = System.getenv(),
            home: Path = Path.of(System.getProperty("user.home")),
            osName: String = System.getProperty("os.name", "").lowercase(),
        ): Path = UserDirs(osName = osName, env = env, home = home).configHome().resolve("editor-install.json")
```

On Linux `configHome()` reproduces the old `$XDG_CONFIG_HOME/qkt` → `~/.config/qkt` result, so the two existing `defaultPath` tests stay green. On Windows it becomes `%APPDATA%\qkt\editor-install.json`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.cli.editor.EditorManifestTest'`
Expected: PASS (existing XDG + dot-config tests still green; new Windows test green).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/editor/EditorManifest.kt src/test/kotlin/com/qkt/cli/editor/EditorManifestTest.kt
git commit -m "refactor: route editor manifest path through UserDirs (#262)"
```

---

## Task 5: Gradle `jpackage` Windows packaging

**Files:**
- Modify: `build.gradle.kts:58-95` (the `jlinkRuntime` task + add new tasks after `selfContainedDist`)

- [ ] **Step 1: Make `jlinkRuntime` resolve the platform binary name**

`jlinkRuntime` will now run on Windows too (as a dependency of the new task), where the binary is `jlink.exe`. In `build.gradle.kts`, replace the `commandLine` first argument inside `jlinkRuntime`'s `doFirst` (line 67-68, the `"${...}/bin/jlink"` element). First add a top-level helper. It must go **after** the `plugins { ... }` block (a Gradle Kotlin DSL script requires `plugins {}` to be the first statement) — put it right after the `version = ...` line (line 13):

```kotlin
val toolExt = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
```

Then in `jlinkRuntime`'s `commandLine`, change:

```kotlin
            "${launcher.get().metadata.installationPath.asFile.absolutePath}/bin/jlink",
```

to:

```kotlin
            "${launcher.get().metadata.installationPath.asFile.absolutePath}/bin/jlink$toolExt",
```

- [ ] **Step 2: Add the `windowsAppImage` and `windowsSelfContainedDist` tasks**

In `build.gradle.kts`, immediately after the `selfContainedDist` task registration (after line 95), add:

```kotlin
// Windows self-contained app-image: a real qkt.exe plus the bundled jlink runtime,
// so qkt runs on Windows with no system Java. The Windows twin of selfContainedDist.
// jpackage targets the host OS, so a real Windows image is produced only when this
// runs on a Windows JDK (CI: windows-latest). On Linux it yields a host-OS image,
// which is enough to validate the task wiring locally.
val jpackageImageDir = layout.buildDirectory.dir("jpackage")

val windowsAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a Windows app-image (qkt.exe + bundled runtime) via jpackage."
    dependsOn("installDist", jlinkRuntime)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val libDir = layout.buildDirectory.dir("install/qkt/lib")
    val runtimeDir = jlinkRuntimeDir
    val outDir = jpackageImageDir
    val ver = project.version.toString()
    outputs.dir(outDir)
    doFirst {
        val out = outDir.get().asFile
        delete(out) // jpackage refuses to write into an existing app-image dir
        out.mkdirs()
        val jpackage =
            "${launcher.get().metadata.installationPath.asFile.absolutePath}/bin/jpackage$toolExt"
        commandLine(
            jpackage,
            "--type", "app-image",
            "--name", "qkt",
            "--input", libDir.get().asFile.absolutePath,
            "--main-jar", "qkt-$ver.jar",
            "--main-class", "com.qkt.cli.MainKt",
            "--runtime-image", runtimeDir.get().asFile.absolutePath,
            "--dest", out.absolutePath,
        )
    }
}

// Zip the Windows app-image with the editor bundle into the release asset. The
// installer extracts this to %LOCALAPPDATA%\Programs\qkt and points QKT_HOME there,
// so `qkt editor install` finds share/editor (jpackage puts jars under app/, not
// lib/, so EditorPaths' lib-dir fallback does not fire — QKT_HOME is the locator).
tasks.register<Zip>("windowsSelfContainedDist") {
    group = "distribution"
    description = "Zip the Windows app-image (qkt.exe + runtime + editor files)."
    dependsOn(windowsAppImage)
    archiveFileName.set("qkt-${project.version}-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("qkt") {
        from(jpackageImageDir.map { it.dir("qkt") })
        from(rootProject.file("editor")) {
            into("share/editor")
            exclude("**/node_modules/**")
        }
    }
}
```

- [ ] **Step 3: Validate the task wiring locally**

Run: `./gradlew windowsAppImage`
Expected: BUILD SUCCESSFUL; `build/jpackage/qkt/` exists and contains a launcher and a `runtime/` (or, on Linux, `bin/qkt` + `lib/runtime/` — the host-OS layout). This confirms the task graph, the jar name, the runtime hand-off, and that jpackage is on the toolchain. The Windows-specific layout (`qkt.exe` at root) and a real `.exe` are verified by CI in Task 7.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: jpackage windows app-image and self-contained zip (#262)"
```

---

## Task 6: `scripts/install.ps1` PowerShell installer

**Files:**
- Create: `scripts/install.ps1`

- [ ] **Step 1: Write the installer**

Create `scripts/install.ps1`:

```powershell
# qkt - Windows one-line installer.
#
# Fetches the latest GitHub release zip, extracts under %LOCALAPPDATA%\Programs\qkt,
# adds it to the user PATH, and sets QKT_HOME so `qkt editor install` finds its files.
#
# Usage:
#   irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
#
#   # Pin a version:
#   $env:QKT_VERSION='v0.29.14'; irm .../install.ps1 | iex
#
#   # Install from a local zip instead of downloading (used by CI smoke):
#   $env:QKT_ARCHIVE='C:\path\qkt-0.29.14-windows-x64.zip'; irm .../install.ps1 | iex

$ErrorActionPreference = 'Stop'

$Repo    = if ($env:QKT_REPO)    { $env:QKT_REPO }    else { 'elitekaycy/qkt' }
$Version = if ($env:QKT_VERSION) { $env:QKT_VERSION } else { 'latest' }
$Prefix  = if ($env:QKT_PREFIX)  { $env:QKT_PREFIX }  else { Join-Path $env:LOCALAPPDATA 'Programs\qkt' }

function Say  ($m) { Write-Host "==> $m" -ForegroundColor Blue }
function Ok   ($m) { Write-Host "OK  $m" -ForegroundColor Green }
function Warn ($m) { Write-Warning $m }

# --- obtain the zip (download, or use a local archive for testing) ---
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("qkt-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
try {
    if ($env:QKT_ARCHIVE) {
        $zip = $env:QKT_ARCHIVE
        Say "Using local archive $zip"
        if (-not (Test-Path $zip)) { throw "QKT_ARCHIVE not found: $zip" }
    } else {
        if ($Version -eq 'latest') {
            Say "Resolving latest qkt release from $Repo ..."
            $rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest"
            $Version = $rel.tag_name
            if (-not $Version) { throw 'could not determine latest release tag' }
            Ok "Latest release: $Version"
        }
        $verNum = $Version.TrimStart('v')
        $asset  = "qkt-$verNum-windows-x64.zip"
        $url    = "https://github.com/$Repo/releases/download/$Version/$asset"
        $zip    = Join-Path $tmp $asset
        Say "Downloading $asset"
        Invoke-WebRequest -Uri $url -OutFile $zip
    }

    # --- extract (wipe prior install so versions don't accrete) ---
    Say "Extracting to $Prefix"
    if (Test-Path $Prefix) { Remove-Item -Recurse -Force $Prefix }
    New-Item -ItemType Directory -Path $Prefix -Force | Out-Null
    $unpack = Join-Path $tmp 'unpack'
    Expand-Archive -Path $zip -DestinationPath $unpack -Force
    # The zip has a top-level qkt/ directory; move its contents into $Prefix.
    Copy-Item -Path (Join-Path $unpack 'qkt\*') -Destination $Prefix -Recurse -Force

    $exe = Join-Path $Prefix 'qkt.exe'
    if (-not (Test-Path $exe)) { throw "extracted archive did not contain qkt.exe under $Prefix" }
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

# --- QKT_HOME (user env) so `qkt editor install` finds share/editor ---
[Environment]::SetEnvironmentVariable('QKT_HOME', $Prefix, 'User')
$env:QKT_HOME = $Prefix

# --- add to user PATH idempotently ---
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -notlike "*$Prefix*") {
    $newPath = if ([string]::IsNullOrEmpty($userPath)) { $Prefix } else { "$userPath;$Prefix" }
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
    Ok "Added $Prefix to your user PATH"
} else {
    Ok "$Prefix already on your user PATH"
}
$env:Path = "$env:Path;$Prefix"

# --- verify ---
$ver = & $exe --version 2>&1 | Select-Object -First 1
Ok "Installed: $ver"
Write-Host ''
Ok 'qkt installed. Open a NEW terminal, then try:'
Write-Host '    qkt --version'
Write-Host '    qkt --help'
Write-Host ''
Write-Host 'Docs: https://elitekaycy.github.io/qkt/'
```

- [ ] **Step 2: Lint the script syntax (best-effort, on the dev machine)**

If `pwsh` (PowerShell Core) is installed on Linux, run:
Run: `pwsh -NoProfile -Command "[System.Management.Automation.Language.Parser]::ParseFile('scripts/install.ps1', [ref]\$null, [ref]\$null) | Out-Null; 'parse-ok'"`
Expected: prints `parse-ok` (syntax valid). If `pwsh` is absent, skip — the real end-to-end run is exercised on Windows in Task 7.

- [ ] **Step 3: Commit**

```bash
git add scripts/install.ps1
git commit -m "feat: add install.ps1 windows installer (#262)"
```

---

## Task 7: `windows-ci.yml` — PR-gated Windows build + real smoke test

**Files:**
- Create: `.github/workflows/windows-ci.yml`

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/windows-ci.yml`:

```yaml
name: windows-ci

# Builds the Windows self-contained zip on a real Windows runner and smoke-tests
# the actual qkt.exe (and install.ps1) on every PR that touches the build or the
# installer. This is the gate that proves the jpackage packaging and the PowerShell
# installer work before merge — release.yml then reuses the same Gradle task to
# attach the artifact to releases.

on:
  pull_request:
    paths:
      - 'build.gradle.kts'
      - 'gradle/**'
      - 'src/main/**'
      - 'editor/**'
      - 'scripts/install.ps1'
      - '.github/workflows/windows-ci.yml'
  workflow_dispatch:

jobs:
  build-windows:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Build VSCode extension (.vsix)
        working-directory: editor/vscode
        run: npx --yes @vscode/vsce@latest package --no-dependencies

      - name: Build Windows self-contained zip
        run: ./gradlew.bat --no-daemon windowsSelfContainedDist

      - name: Smoke test qkt.exe (version + parse)
        shell: pwsh
        run: |
          $zip = Get-ChildItem build/distributions/qkt-*-windows-x64.zip | Select-Object -First 1
          if (-not $zip) { throw "no windows zip produced" }
          Expand-Archive $zip.FullName -DestinationPath build/smoke -Force
          $exe = "build/smoke/qkt/qkt.exe"
          if (-not (Test-Path $exe)) { throw "qkt.exe missing from zip" }
          & $exe --version
          if ($LASTEXITCODE -ne 0) { throw "qkt --version failed" }
          & $exe parse examples/hedge-straddle/hedge-straddle.qkt
          if ($LASTEXITCODE -ne 0) { throw "qkt parse failed" }

      - name: Smoke test install.ps1 (install from the built zip, run installed exe)
        shell: pwsh
        run: |
          $zip = (Get-ChildItem build/distributions/qkt-*-windows-x64.zip | Select-Object -First 1).FullName
          $env:QKT_ARCHIVE = $zip
          $env:QKT_PREFIX  = "$env:RUNNER_TEMP\qkt-install\qkt"
          ./scripts/install.ps1
          $installed = "$env:RUNNER_TEMP\qkt-install\qkt\qkt.exe"
          if (-not (Test-Path $installed)) { throw "install.ps1 did not place qkt.exe" }
          & $installed --version
          if ($LASTEXITCODE -ne 0) { throw "installed qkt --version failed" }
```

- [ ] **Step 2: Verify YAML is well-formed**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/windows-ci.yml')); print('yaml-ok')"`
Expected: prints `yaml-ok`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/windows-ci.yml
git commit -m "ci: build and smoke-test windows package on windows-latest (#262)"
```

- [ ] **Step 4: Push the branch and open the PR so windows-ci runs**

```bash
git push -u origin issue262-windows-install
gh pr create --base dev --title "Windows install: full parity (#262)" --body "Refs #262. Implements the Windows install per docs/superpowers/specs/2026-06-05-windows-install-design.md: UserDirs native paths, jpackage self-contained zip, install.ps1, winget, CI smoke."
```

Expected: the `windows-ci` check appears on the PR and goes green (real `qkt.exe --version`, `qkt parse`, and `install.ps1` all pass on `windows-latest`). **This is the proof the packaging + installer work.** If `windows-ci` fails on a `NoClassDefFoundError`, the jpackage classpath needs the lib jars — see the spec's Risk #1; fix by adding `--add-modules`/manifest `Class-Path` and re-push.

---

## Task 8: Refactor `release.yml` to attach the Windows zip atomically

The current `release.yml` builds + publishes the release in one ubuntu job. Adding a parallel Windows job that uploads to the same release races on release creation and leaves a window where winget (Task 9) sees a release without the Windows asset. Refactor into three jobs — `build-linux`, `build-windows`, `publish` — that upload build artifacts and then publish once, atomically, with every asset present.

**Files:**
- Modify: `.github/workflows/release.yml` (full rewrite of the `jobs:` block; header comment retained)

- [ ] **Step 1: Rewrite `release.yml`**

Replace the entire `jobs:` section (lines 25-117) with:

```yaml
jobs:
  build-linux:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Build VSCode extension (.vsix) into editor/vscode/
        working-directory: editor/vscode
        run: npx --yes @vscode/vsce@latest package --no-dependencies

      - name: Build distribution tarball
        run: ./gradlew --no-daemon distTar

      - name: Build self-contained linux-x64 bundle (#57)
        run: ./gradlew --no-daemon selfContainedDist

      - name: Verify tarball + self-contained bundle contents
        run: |
          set -e
          tar=$(ls build/distributions/qkt-*.tar | head -1)
          sc=$(ls build/distributions/qkt-*-linux-x64.tar.gz | head -1)
          tar -tf "$tar" | grep -q '/bin/qkt$'          || { echo "::error::tarball missing bin/qkt"; exit 1; }
          tar -tf "$tar" | grep -qE 'share/editor/vscode/qkt-[0-9].*\.vsix$' \
            || { echo "::error::tarball missing share/editor/*.vsix"; exit 1; }
          tar -tzf "$sc" | grep -q '/bin/qkt$'           || { echo "::error::bundle missing bin/qkt"; exit 1; }
          tar -tzf "$sc" | grep -q '/runtime/bin/java$'  || { echo "::error::bundle missing runtime/bin/java"; exit 1; }

      - uses: actions/upload-artifact@v4
        with:
          name: linux-dist
          path: |
            build/distributions/qkt-*.tar
            build/distributions/qkt-*-linux-x64.tar.gz
          if-no-files-found: error

  build-windows:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Build VSCode extension (.vsix)
        working-directory: editor/vscode
        run: npx --yes @vscode/vsce@latest package --no-dependencies

      - name: Build Windows self-contained zip
        run: ./gradlew.bat --no-daemon windowsSelfContainedDist

      - name: Verify zip has qkt.exe and runtime
        shell: pwsh
        run: |
          $zip = Get-ChildItem build/distributions/qkt-*-windows-x64.zip | Select-Object -First 1
          if (-not $zip) { throw "no windows zip produced" }
          Add-Type -AssemblyName System.IO.Compression.FileSystem
          $names = [System.IO.Compression.ZipFile]::OpenRead($zip.FullName).Entries.FullName
          if (-not ($names -contains 'qkt/qkt.exe')) { throw "zip missing qkt/qkt.exe" }
          if (-not ($names -like 'qkt/runtime/*'))   { throw "zip missing qkt/runtime" }

      - uses: actions/upload-artifact@v4
        with:
          name: windows-dist
          path: build/distributions/qkt-*-windows-x64.zip
          if-no-files-found: error

  publish:
    needs: [build-linux, build-windows]
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/download-artifact@v4
        with:
          path: dist
          merge-multiple: true

      - name: Create + publish the release with all assets
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ github.ref_name }}
          GH_REPO: ${{ github.repository }}
        run: |
          set -e
          assets=$(ls dist/qkt-*.tar dist/qkt-*-linux-x64.tar.gz dist/qkt-*-windows-x64.zip)
          echo "attaching:"; echo "$assets"
          if gh release view "$TAG" >/dev/null 2>&1; then
            echo "release $TAG exists; uploading assets"
            gh release upload "$TAG" $assets --clobber
          else
            echo "creating release $TAG"
            gh release create "$TAG" $assets --title "$TAG" --generate-notes
          fi
```

Note: `permissions: contents: write` moves onto the `publish` job (the build jobs don't need it). The top-level `permissions:` block (lines 18-19) can stay or be removed; leaving it is harmless. Keep the header comment block (lines 1-12) and the `on:` / `concurrency:` blocks unchanged.

- [ ] **Step 2: Verify YAML is well-formed**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml')); print('yaml-ok')"`
Expected: prints `yaml-ok`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: attach windows zip to releases via atomic publish (#262)"
```

---

## Task 9: winget automation + bootstrap doc

**Files:**
- Create: `.github/workflows/winget.yml`
- Create: `docs/how-to/winget-bootstrap.md`

- [ ] **Step 1: Write the winget release workflow**

Create `.github/workflows/winget.yml`:

```yaml
name: winget

# Submits a version-bump PR to microsoft/winget-pkgs whenever a release is
# published, computing the installer SHA from the release's windows-x64 zip.
# One-time bootstrap (the first manifest) is manual — see
# docs/how-to/winget-bootstrap.md. Requires:
#   - a fork of microsoft/winget-pkgs under the token owner
#   - secret WINGET_TOKEN: a classic PAT with public_repo scope

on:
  release:
    types: [released]

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: vedantmgoyal2009/winget-releaser@v2
        with:
          identifier: elitekaycy.qkt
          installers-regex: 'windows-x64\.zip$'
          token: ${{ secrets.WINGET_TOKEN }}
```

- [ ] **Step 2: Verify YAML is well-formed**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/winget.yml')); print('yaml-ok')"`
Expected: prints `yaml-ok`.

- [ ] **Step 3: Write the bootstrap doc**

Create `docs/how-to/winget-bootstrap.md`:

```markdown
# Publishing qkt to winget

qkt ships a winget package (`elitekaycy.qkt`) so Windows users can run
`winget install elitekaycy.qkt`. Per-release updates are automated by
`.github/workflows/winget.yml`; the first submission is a one-time manual step.

## One-time bootstrap

1. Fork `microsoft/winget-pkgs` under the account that owns `WINGET_TOKEN`.
2. Create a classic Personal Access Token with the `public_repo` scope and add it
   to the qkt repo secrets as `WINGET_TOKEN`.
3. After the first release that includes `qkt-X.Y.Z-windows-x64.zip`, create the
   initial manifest from a Windows machine (or any host with winget tooling):

   ```powershell
   winget install wingetcreate
   wingetcreate new https://github.com/elitekaycy/qkt/releases/download/vX.Y.Z/qkt-X.Y.Z-windows-x64.zip
   ```

   When prompted: PackageIdentifier `elitekaycy.qkt`, installer type `zip`, nested
   installer type `portable`, nested file `qkt\qkt.exe` with command alias `qkt`.
   Submit the generated PR to `microsoft/winget-pkgs`.

## Thereafter

Every published GitHub release triggers `winget.yml`, which opens the version-bump
PR automatically. No manual action needed once the package exists.

## Install (end users)

```powershell
winget install elitekaycy.qkt
```
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/winget.yml docs/how-to/winget-bootstrap.md
git commit -m "ci: auto-submit winget package on release (#262)"
```

---

## Task 10: README + docs Windows section

**Files:**
- Modify: `README.md:89` (the roadmap caveat) + the Install section
- Create: `docs/how-to/install-windows.md`
- Modify: `docs/` nav if the site uses an explicit nav (check `mkdocs.yml`)

- [ ] **Step 1: Replace the README Windows caveat with a real Windows install block**

In `README.md`, replace line 89:

```markdown
> Windows: a native install path is on the roadmap. For now, use Docker or run from source under WSL2.
```

with:

```markdown
### Windows

```powershell
# winget (recommended)
winget install elitekaycy.qkt

# or the one-line installer
irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
```

Both install a self-contained build (bundled Java runtime — no prerequisites). Open a new terminal, then `qkt --version`.
```

- [ ] **Step 2: Write the Windows how-to page**

Create `docs/how-to/install-windows.md`:

```markdown
# Install qkt on Windows

qkt ships a self-contained Windows build — `qkt.exe` plus a bundled Java runtime,
so there are no prerequisites to install.

## winget (recommended)

```powershell
winget install elitekaycy.qkt
```

## One-line installer

```powershell
irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
```

Pin a version or change the location:

```powershell
$env:QKT_VERSION='v0.29.14'; irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
```

Open a **new** terminal afterward (so the updated PATH takes effect), then:

```powershell
qkt --version
qkt --help
```

## Where qkt stores things on Windows

- State (daemon runtime, logs, persisted engine state): `%LOCALAPPDATA%\qkt`
- Config (`qkt.config.yaml`, editor manifest): `%APPDATA%\qkt`

## Live trading

The live daemon connects to an MT5 gateway over HTTP, exactly as on Linux — set
`gatewayUrl` in your `qkt.config.yaml`. The gateway is a separate service (it can
run natively against a MetaTrader 5 terminal on the same Windows machine). See the
live-trading guide for gateway setup.
```

- [ ] **Step 3: Add the page to the docs nav if one is declared**

Run: `grep -n "how-to" mkdocs.yml | head`
If `mkdocs.yml` has an explicit `nav:` listing how-to pages, add `- Install on Windows: how-to/install-windows.md` alongside the others, matching the existing indentation. If the nav is auto-generated (no explicit how-to entries), skip.

- [ ] **Step 4: Build the docs to verify (if the docs venv exists)**

Run: `. .venv-docs/bin/activate 2>/dev/null && mkdocs build --strict 2>&1 | tail -5 || echo "skip: no docs venv"`
Expected: `Documentation built` with no warnings, or the skip message. If `--strict` flags the new page as not in nav and the site uses an explicit nav, add it (Step 3); if the site auto-discovers pages, the warning is benign.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/how-to/install-windows.md mkdocs.yml
git commit -m "docs: windows install instructions (#262)"
```

---

## Final verification

- [ ] **Push and confirm CI is green**

```bash
git push
gh pr checks --watch
```

Expected: `check` (ubuntu: build + ktlint + unit tests, including the new `UserDirsTest` and updated config/editor tests) green, and `windows-ci` (real `qkt.exe` + `install.ps1` smoke on `windows-latest`) green.

- [ ] **Merge to `dev`** once both checks pass (squash per repo convention). `release.yml` + `winget.yml` activate on the next `v*` tag; winget's first manifest is the manual bootstrap in `docs/how-to/winget-bootstrap.md`.

---

## Notes for the implementer

- **ktlint:** runs in `check.yml`. The recurring failure in this repo is "needs a blank line before a comment" — leave a blank line before any standalone `//` comment, including inside the new Gradle tasks.
- **CI over local build:** don't block on a full `./gradlew build` locally (memory: qkt pushes and lets CI verify). Run the targeted `--tests` filters for TDD; let `check.yml`/`windows-ci` do the full verification.
- **Don't touch** the untracked `docs/superpowers/plans/2026-05-26-issue139-multi-mt5.md` — it's a pre-existing artifact, not part of this work.
- **jpackage classpath (spec Risk #1):** if `windows-ci` smoke throws `NoClassDefFoundError`, jpackage didn't put all lib jars on the classpath. Fix by adding the lib jars to the jar manifest `Class-Path` (in `build.gradle.kts` `tasks.jar`) or via jpackage `--java-options -cp`. This is the one packaging unknown; the CI smoke is its guard.
```
