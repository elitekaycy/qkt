package com.qkt.observe

import com.qkt.common.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import org.slf4j.LoggerFactory

/**
 * Ages day-partitioned journal files: compresses closed days, deletes expired days.
 *
 * The engine audit journal and the MT5 transport journal write one
 * `<prefix>-YYYY-MM-DD.jsonl` (plus an optional `.dropped` marker) per UTC day under
 * `<root>/<owner>/` and never delete anything themselves; on a busy live host that is
 * hundreds of megabytes a day with no ceiling. [sweep] applies two policies per file, both
 * keyed on the date in the file name:
 *
 *  - **delete**: any day-file (plain, compressed, or marker) dated before
 *    `today - retentionDays` is removed. `retentionDays <= 0` keeps everything.
 *  - **compress**: any remaining `.jsonl` file dated before `today - compressAfterDays` is
 *    gzipped in place (written to a temp file, atomically moved to `<name>.jsonl.gz`, then
 *    the original deleted) — JSONL journals shrink roughly 10x. `compressAfterDays <= 0`
 *    disables compression. The default daemon setting of 1 means today's and yesterday's
 *    files are always left plain, so an in-flight golden capture or parity replay over the
 *    recent window reads uncompressed data; readers of older files accept `.jsonl.gz`.
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
    private val compressAfterDays: Int = 0,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(JournalRetention::class.java)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "qkt-journal-retention").apply { isDaemon = true }
        }

    /** Outcome of one [sweep]: how many files were deleted and how many were gzipped. */
    data class Result(
        val removed: Int,
        val compressed: Int,
    )

    /** Sweep now, then once per day. Disabled entirely when neither policy is active. */
    fun start() {
        if (retentionDays <= 0 && compressAfterDays <= 0) return
        executor.scheduleAtFixedRate(::sweepSafely, 0L, 1L, TimeUnit.DAYS)
    }

    private fun sweepSafely() {
        runCatching { sweep() }.onFailure { log.error("journal retention sweep failed", it) }
    }

    /** Apply the delete and compress policies to every day-file under the configured roots. */
    fun sweep(): Result {
        val today =
            Instant
                .ofEpochMilli(clock.now())
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
        val deleteCutoff = if (retentionDays > 0) today.minusDays(retentionDays.toLong()) else null
        val compressCutoff = if (compressAfterDays > 0) today.minusDays(compressAfterDays.toLong()) else null
        var removed = 0
        var compressed = 0
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root, 2).use { stream ->
                for (file in stream.filter { Files.isRegularFile(it) }) {
                    val name = file.fileName.toString()
                    val day = dayOf(name) ?: continue
                    if (deleteCutoff != null && day.isBefore(deleteCutoff)) {
                        if (Files.deleteIfExists(file)) removed++
                    } else if (compressCutoff != null && name.endsWith(".jsonl") && day.isBefore(compressCutoff)) {
                        if (compress(file)) compressed++
                    }
                }
            }
        }
        if (removed > 0 || compressed > 0) {
            log.info(
                "journal retention removed {} file(s) older than {}, compressed {} file(s) older than {}",
                removed,
                deleteCutoff,
                compressed,
                compressCutoff,
            )
        }
        return Result(removed, compressed)
    }

    private fun compress(file: Path): Boolean {
        val target = file.resolveSibling("${file.fileName}.gz")
        if (Files.exists(target)) {
            log.warn("journal retention skipping {}: {} already exists", file, target.fileName)
            return false
        }
        val temp = file.resolveSibling(".${file.fileName}.gz.tmp")
        return try {
            GZIPOutputStream(
                Files.newOutputStream(
                    temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ),
            ).use { out -> Files.copy(file, out) }
            runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(file)
            true
        } catch (error: Exception) {
            log.error("journal retention could not compress {}: {}", file, error.message)
            runCatching { Files.deleteIfExists(temp) }
            false
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        private val DAY_FILE = Regex("""^[a-z]+-(\d{4}-\d{2}-\d{2})\.(jsonl|jsonl\.gz|dropped)$""")

        fun dayOf(fileName: String): LocalDate? {
            val match = DAY_FILE.matchEntire(fileName) ?: return null
            return runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
        }
    }
}
