# Windows Install (Full Parity) — Design

**Goal:** Let a Windows user install qkt and run it fully — backtest and the live daemon — exactly as on Linux, with a bundled runtime (no Java prerequisite), via a PowerShell one-liner and `winget`.

**Architecture:** The qkt engine is already cross-platform JVM code; the only OS-specific surface is the bundled runtime, the launcher, the filesystem paths it writes to, and the installer. We add a Windows self-contained artifact built by `jpackage` on a `windows-latest` CI runner, two install paths (`install.ps1` + winget), and a small `UserDirs` helper so state/config land in native Windows directories. Linux/Mac behavior is unchanged.

**Tech Stack:** Kotlin/JVM 21, Gradle (`application` + custom tasks), JDK `jpackage` + `jlink`, GitHub Actions (`windows-latest`), PowerShell, winget (`winget-releaser` action).

---

## Background — current state

- **Engine is portable already.** All paths go through `java.nio.file.Path` with `user.home` fallbacks. Nothing hard-crashes on Windows today; the defaults are merely Unix-flavored (`~/.local/state/qkt`, `~/.qkt`, config search includes a `/etc/qkt` entry that simply never matches on Windows).
- **The MT5 gateway is a separate HTTP service** (`mt5-gateway`), not shipped by this repo on any OS. qkt reaches it at a configurable `gatewayUrl`. "Everything running" on Windows therefore means the daemon runs and connects to a gateway — the same contract as Linux. The gateway is out of scope here (on Windows it can run natively against a native MT5 terminal; that is documented separately, not packaged by this work).
- **Linux artifacts today:** `selfContainedDist` (Gradle task) builds `qkt-X.Y.Z-linux-x64.tar.gz` = app (`installDist`) + a jlink minimal runtime; `scripts/install.sh` (`curl|bash`) installs it to `~/.local`. The jlink module set is `java.base,java.logging,java.naming,java.xml,jdk.httpserver,jdk.crypto.ec,jdk.unsupported`.
- **Editor bundle:** the distribution carries `editor/` under `share/editor/`; `EditorPaths.bundledEditorRoot` locates it via `QKT_HOME/share/editor`, else `<jar-dir>/../share/editor` when the jar sits in a `lib/` dir, else `<cwd>/editor`.

## Scope

**In scope**
- A Windows self-contained zip (`qkt-X.Y.Z-windows-x64.zip`) attached to each GitHub release.
- `scripts/install.ps1` PowerShell one-liner installer.
- A winget package (`elitekaycy.qkt`) auto-submitted on each release.
- `UserDirs` helper → native Windows state/config dirs; Linux/Mac unchanged.
- CI: a `windows-latest` build+smoke job in `release.yml`.
- README + docs Windows section (drop the "roadmap" caveat).

**Out of scope**
- The MT5 gateway (separate component, any OS).
- macOS native packaging (Mac still uses the JRE-less tarball + system JDK; unchanged).
- An `.msi`/signed installer (no code signing cert; app-image zip + winget portable is the target). Code signing is a possible later follow-up.

---

## Component 1 — `UserDirs` (engine; the only product-code change)

A small injectable helper that returns OS-correct base directories, mirroring the existing `EditorDetector` DI shape (`env`, `home`, `osName` constructor params defaulting to `System.*`).

**New file:** `src/main/kotlin/com/qkt/cli/UserDirs.kt`

