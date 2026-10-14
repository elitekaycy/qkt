package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.check.CheckTally
import com.qkt.marketdata.ingest.record.IngestRecord
import java.time.LocalDate

/** What happened to the store's days, per outcome. */
data class DayOutcomes(
    val written: List<LocalDate> = emptyList(),
    val unchanged: List<LocalDate> = emptyList(),
    val skipped: List<LocalDate> = emptyList(),
    val replaced: List<LocalDate> = emptyList(),
    /** Days inside the imported span with no rows, written header-only (bars) so the store covers them. */
    val emptyFilled: List<LocalDate> = emptyList(),
)

/**
 * Everything one `qkt data import` decided and did, rendered as text for people or JSON for tools. It
 * shows how the file was read (preset, clock and its evidence, mapping), the first and last rows next
 * to what they became, every count of dropped or flagged data, and the days written.
 */
data class ImportReport(
    val symbol: String,
    val kind: String,
    val preset: String,
    val presetSource: String,
    val description: String?,
    val inputs: List<InputStats>,
    val timeFormats: List<String>,
    val clock: String?,
    val clockEvidence: String?,
    val timeframe: String?,
    val basis: String?,
    val volume: String?,
    val target: String,
    val days: List<LocalDate>,
    val records: Long,
    val tally: CheckTally,
    val outcomes: DayOutcomes,
    val warnings: List<String>,
    val dryRun: Boolean,
    val plan: String,
) {
    /** The first and last rows of the import, raw beside stored. */
    val preview: List<Pair<String, IngestRecord>>
        get() = (inputs.firstOrNull()?.head.orEmpty() + inputs.lastOrNull()?.tail.orEmpty()).distinct()

    /** Rows read across inputs. */
    val rows: Long get() = inputs.sumOf { it.rows }
}
