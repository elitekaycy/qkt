package com.qkt.marketdata.store

import com.qkt.evidence.EvidenceHasher
import com.qkt.marketdata.openDayFeed
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Reads one stored tick day file fully and records it as a [DatasetSnapshotFile]: its path
 * relative to [root], encoding, size, sha256, tick count, timestamp bounds, largest gap and
 * bid/ask and volume coverage. A parse failure marks the file unreadable instead of throwing.
 */
internal fun inspectDatasetDayFile(
    root: Path,
    day: LocalDate,
    path: Path,
): DatasetSnapshotFile {
    var count = 0
    var bidAskTicks = 0
    var volumeTicks = 0
    var maxGap = 0L
    var minTs: Long? = null
    var maxTs: Long? = null
    var last = Long.MIN_VALUE
    var readable = true
    try {
        openDayFeed(path).use { feed ->
            while (true) {
                val tick = feed.next() ?: break
                if (last != Long.MIN_VALUE) {
                    val gap = tick.timestamp - last
                    if (gap > maxGap) maxGap = gap
                }
                last = tick.timestamp
                minTs = minTs?.coerceAtMost(tick.timestamp) ?: tick.timestamp
                maxTs = maxTs?.coerceAtLeast(tick.timestamp) ?: tick.timestamp
                if (tick.bid != null && tick.ask != null) bidAskTicks++
                if (tick.volume != null) volumeTicks++
                count++
            }
        }
    } catch (e: Exception) {
        readable = false
    }
    return DatasetSnapshotFile(
        date = day.toString(),
        path = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'),
        format = formatOf(path),
        lineage = "local tick day file (${formatOf(path)})",
        sizeBytes = Files.size(path),
        sha256 = EvidenceHasher.sha256(path),
        tickCount = count,
        minTimestamp = minTs,
        maxTimestamp = maxTs,
        maxGapMs = maxGap,
        readable = readable,
        bidAskTicks = bidAskTicks,
        volumeTicks = volumeTicks,
    )
}

private fun formatOf(path: Path): String {
    val name = path.fileName.toString()
    return when {
        name.endsWith(".bin") -> "binary-ticks-v1"
        name.endsWith(".csv.gz") -> "csv-gzip"
        name.endsWith(".csv") -> "csv"
        else -> "unknown"
    }
}
