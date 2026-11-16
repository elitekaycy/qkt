package com.qkt.backtest

import com.qkt.common.FixedClock
import com.qkt.instrument.EventCatalogStore
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.marketdata.ingest.record.TickRecord
import com.qkt.marketdata.ingest.store.TickDayTarget
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * The stored data of the hourly family `KALSHI:KXS` ([SlotFamilyFixture]) for 2024-03-04: its `events:` declaration,
 * its catalog with the 200 strikes settling 1, 0 and 1, and one quote a minute for each of its twelve contracts.
 * Shared by [EventSlotBacktestTest] and the golden event case.
 */
internal object EventSlotData {
    val OUTCOMES = mapOf("KXS-H1-T2" to "1", "KXS-H2-T2" to "0", "KXS-H3-T2" to "1")
    private val day = LocalDate.parse("2024-03-04")

    /** Writes the family's declaration, catalog and quotes under [data]. */
    fun seed(data: Path) {
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("instruments.yaml"),
            "events:\n  - { root: KALSHI:KXS, currency: USD, tickSize: 0.01, volumeStep: 1, volumeMin: 1 }\n",
        )
        EventCatalogStore(data).write(SlotFamilyFixture.catalog(OUTCOMES))
        val mids =
            mapOf(
                1 to listOf("0.90", "0.70", "0.40", "0.10"),
                2 to listOf("0.95", "0.80", "0.68", "0.20"),
                3 to listOf("0.85", "0.72", "0.50", "0.30"),
            )
        for ((event, ladder) in mids) {
            for ((i, mid) in ladder.withIndex()) {
                val jump = if (event == 2 && i == 2) "11:30" to "0.88" else null
                // The tail contract also quotes at H3's expiry, so the run reaches 13:00 and settles H3.
                val last = SlotFamilyFixture.closeMs(event)
                val end = if (event == 3 && i == 3) last else last - 60_000L
                quotes(data, "KXS_H${event}_T${i + 1}", mid, jump, end)
            }
        }
    }

    /** One quote a minute from 10:00 through [endMs] at [mid] a cent either side, moving to the [jump] mid then. */
    private fun quotes(
        data: Path,
        bare: String,
        mid: String,
        jump: Pair<String, String>?,
        endMs: Long,
    ) {
        val cent = BigDecimal("0.01")
        val jumpMs = jump?.let { SlotFamilyFixture.ms("${day}T${it.first}:00Z") } ?: Long.MAX_VALUE
        val records = mutableListOf<TickRecord>()
        var at = SlotFamilyFixture.ms("${day}T10:00:00Z")
        while (at <= endMs) {
            val m = BigDecimal(if (at >= jumpMs) requireNotNull(jump).second else mid)
            records += TickRecord(at, records.size.toLong(), null, null, m - cent, m + cent, null, null)
            at += 60_000L
        }
        val target = TickDayTarget(data, bare, FixedClock(0L))
        target.write(day, records)
        target.commit(listOf(day))
    }
}
