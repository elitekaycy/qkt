package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis

/**
 * The plain-language notes an import records about meaning it could not make exact: bid bars stored as
 * qkt's mid without a spread to shift them by, sources that never say which price they quote, digits
 * rounded to qkt's precision, timestamps finer than a millisecond.
 */
internal object ImportNotes {
    /** What the stored prices are, given the source's basis. */
    fun basis(
        descriptor: ImportDescriptor,
        kind: IngestKind,
    ): String? {
        if (kind != IngestKind.BARS && kind != IngestKind.TICKS) return null
        val spread = descriptor.fields["spread"]?.let { !it.trimEnd().endsWith('?') } ?: false
        return when (descriptor.basis) {
            PriceBasis.BID, PriceBasis.ASK ->
                if (kind == IngestKind.BARS && descriptor.fields.containsKey("spread")) {
                    "${descriptor.basis.name.lowercase()} → mid by half of each bar's spread" +
                        if (spread) "" else " (where the file has one)"
                } else {
                    "${descriptor.basis.name.lowercase()} prices, stored as they are"
                }
            PriceBasis.MID -> "mid"
            PriceBasis.LAST -> "last traded prices"
            null ->
                if (kind ==
                    IngestKind.TICKS
                ) {
                    "bid/ask quotes or traded prices as the columns say"
                } else {
                    "not stated by the source"
                }
        }
    }

    /** Warnings for the report and provenance. */
    fun warnings(
        plan: ImportPlan,
        read: ReadResult,
        kind: IngestKind,
    ): List<String> =
        buildList {
            val d = plan.descriptor
            if (kind == IngestKind.BARS &&
                (d.basis == PriceBasis.BID || d.basis == PriceBasis.ASK) &&
                !d.fields.containsKey("spread")
            ) {
                add(
                    "the bars are ${d.basis.name.lowercase()} prices and the file has no spread: " +
                        "qkt reads bar prices as mid, " +
                        "so they sit about half a spread ${if (d.basis == PriceBasis.BID) "low" else "high"}",
                )
            }
            if (kind == IngestKind.BARS &&
                d.basis == null
            ) {
                add("the source does not say whether its bars are bid, ask, mid or traded prices")
            }
            val rounded = read.stats.sumOf { it.rounded }
            if (rounded >
                0
            ) {
                add("$rounded value(s) had more than 8 decimals and were rounded to qkt's precision (HALF_EVEN)")
            }
            val sub = read.stats.sumOf { it.subMillis }
            if (sub >
                0
            ) {
                add(
                    "$sub timestamp(s) were finer than a millisecond; qkt stores milliseconds, so the rest was dropped",
                )
            }
            if (plan.skippedFiles.isNotEmpty()) {
                val kinds =
                    plan.skippedFiles
                        .groupingBy {
                            it.fileName.toString().substringAfterLast(
                                '.',
                                "(none)",
                            )
                        }.eachCount()
                add(
                    "${plan.skippedFiles.size} non-data file(s) skipped in the directory: " +
                        kinds.entries.joinToString { "${it.value} .${it.key}" },
                )
            }
            val filtered = read.stats.sumOf { it.filtered }
            if (filtered > 0) add("$filtered row(s) did not pass the row filter ${d.where} and were not imported")
            if (plan.target.digits == null && (kind == IngestKind.BARS || kind == IngestKind.TICKS)) {
                add(
                    "qkt has no spec for ${plan.target.qktSymbol}: add it to instruments.yaml (contract size, digits, fees) before backtesting",
                )
            }
        }
}