```kotlin
package com.qkt.cli

import java.nio.file.Path

/**
 * OS-correct base directories for qkt's state and config.
 *
 * Windows uses the platform conventions (%LOCALAPPDATA%, %APPDATA%); Linux and
 * macOS keep the XDG / dotfile layout qkt has always used. Injectable for tests:
 * drive [osName] + [env] to assert resolution without running on the target OS.
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

**Rewiring at three chokepoints** (each is the single resolution site):

1. **`StateDir.resolve(override)`** — preserve override precedence, add the platform default via `UserDirs`:
   ```
   override != null                  -> Path.of(override)          // --state-dir
   getenv("QKT_STATE_DIR") != null   -> Path.of(...)               // explicit env override (all OSes)
   else                              -> UserDirs().stateHome()
   ```
   This keeps Linux output byte-identical when `XDG_STATE_HOME`/home are unchanged (UserDirs reproduces the old `XDG_STATE_HOME/qkt` → `~/.local/state/qkt` ladder), and adds `%LOCALAPPDATA%\qkt` on Windows. The old inline `XDG_STATE_HOME`/`~/.local/state` branch is removed in favor of `UserDirs`.

2. **`Config.defaultSearchPaths()`** — keep `./qkt.config.yaml` first; add the OS-correct user config dir via `UserDirs`; **retain `/etc/qkt` on non-Windows** and the legacy `~/.qkt`:
   ```
   val paths = mutableListOf(
     Path.of("./qkt.config.yaml"),
     UserDirs().configHome().resolve("qkt.config.yaml"),   // %APPDATA%\qkt\... on Win; ~/.config/qkt/... on *nix
   )
   if (!UserDirs().isWindows) paths += Path.of("/etc/qkt/qkt.config.yaml")  // system-wide / container mount
   paths += home.resolve(".qkt/qkt.config.yaml")           // legacy ~/.qkt fallback, retained
   ```
   On Linux this is purely *additive* (gains `~/.config/qkt/qkt.config.yaml`; `/etc/qkt` and `~/.qkt` unchanged) — no regression. On Windows the user config dir is `%APPDATA%\qkt` and the meaningless `/etc/qkt` literal is omitted. **Verified safe** — qkt-prod passes `--config /etc/qkt/qkt.config.yaml` explicitly (`../qkt-prod/compose.yml:101`), so the default search list is irrelevant to prod regardless.

3. **`EditorManifest.defaultPath(env, home)`** — route through `UserDirs`:
   ```
   UserDirs(env = env, home = home).configHome().resolve("editor-install.json")
   ```
   On Linux this changes the manifest location from `~/.config/qkt/editor-install.json` to the same path (no change). On Windows it becomes `%APPDATA%\qkt\editor-install.json`.

**Tests** (`src/test/kotlin/com/qkt/cli/UserDirsTest.kt`, real unit tests, no Windows host needed):
- Windows: `osName="windows 11"`, `env={LOCALAPPDATA: "C:\\Users\\me\\AppData\\Local", APPDATA: "C:\\Users\\me\\AppData\\Roaming"}` → `stateHome()` ends `qkt` under LocalAppData; `configHome()` under Roaming.
- Windows fallback: LOCALAPPDATA/APPDATA absent → derived from `home\AppData\Local|Roaming`.
- Linux with `XDG_STATE_HOME`/`XDG_CONFIG_HOME` set → honored.
- Linux without XDG → `~/.local/state/qkt`, `~/.config/qkt`.
- Plus targeted tests that `StateDir.resolve` still honors `--state-dir` and `QKT_STATE_DIR` first on both OSes.

---

## Component 2 — Windows packaging via `jpackage` app-image

**Decision:** use `jpackage --type app-image` (JDK native packaging) rather than the Gradle `distZip` `.bat`. It emits a real `qkt.exe` + a bundled minimal runtime in one step. A real executable is what winget's portable install type shims onto PATH cleanly; a `.bat` is fragile there. The exe also bakes in the runtime, so there is no `JAVA_HOME` wrapper to manage. It reuses the **same jlink module set** already maintained for Docker/Linux — no second module list to keep in sync.

**Rejected alternative:** Gradle `distZip` (the `.bat` launcher already exists, zero build code). Rejected because winget would then rely on a `.bat` shim and the install would still need the `JAVA_HOME→runtime` dance. Not worth it given winget is first-class.

**New Gradle task** `windowsAppImage` (only meaningful on a Windows JDK; CI runs it on `windows-latest`):
- `dependsOn("installDist", jlinkRuntime)` — `installDist` yields `build/install/qkt/lib/*.jar`; `jlinkRuntime` yields `build/jlink-runtime` (on a Windows toolchain this is a Windows runtime).
- Invoke jpackage:
  ```
  jpackage \
    --type app-image \
    --name qkt \
    --input        build/install/qkt/lib \
    --main-jar     qkt-<version>.jar \
    --main-class   com.qkt.cli.MainKt \
    --runtime-image build/jlink-runtime \
    --dest         build/jpackage
  ```
  Produces `build/jpackage/qkt/qkt.exe`, `build/jpackage/qkt/runtime/`, and `build/jpackage/qkt/app/` (the jars).
- **Bundle the editor files:** copy `editor/` (including the built `.vsix`) into `build/jpackage/qkt/share/editor` so `qkt editor install` works from the install (located via `QKT_HOME`, set by the installer — see Component 3).
- **Zip:** a `Zip` task `windowsSelfContainedDist` packs `build/jpackage/qkt/**` into `build/distributions/qkt-<version>-windows-x64.zip` with a top-level `qkt/` directory (so extraction yields `qkt\qkt.exe`, `qkt\runtime\`, `qkt\share\editor\`).

**Classpath note / guard:** jpackage writes the app classpath into `app\qkt.cfg` from the jars in `--input`. The CI smoke test (`qkt.exe --version` + a tiny backtest) is the guard that the classpath is assembled correctly; a `NoClassDefFoundError` there means the plan adds the lib jars to the jar manifest `Class-Path` (or the cfg) and re-tests. This is the one packaging unknown and is verified empirically in CI, not assumed.

---

## Component 3 — `scripts/install.ps1` (PowerShell one-liner)

Structural twin of `scripts/install.sh`. Usage:
```
irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
# pin:    $env:QKT_VERSION='v0.29.14'; irm .../install.ps1 | iex
```
Behavior:
1. Resolve version — `latest` (GitHub API `releases/latest`) unless `$env:QKT_VERSION` is set.
2. Asset name `qkt-<num>-windows-x64.zip`; URL `https://github.com/elitekaycy/qkt/releases/download/<tag>/<asset>`.
3. Download to a temp dir (`Invoke-WebRequest`), extract (`Expand-Archive`) into `%LOCALAPPDATA%\Programs\qkt` (override via `$env:QKT_PREFIX`). Wipe `qkt.exe`/`runtime`/`app`/`share` first so versions don't accrete.
4. **Set `QKT_HOME`** to the install dir (user env, `setx QKT_HOME` / `[Environment]::SetEnvironmentVariable(...,'User')`) — required because the jpackage app-image puts jars in `app\` not `lib\`, so `EditorPaths.appHomeFromJar()` (which keys off a `lib` dir) will not fire; `QKT_HOME/share/editor` is the working locator.
5. **Add the install dir to user PATH** idempotently (only if absent), so `qkt` resolves in new shells. (`qkt.exe` lives at the install root.)
6. Verify: run `<install>\qkt.exe --version`; print success + a "open a new terminal for PATH" note.

Guard rails mirroring `install.sh`: fail loud if download fails or the zip lacks `qkt.exe`; clear messaging; no admin rights required (user-scoped PATH + `%LOCALAPPDATA%`).

---

## Component 4 — winget

- **Package identifier:** `elitekaycy.qkt`. Installer is the release zip with `InstallerType: zip`, `NestedInstallerType: portable`, `NestedInstallerFiles: [{ RelativeFilePath: "qkt\\qkt.exe", PortableCommandAlias: "qkt" }]`. winget creates a `qkt` shim on PATH and manages add/remove.
- **Bootstrap (one-time, manual):** create the first manifest with `wingetcreate new` pointing at the first published `windows-x64.zip`, submit the PR to `microsoft/winget-pkgs`. Requires a fork of `winget-pkgs` under `elitekaycy` and a classic PAT (`public_repo`).
- **Per-release automation:** add `winget-releaser` GitHub Action (new workflow `.github/workflows/winget.yml`, trigger `release: [published]`) with `identifier: elitekaycy.qkt`, `installers-regex: 'windows-x64\.zip$'`, `token: ${{ secrets.WINGET_TOKEN }}`. On each published release it computes the SHA256 from the asset and opens the version-bump PR automatically.
- **Honest caveat:** winget cannot be fully proven until a Windows release zip exists for it to ingest, and the *first* submission is a manual `wingetcreate new`. So winget lands "wired; first PR manual; auto thereafter."

---

## Component 5 — CI + docs

**`release.yml`** — add a parallel job `build-windows` (`runs-on: windows-latest`):
- `actions/checkout`, `actions/setup-java@v4` (temurin 21 — includes `jpackage`), `gradle/actions/setup-gradle`, `actions/setup-node` (for the `.vsix`).
- Build the `.vsix` (`npx --yes @vscode/vsce package --no-dependencies` in `editor/vscode`), then `./gradlew.bat --no-daemon windowsSelfContainedDist`.
- **Smoke test on real Windows:** unzip the artifact to a temp dir; run `qkt.exe --version` (assert it prints the version) and a minimal `qkt backtest` against a tiny fixture (assert exit 0). No mocks — a real `.exe` on a real Windows runner.
- Verify the zip contains `qkt\qkt.exe`, `qkt\runtime\`, and `qkt\share\editor\...\*.vsix`.
- Upload the zip to the same release (`gh release upload <tag> <zip> --clobber`), matching the existing auto-publish flow (the release is created by the ubuntu job; the windows job only uploads — ordering handled by `gh release upload` retrying/clobbering, or by gating on the release existing).

**Prod-config check — done.** qkt-prod passes `--config /etc/qkt/qkt.config.yaml` explicitly (`../qkt-prod/compose.yml:101`), and Component 1 retains `/etc/qkt` in the non-Windows default search regardless, so there is no prod impact. No further action.

**Docs:**
- `README.md` — replace the "Windows: native install path is on the roadmap" note with a real Windows section: winget one-liner + the `irm | iex` one-liner.
- `docs/` — a Windows install how-to page; note the gateway is a separate service (link to the live/gateway docs).

---

## Cross-platform path behavior (summary)

| Concern | Linux/Mac (unchanged) | Windows (new) |
|---|---|---|
| Daemon state dir | `$XDG_STATE_HOME/qkt` or `~/.local/state/qkt` | `%LOCALAPPDATA%\qkt` |
| Config search | `./qkt.config.yaml`, `~/.config/qkt/...`, `/etc/qkt/...`, `~/.qkt/...` | `.\qkt.config.yaml`, `%APPDATA%\qkt\...`, `~\.qkt\...` |
| Editor manifest | `~/.config/qkt/editor-install.json` | `%APPDATA%\qkt\editor-install.json` |
| Explicit overrides | `--state-dir`, `QKT_STATE_DIR`, `--config` honored first | identical |
| Data root | `--data-root` / `QKT_DATA_HOME` / `./data` | identical (already portable) |

---

## Testing strategy

- **UserDirs:** real unit tests driving injected `osName`/`env`/`home` for both OS families. Plus StateDir override-precedence tests.
- **Packaging:** verified by the `windows-latest` CI smoke (`qkt.exe --version` + real backtest) and a zip-contents assertion. No mocks, real artifact.
- **install.ps1 / winget:** exercised by the CI build and the first real release; winget's first PR is a manual bootstrap, automated thereafter.

## Risks / open items

1. **jpackage classpath** — whether `--input` jars all land on the runtime classpath is verified by the CI smoke, not assumed; fallback is a jar-manifest `Class-Path`. (Only real packaging unknown.)
2. **`/etc/qkt` config search** — RESOLVED: qkt-prod passes `--config` explicitly (`../qkt-prod/compose.yml:101`), and `/etc/qkt` is retained on non-Windows anyway, so the Linux config search is purely additive. No regression risk.
3. **winget bootstrap** — first manifest is a manual `wingetcreate new`; needs a `winget-pkgs` fork + `WINGET_TOKEN` secret.
4. **No code signing** — `qkt.exe` is unsigned; SmartScreen may warn on first run. Acceptable for v1; signing is a later follow-up.

## Sequencing (single spec, ordered task groups)

1. `UserDirs` + rewiring + tests (standalone, mergeable on its own).
2. `windowsAppImage`/`windowsSelfContainedDist` Gradle tasks.
3. `release.yml` `build-windows` job + smoke.
4. `scripts/install.ps1`.
5. winget workflow + (manual) bootstrap.
6. README + docs.
