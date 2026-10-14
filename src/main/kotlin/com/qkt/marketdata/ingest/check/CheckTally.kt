package com.qkt.marketdata.ingest.check

import java.time.Instant
import java.time.LocalDate

/**
 * What validation found across an import, for the report and provenance. [problems] are refusals (with
 * the day and record time for each); everything else was handled without changing a value: exact
 * duplicates dropped, crossed quotes dropped (qkt's reader drops them too), superseded series values
 * replaced by the later report, and flags that leave the data as it is.
 */
class CheckTally {
    val problems = mutableListOf<String>()
    var duplicatesDropped: Long = 0
    var crossedDropped: Long = 0
    var superseded: Long = 0
    val spikes = mutableListOf<String>()
    val gaps = mutableListOf<String>()

    /** Records a refusal-grade problem found on [day] at [timeMs]. */
    fun problem(
        day: LocalDate,
        timeMs: Long,
        message: String,
    ) {
        problems += "$day ${Instant.ofEpochMilli(timeMs)}: $message"
    }
}
