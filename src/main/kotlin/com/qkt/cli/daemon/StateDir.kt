package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class StateDir private constructor(
    val root: Path,
) {
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

    fun logFile(name: String): Path = logsDir.resolve("${name.replace("/", "__")}.log")

    /**
     * Resolves a per-strategy state file path under `<root>/state/<name>/<fileName>`.
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

    companion object {
        fun resolve(override: String? = null): StateDir {
            val root =
                when {
                    override != null -> Path.of(override)
                    System.getenv("QKT_STATE_DIR") != null -> Path.of(System.getenv("QKT_STATE_DIR"))
                    System.getenv("XDG_STATE_HOME") != null -> Path.of(System.getenv("XDG_STATE_HOME"), "qkt")
                    else -> Path.of(System.getProperty("user.home"), ".local", "state", "qkt")
                }
            return StateDir(root)
        }
    }
}
