package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.slf4j.Logger

/** Intra-day gap (ms) above which a fetched day file is flagged as possibly partial. 6h. */
private const val SUSPICIOUS_GAP_MS = 6 * 60 * 60 * 1000L

/**
 * Inspect a freshly-fetched day file and warn loudly if it looks incomplete — empty, corrupt,
 * or holding a large intra-day gap. The store keys coverage on file presence, so without this a
 * bad fetch is concatenated into backtests as if complete. We warn rather than refetch: a
 * genuinely quiet day (weekend/holiday) is legitimately empty, and auto-refetching would loop.
 */
internal fun warnOnLowQuality(
    log: Logger,
    sym: String,
    day: LocalDate,
    path: Path,
) {
    if (!Files.exists(path)) {
        log.warn("data integrity: fetch of {} {} produced no file at {}", sym, day, path)
        return
    }
    val q = DayFileIntegrity.inspect(path)
    when {
        !q.readable ->
            log.warn("data integrity: {} {} is unreadable/corrupt — backtests will fail or skip it", sym, day)
        q.isEmpty ->
            log.warn("data integrity: {} {} has 0 ticks (a no-trading day, or a truncated fetch)", sym, day)
        q.maxGapMs >= SUSPICIOUS_GAP_MS ->
            log.warn(
                "data integrity: {} {} has a {}h intra-day gap across {} ticks — possible partial data",
                sym,
                day,
                q.maxGapMs / 3_600_000,
                q.tickCount,
            )
    }
}
