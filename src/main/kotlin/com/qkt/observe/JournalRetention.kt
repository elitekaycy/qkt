package com.qkt.observe

import com.qkt.common.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Deletes day-partitioned journal files older than a retention window.
 *
 * The engine audit journal and the MT5 transport journal write one
 * `<prefix>-YYYY-MM-DD.jsonl` (plus an optional `.dropped` marker) per UTC day under
 * `<root>/<owner>/` and never delete anything themselves; on a busy live host that is
 * hundreds of megabytes a day with no ceiling. [sweep] removes every day-file whose date is
 * before `today - retentionDays`; today's and recent files are never touched, so an in-flight
 * golden capture or parity replay over the retained window is unaffected.
 *
 * Cold path: runs once at daemon start and once per day on its own daemon thread.
 *
 * ```
 * val retention = JournalRetention(listOf(stateRoot.resolve("audit-journal")), retentionDays = 14, clock)
 * retention.start()   // sweeps now, then every 24h
 * retention.close()
 * ```
 */
class JournalRetention(
    private val roots: List<Path>,
    private val retentionDays: Int,
    private val clock: Clock,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(JournalRetention::class.java)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "qkt-journal-retention").apply { isDaemon = true }
        }

    /** Sweep now, then once per day. A retention of zero or less disables sweeping entirely. */
    fun start() {
        if (retentionDays <= 0) return
        executor.scheduleAtFixedRate(::sweepSafely, 0L, 1L, TimeUnit.DAYS)
    }

    private fun sweepSafely() {
        runCatching { sweep() }.onFailure { log.error("journal retention sweep failed", it) }
    }

    /** Delete every day-file older than the retention cutoff; returns the number of files removed. */
    fun sweep(): Int {
        if (retentionDays <= 0) return 0
        val cutoff =
            Instant
                .ofEpochMilli(
                    clock.now(),
                ).atZone(ZoneOffset.UTC)
                .toLocalDate()
                .minusDays(retentionDays.toLong())
        var removed = 0
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root, 2).use { stream ->
                for (file in stream.filter { Files.isRegularFile(it) }) {
                    val day = dayOf(file.fileName.toString()) ?: continue
                    if (day.isBefore(cutoff) && Files.deleteIfExists(file)) removed++
                }
            }
        }
        if (removed > 0) log.info("journal retention removed {} file(s) older than {}", removed, cutoff)
        return removed
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        private val DAY_FILE = Regex("""^[a-z]+-(\d{4}-\d{2}-\d{2})\.(jsonl|dropped)$""")

        fun dayOf(fileName: String): LocalDate? {
            val match = DAY_FILE.matchEntire(fileName) ?: return null
            return runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
        }
    }
}
