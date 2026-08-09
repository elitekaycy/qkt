package com.qkt.cli.daemon

import com.qkt.cli.UserDirs
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class StateDir private constructor(
    val root: Path,
) {
    val logsDir: Path = root.resolve("logs")
    val controlPortFile: Path = root.resolve("control.port")

    /** Owner-only bearer token shared by the daemon and local CLI commands. */
    val controlTokenFile: Path = root.resolve("control.token")
    val pidFile: Path = root.resolve("daemon.pid")

    init {
        Files.createDirectories(root)
        Files.createDirectories(logsDir)
    }

    fun readControlPort(): Int? {
        if (!Files.exists(controlPortFile)) return null
        return runCatching { Files.readString(controlPortFile).trim().toInt() }.getOrNull()
    }

    fun deleteControlPort() {
        Files.deleteIfExists(controlPortFile)
    }

    /** Read the daemon bearer token, or null before the daemon has created one. */
    fun readControlToken(): String? {
        if (!Files.isRegularFile(controlTokenFile)) return null
        return Files.readString(controlTokenFile).trim().takeIf { it.isNotEmpty() }
    }

    fun logFile(name: String): Path = logsDir.resolve("${name.replace("/", "__")}.log")

    val stateRoot: Path = root.resolve("state")

    /**
     * Acquires the state directory's process-wide daemon lease, or returns null when
     * another daemon already holds it. The caller must keep the lease open for the
     * daemon's entire lifetime.
     */
    fun acquireDaemonLock(): DaemonInstanceLock? {
        val channel =
            FileChannel.open(
                pidFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
        if (lock == null) {
            channel.close()
            return null
        }
        Files.deleteIfExists(controlPortFile)
        channel.truncate(0)
        val pid = StandardCharsets.UTF_8.encode(ProcessHandle.current().pid().toString())
        while (pid.hasRemaining()) channel.write(pid)
        channel.force(true)
        return DaemonInstanceLock(this, channel, lock)
    }

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
}

/** Exclusive daemon lease for one [StateDir]. */
class DaemonInstanceLock internal constructor(
    private val stateDir: StateDir,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    /** Publishes the control port while this process owns the state directory lease. */
    fun writeControlPort(port: Int) {
        check(lock.isValid) { "daemon instance lock is closed" }
        val tmp = stateDir.controlPortFile.resolveSibling("control.port.tmp")
        Files.writeString(tmp, port.toString())
        Files.move(tmp, stateDir.controlPortFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun close() {
        if (!lock.isValid) return
        val interrupted = Thread.interrupted()
        try {
            stateDir.deleteControlPort()
            channel.truncate(0)
            channel.force(true)
        } finally {
            try {
                if (lock.isValid) lock.release()
            } finally {
                channel.close()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
