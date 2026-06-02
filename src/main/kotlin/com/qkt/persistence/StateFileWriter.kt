package com.qkt.persistence

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
 * Errors are logged but do not throw. Persistence is best-effort; the trading engine
 * keeps running even when the disk is full or read-only.
 */
internal class StateFileWriter(
    private val rootDir: Path,
    private val slowWriteThresholdMs: Long = 100L,
) {
    private val log = LoggerFactory.getLogger(StateFileWriter::class.java)

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

    fun write(
        strategyName: String,
        fileName: String,
        json: String,
    ) {
        val start = System.nanoTime()
        try {
            val dir = rootDir.resolve(strategyName)
            Files.createDirectories(dir)
            val target = dir.resolve(fileName)
            // Unique temp per write. A shared temp lets concurrent writers to the same
            // target collide: one writer's rename consumes the temp another is mid-write on,
            // so the loser's move fails. e.g. two saves of bracket-pairs.json racing under
            // the async persistor's caller-runs fallback.
            val temp = Files.createTempFile(dir, "$fileName.", ".tmp")
            try {
                Files.writeString(
                    temp,
                    json,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                try {
                    Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: UnsupportedOperationException) {
                    // ATOMIC_MOVE not supported on this filesystem; fall back to non-atomic.
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                // On success the temp is already renamed away; on failure this reaps the
                // orphan so unique temps don't accumulate.
                Files.deleteIfExists(temp)
            }
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
            log.warn("StateFileWriter.write failed for $strategyName/$fileName: ${e.message}")
        }
    }

    fun read(
        strategyName: String,
        fileName: String,
    ): String? {
        val target = rootDir.resolve(strategyName).resolve(fileName)
        if (!Files.exists(target)) return null
        return try {
            Files.readString(target)
        } catch (e: Exception) {
            log.warn("StateFileWriter.read failed for $strategyName/$fileName: ${e.message}")
            null
        }
    }

    fun deleteStrategy(strategyName: String) {
        val dir = rootDir.resolve(strategyName)
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
}
