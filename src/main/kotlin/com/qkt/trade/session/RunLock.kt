package com.qkt.trade.session

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * An exclusive hold on one run's session directory (`run.lock`), for as long as the session runs. Two
 * processes over one run would both restore its legs, both append to its journal and both drive its
 * exits on one account, so the second is refused. The OS releases the lock when the process dies.
 */
class RunLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(
            sessionDir: Path,
            runId: String,
        ): RunLock {
            Files.createDirectories(sessionDir)
            val path = sessionDir.resolve("run.lock")
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock == null) {
                channel.close()
                error("run $runId is already running (another qkt bot session holds $path); use another --run")
            }
            return RunLock(channel, lock)
        }
    }
}
