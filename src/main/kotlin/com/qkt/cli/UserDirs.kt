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

    private fun localAppData(): Path = envPath("LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local")

    private fun appData(): Path = envPath("APPDATA") ?: home.resolve("AppData").resolve("Roaming")

    private fun envPath(key: String): Path? = env[key]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
}
