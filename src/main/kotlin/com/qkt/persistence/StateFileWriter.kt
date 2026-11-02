package com.qkt.persistence

import com.qkt.common.FileNameSegment
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.slf4j.LoggerFactory

/**
 * Atomic JSON file writer for engine state. Writes happen to a `.tmp` sibling and are
 * atomically renamed over the target via [StandardCopyOption.ATOMIC_MOVE].
 *
 * A crash during write is benign: the old file remains intact until the rename
 * commits, so readers always see a complete (old or new) document — never a torn write.
 *
 * Errors are logged and counted rather than thrown from the write path. Live sessions monitor
 * [failedWrites] and halt new exposure while leaving protective exits active.
 */
internal class StateFileWriter(
    private val rootDir: Path,
    private val slowWriteThresholdMs: Long = 100L,
) {
    private val log = LoggerFactory.getLogger(StateFileWriter::class.java)

    /**
     * One lock per target file. Windows refuses to replace a file another rename is replacing at
     * the same moment (`AccessDeniedException`), so writers to the same target take turns; writes
     * to different targets still run in parallel.
     */
    private val targetLocks = java.util.concurrent.ConcurrentHashMap<Path, Any>()

    val totalWrites: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic
            .AtomicLong(0)
    val totalBytesWritten: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic
            .AtomicLong(0)
    val slowWrites: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic
            .AtomicLong(0)
    val failedWrites: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic
            .AtomicLong(0)
    val consecutiveFailures: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic
            .AtomicLong(0)
    val failureEpisodes: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic
            .AtomicLong(0)

    fun write(
        strategyName: String,
        fileName: String,
        json: String,
    ) {
        val start = System.nanoTime()
        try {
            val dir = ownerDir(strategyName)
            Files.createDirectories(dir)
            val target = dir.resolve(fileName)
            // Unique temp per write. A shared temp lets concurrent writers to the same
            // target collide: one writer's rename consumes the temp another is mid-write on,
            // so the loser's move fails.
            val temp = Files.createTempFile(dir, "$fileName.", ".tmp")
            try {
                // DSYNC forces the bytes to the device before the rename commits — a
                // host crash right after the move can't leave a durable name pointing
                // at non-durable content. Costs one disk flush per state write.
                Files.writeString(
                    temp,
                    json,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.DSYNC,
                )
                synchronized(targetLocks.computeIfAbsent(target) { Any() }) { replace(temp, target) }
            } finally {
                // On success the temp is already renamed away; on failure this reaps the
                // orphan so unique temps don't accumulate.
                Files.deleteIfExists(temp)
            }
            consecutiveFailures.set(0L)
            totalWrites.incrementAndGet()
            totalBytesWritten.addAndGet(json.length.toLong())
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            if (elapsedMs > slowWriteThresholdMs) {
                slowWrites.incrementAndGet()
                log.warn(
                    "StateFileWriter.write slow: {}ms for {}/{} ({} bytes) — threshold {}ms",
                    elapsedMs,
                    strategyName,
                    fileName,
                    json.length,
                    slowWriteThresholdMs,
                )
            }
        } catch (e: Exception) {
            failedWrites.incrementAndGet()
            if (consecutiveFailures.getAndIncrement() == 0L) failureEpisodes.incrementAndGet()
            // ERROR, not warn: a session restarting after persist failures reconciles
            // against stale state — the operator must know the disk is failing NOW.
            log.error("StateFileWriter.write FAILED for $strategyName/$fileName: ${e.message}", e)
        }
    }

    fun read(
        strategyName: String,
        fileName: String,
    ): String? {
        val target = ownerDir(strategyName).resolve(fileName)
        if (!Files.exists(target)) return null
        return try {
            Files.readString(target)
        } catch (e: Exception) {
            throw IllegalStateException("StateFileWriter.read failed for $strategyName/$fileName", e)
        }
    }

    /** The names of [strategyName]'s state files; empty when it has none. */
    fun list(strategyName: String): List<String> {
        val dir = ownerDir(strategyName)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }
    }

    fun recordFailure(
        operation: String,
        error: Throwable,
    ) {
        failedWrites.incrementAndGet()
        if (consecutiveFailures.getAndIncrement() == 0L) failureEpisodes.incrementAndGet()
        log.error("$operation FAILED: ${error.message}", error)
    }

    fun deleteStrategy(strategyName: String) {
        val dir = ownerDir(strategyName)
        if (!Files.exists(dir)) return
        try {
            Files
                .walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { p -> runCatching { Files.deleteIfExists(p) } }
        } catch (e: Exception) {
            log.warn("StateFileWriter.deleteStrategy failed for $strategyName: ${e.message}")
        }
    }

    /** The directory of [strategyName]'s state; a portfolio child id such as `book:a` is encoded on Windows. */
    private fun ownerDir(strategyName: String): Path = rootDir.resolve(FileNameSegment.encode(strategyName))

    /**
     * Renames [temp] over [target]. On Windows a reader or a virus scanner holding [target] open
     * makes the rename fail with `AccessDeniedException` for a moment, so it is retried briefly.
     */
    private fun replace(
        temp: Path,
        target: Path,
    ) {
        var attempt = 0
        while (true) {
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: UnsupportedOperationException) {
                    // ATOMIC_MOVE not supported on this filesystem; fall back to non-atomic.
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: AccessDeniedException) {
                if (++attempt >= REPLACE_ATTEMPTS) throw e
                Thread.sleep(attempt * 10L)
            }
        }
    }

    private companion object {
        const val REPLACE_ATTEMPTS = 5
    }
}
