package com.qkt.observe

import com.qkt.events.Event
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * Writes a batch of audit events through [files] as one durable append per run of same-day events,
 * in order, instead of one synced write per event. A run whose encoding or write fails is lost as a
 * whole and reported in the returned [LostRecords]; the next run reopens the day file. Used only from
 * the journal's worker thread.
 */
internal class AuditBatchWriter(
    private val files: AuditJournalFiles,
    private val dayOf: (Event) -> LocalDate,
) {
    private val buffer = ByteArrayOutputStream(64 * 1024)

    /** Write [events] in order and return what could not be written. */
    fun write(events: List<Event>): LostRecords {
        var lost = LostRecords.NONE
        var start = 0
        while (start < events.size) {
            val day = dayOf(events[start])
            var end = start + 1
            while (end < events.size && dayOf(events[end]) == day) end++
            lost = lost + writeRun(day, events, start, end)
            start = end
        }
        return lost
    }

    private fun writeRun(
        day: LocalDate,
        events: List<Event>,
        from: Int,
        to: Int,
    ): LostRecords {
        buffer.reset()
        return try {
            for (i in from until to) buffer.write(encodeAuditLine(events[i]).toByteArray(Charsets.UTF_8))
            files.append(day, buffer.toByteArray())
            LostRecords.NONE
        } catch (e: Exception) {
            files.close()
            val marketData = (from until to).count { isAuditMarketData(events[it]) }
            LostRecords(to - from, marketData, mapOf(day to (to - from).toLong()), e.message ?: e.javaClass.name)
        }
    }
}

/** Audit records a batch could not write: [total], of which [marketData] ticks and candles, by UTC day. */
internal data class LostRecords(
    val total: Int,
    val marketData: Int,
    val byDay: Map<LocalDate, Long>,
    val error: String?,
) {
    /** The lost decision events (everything that is not market data). */
    val decisions: Int get() = total - marketData

    /** Both losses together. */
    operator fun plus(other: LostRecords): LostRecords =
        when {
            other.total == 0 -> this
            total == 0 -> other
            else -> {
                val days = HashMap(byDay)
                for ((day, n) in other.byDay) days.merge(day, n, Long::plus)
                LostRecords(total + other.total, marketData + other.marketData, days, other.error)
            }
        }

    companion object {
        /** Nothing lost. */
        val NONE = LostRecords(0, 0, emptyMap(), null)
    }
}
