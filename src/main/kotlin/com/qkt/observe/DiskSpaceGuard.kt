package com.qkt.observe

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Watches free space on the volume holding [root] and raises [onLow] once when it drops
 * below [floorBytes].
 *
 * Disk-full on a live daemon host is an outage with positions open: journals, state
 * persistence, and recovery data all stop writing. This guard turns that cliff into an
 * operator alert weeks ahead. The alert fires once per crossing; it re-arms only after
 * free space recovers to 110% of the floor, so a volume hovering at the floor does not
 * flap an alert on every check.
 *
 * Cold path: checks at start and every [intervalMinutes] on its own daemon thread.
 * A floor of zero or less disables the guard entirely.
 */
class DiskSpaceGuard(
    private val root: Path,
    private val floorBytes: Long,
    private val onLow: (freeBytes: Long, floorBytes: Long) -> Unit,
    private val intervalMinutes: Long = 30L,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(DiskSpaceGuard::class.java)
    private val alerted = AtomicBoolean(false)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "qkt-disk-space-guard").apply { isDaemon = true }
        }

    /** Check now, then every [intervalMinutes]. A floor of zero or less disables the guard. */
    fun start() {
        if (floorBytes <= 0L) return
        executor.scheduleAtFixedRate(::checkSafely, 0L, intervalMinutes, TimeUnit.MINUTES)
    }

    private fun checkSafely() {
        runCatching { check() }.onFailure { log.error("disk space check failed", it) }
    }

    /** Read free space, fire [onLow] on a low crossing; returns free bytes or null if unreadable. */
    fun check(): Long? {
        if (floorBytes <= 0L) return null
        val free =
            try {
                Files.getFileStore(root).usableSpace
            } catch (error: Exception) {
                log.warn("could not read free space for {}: {}", root, error.message)
                return null
            }
        if (free < floorBytes) {
            if (alerted.compareAndSet(false, true)) {
                log.error(
                    "LOW DISK SPACE on {}: {} MB free, floor {} MB — journal and state writes fail when the volume fills",
                    root,
                    free / MIB,
                    floorBytes / MIB,
                )
                onLow(free, floorBytes)
            }
        } else if (free >= floorBytes + floorBytes / 10L) {
            alerted.set(false)
        }
        return free
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        private const val MIB = 1024L * 1024L
    }
}
