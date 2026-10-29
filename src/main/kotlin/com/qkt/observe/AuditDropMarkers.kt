package com.qkt.observe

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger

/**
 * The per-UTC-day count of audit records an [EngineAuditJournal] lost, kept as the day's `.dropped`
 * marker so `qkt golden capture` refuses a day with holes. [mark] is safe from any thread; [persist]
 * runs on the journal's worker and rewrites only markers whose count changed.
 */
internal class AuditDropMarkers {
    private val byDay = ConcurrentHashMap<LocalDate, AtomicLong>()
    private val persisted = HashMap<LocalDate, Long>()

    /** Add [n] lost records to [day]'s count. */
    fun mark(
        day: LocalDate,
        n: Long,
    ) {
        byDay.computeIfAbsent(day) { AtomicLong(0L) }.addAndGet(n)
    }

    /** Write every changed day's cumulative count through [files]; a failed write is retried next pass. */
    fun persist(
        files: AuditJournalFiles,
        log: Logger,
        owner: String,
    ) {
        if (byDay.isEmpty()) return
        for ((day, countRef) in byDay) {
            val count = countRef.get()
            if (persisted[day] == count) continue
            try {
                files.writeDropMarker(day, count)
                persisted[day] = count
            } catch (error: java.io.IOException) {
                log.error("engine audit journal could not persist drop marker for {}: {}", owner, error.message)
            }
        }
    }
}
