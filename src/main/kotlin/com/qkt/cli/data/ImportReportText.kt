package com.qkt.cli.data

import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.OpenInterestRecord
import com.qkt.marketdata.ingest.record.SeriesRecord
import com.qkt.marketdata.ingest.record.TickRecord
import com.qkt.marketdata.ingest.run.ImportReport
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The human-readable `qkt data import` report: how the file was read, raw rows beside stored ones, counts, and what was written. */
internal object ImportReportText {
    fun render(r: ImportReport): String =
        buildString {
            appendLine("Read     ${r.inputs.size} input(s), ${r.rows} rows → ${r.records} ${r.kind} records")
            r.inputs.take(INPUTS_SHOWN).forEach {
                appendLine(
                    "           ${it.name}: ${it.rows} rows" + skipped(it.skips.repeatedHeaders, it.skips.textRows),
                )
            }
            if (r.inputs.size > INPUTS_SHOWN) appendLine("           … and ${r.inputs.size - INPUTS_SHOWN} more")
            appendLine("Format   ${r.preset} (${r.presetSource})" + (r.description?.let { ": $it" } ?: ""))
            appendLine("Time     ${r.timeFormats.joinToString()}")
            appendLine("Clock    ${r.clock}")
            r.clockEvidence?.let { appendLine("         evidence: $it") }
            r.timeframe?.let { appendLine("Bars     $it") }
            r.basis?.let { appendLine("Prices   $it") }
            r.volume?.let { appendLine("Volume   $it") }
            appendLine()
            appendLine("  raw line → stored (UTC)")
            r.preview.forEach { (raw, rec) ->
                appendLine("  ${raw.take(RAW_WIDTH).padEnd(RAW_WIDTH)} → ${stored(rec)}")
            }
            appendLine()
            val t = r.tally
            appendLine(
                "Checks   ${t.duplicatesDropped} duplicate(s) dropped · ${t.crossedDropped} crossed quote(s) dropped" +
                    (if (t.superseded > 0) " · ${t.superseded} superseded by a later report" else "") +
                    " · ${t.spikes.size} spike(s) flagged (kept)",
            )
            t.spikes.forEach { appendLine("           spike $it") }
            if (t.zeroOpenInterestDropped > 0) {
                appendLine(
                    "Dropped  ${t.zeroOpenInterestDropped} open-interest figure(s) of exactly 0 (venue outage placeholders)",
                )
            }
            if (t.conflictsKeptLast.isNotEmpty()) {
                appendLine(
                    "Conflict ${t.conflictsKeptLast.size} time(s) held two values; kept the later file's:",
                )
            }
            t.conflictsKeptLast.take(CONFLICTS_SHOWN).forEach { appendLine("           $it") }
            r.warnings.forEach { appendLine("Note     $it") }
            appendLine("Days     ${r.days.size}" + span(r.days))
            val o = r.outcomes
            if (r.dryRun) {
                appendLine("Writes   nothing (dry run) → ${r.target}")
            } else {
                appendLine(
                    "Wrote    ${o.written.size} day(s) · ${o.replaced.size} replaced · " +
                        "${o.unchanged.size} unchanged · ${o.kept.size} kept (stored day as full or fuller) · " +
                        "${o.skipped.size} skipped · ${o.emptyFilled.size} empty day(s) recorded → ${r.target}",
                )
            }
            o.overlap?.let { appendLine("Overlap  $it") }
            o.seams.forEach { appendLine("Seam     $it") }
        }

    private fun skipped(
        headers: Int,
        text: Int,
    ): String =
        listOfNotNull(
            headers.takeIf { it > 0 }?.let { "$it repeated header(s) skipped" },
            text.takeIf { it > 0 }?.let { "$it text row(s) skipped" },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }

    private fun span(days: List<LocalDate>): String = if (days.isEmpty()) "" else "  ${days.first()} … ${days.last()}"

    /** One stored record in qkt's terms. */
    fun stored(r: IngestRecord): String {
        val at = Instant.ofEpochMilli(r.timeMs)
        return when (r) {
            is TickRecord ->
                "$at " +
                    listOfNotNull(
                        r.bid?.let {
                            "bid=${p(it)}"
                        },
                        r.ask?.let {
                            "ask=${p(
                                it,
                            )}"
                        },
                        r.price?.let { "price=${p(it)}" },
                        r.volume?.let { "vol=${p(it)}" },
                    ).joinToString(" ")
            is BarRecord -> "$at o=${p(r.open)} h=${p(r.high)} l=${p(r.low)} c=${p(r.close)} v=${p(r.volume)}"
            is FundingRecord -> "$at rate=${p(r.rate)}" + (r.price?.let { " price=${p(it)}" } ?: "")
            is OpenInterestRecord -> "$at oi=${p(r.openInterest)}"
            is SeriesRecord -> "${LocalDate.ofInstant(
                at,
                ZoneOffset.UTC,
            )} value=${p(r.value)} (observed ${Instant.ofEpochMilli(r.observedMs)})"
        }
    }

    private fun p(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

    private const val INPUTS_SHOWN = 5
    private const val CONFLICTS_SHOWN = 20
    private const val RAW_WIDTH = 56
}
