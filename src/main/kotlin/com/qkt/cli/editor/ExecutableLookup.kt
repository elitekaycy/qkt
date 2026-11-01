package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Finds a command on `PATH` the way the platform shell would.
 *
 * On Windows a command such as `code` or `npx` is installed as `code.cmd` / `npx.cmd`, next to an
 * extensionless shell script meant for Git Bash or WSL that `CreateProcess` cannot start. So on
 * Windows each `PATHEXT` extension is tried first, and the bare name is never returned.
 */
internal fun findExecutable(
    name: String,
    env: Map<String, String> = System.getenv(),
    windows: Boolean = System.getProperty("os.name", "").lowercase().contains("win"),
): Path? {
    val path = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value ?: return null
    val separator = if (windows) ";" else ":"
    val candidates =
        if (windows) {
            val pathExt =
                env.entries.firstOrNull { it.key.equals("PATHEXT", ignoreCase = true) }?.value
                    ?: ".COM;.EXE;.BAT;.CMD"
            pathExt.split(';').filter { it.isNotBlank() }.map { name + it.lowercase() }
        } else {
            listOf(name)
        }
    for (dir in path.split(separator).filter { it.isNotBlank() }) {
        for (candidate in candidates) {
            val file =
                try {
                    Path.of(dir.trim('"')).resolve(candidate)
                } catch (_: InvalidPathException) {
                    continue
                }
            if (Files.isRegularFile(file) && (windows || Files.isExecutable(file))) return file
        }
    }
    return null
}
