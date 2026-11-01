package com.qkt.marketdata.store

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Fetches one day by running an external script with `SYMBOL YYYY-MM-DD TARGET_PATH`.
 *
 * The script's extension picks how it runs: `.cmd`, `.bat` and `.exe` start directly, `.ps1` runs
 * under PowerShell, and anything else (such as `scripts/fetch-dukascopy.sh`) runs under `bash`,
 * which on Windows means Git Bash or WSL.
 */
class ScriptDataFetcher(
    private val script: Path,
) : DataFetcher {
    init {
        require(Files.exists(script)) {
            "ScriptDataFetcher: script not found at $script (working dir: ${Path.of("").toAbsolutePath()}); " +
                "supply an absolute path"
        }
    }

    override fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    ) {
        Files.createDirectories(target.parent)
        val command = commandFor(script) + listOf(symbol, day.toString(), target.toString())
        val process =
            try {
                ProcessBuilder(command).inheritIO().start()
            } catch (e: IOException) {
                throw IllegalStateException(
                    "could not start fetcher script ${command.first()} for $script: ${e.message}. " +
                        "A .sh script needs bash on PATH (on Windows: Git Bash or WSL); " +
                        "a .cmd, .bat or .ps1 script runs without it",
                    e,
                )
            }
        val rc = process.waitFor()
        check(rc == 0) {
            "fetcher script failed: rc=$rc symbol=$symbol day=$day script=$script"
        }
        check(Files.exists(target)) {
            "fetcher script exited 0 but produced no file: $target"
        }
    }

    companion object {
        /** The command that runs [script], before its arguments. */
        internal fun commandFor(script: Path): List<String> {
            val name = script.fileName.toString().lowercase()
            return when {
                name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".exe") -> listOf(script.toString())
                name.endsWith(
                    ".ps1",
                ) -> listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                else -> listOf("bash", script.toString())
            }
        }

        fun dukascopy(scriptPath: Path): ScriptDataFetcher = ScriptDataFetcher(scriptPath)
    }
}
