package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.check.QuoteBarPairing
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis
import java.nio.file.Path

/**
 * The plain-language notes an import records about meaning it could not make exact: bid bars stored as
 * qkt's mid without a spread to shift them by, sources that never say which price they quote, digits
 * rounded to qkt's precision, timestamps finer than a millisecond.
 */
internal object ImportNotes {
    private const val PAIRED = "bid and ask files were paired: each stored bar is the mean of its bid and ask bar"
    private const val NO_BASIS = "the source does not say whether its bars are bid, ask, mid or traded prices"
    private const val ROUNDED = "value(s) had more than 8 decimals and were rounded to qkt's precision (HALF_EVEN)"
    private const val SUB_MS = "timestamp(s) were finer than a millisecond; qkt stores milliseconds"
    private const val QUIET = "Dukascopy hour file(s) hold no ticks (market closed or quiet)"
    private const val TICK_BASIS = "bid/ask quotes or traded prices, as the columns say"
    private const val NO_SPEC = "add it to instruments.yaml (contract size, digits, fees) before backtesting"

    /** What the stored prices are, given the source's basis. */
    fun basis(
        descriptor: ImportDescriptor,
        kind: IngestKind,
    ): String? {
        if (kind != IngestKind.BARS && kind != IngestKind.TICKS) return null
        val side = descriptor.basis?.name?.lowercase()
        return when (descriptor.basis) {
            PriceBasis.BID, PriceBasis.ASK ->
                if (kind == IngestKind.BARS && descriptor.fields.containsKey("spread")) {
                    "$side → mid by half of each bar's spread (where the file has one)"
                } else {
                    "$side prices, stored as they are"
                }
            PriceBasis.MID -> "mid"
            PriceBasis.LAST -> "last traded prices"
            null -> if (kind == IngestKind.TICKS) TICK_BASIS else "not stated"
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
            val bars = kind == IngestKind.BARS
            val paired =
                bars &&
                    read.stats
                        .mapNotNull { QuoteBarPairing.sideOf(it.name) }
                        .toSet()
                        .size == 2
            val oneSided = d.basis == PriceBasis.BID || d.basis == PriceBasis.ASK
            if (paired) add(PAIRED)
            if (bars && !paired && oneSided && !d.fields.containsKey("spread")) add(oneSidedBars(d.basis))
            if (bars && d.basis == null) add(NO_BASIS)
            count(read.stats.sumOf { it.rounded }, ROUNDED)
            count(read.stats.sumOf { it.subMillis }, SUB_MS)
            count(plan.emptyBi5Hours.toLong(), QUIET)
            count(read.stats.sumOf { it.filtered }, "row(s) did not pass the row filter ${d.where}")
            if (plan.skippedFiles.isNotEmpty()) add(skipped(plan))
            if (plan.target.digits == null && (bars || kind == IngestKind.TICKS)) {
                add("qkt has no spec for ${plan.target.qktSymbol}: $NO_SPEC")
            }
        }

    private fun MutableList<String>.count(
        n: Long,
        what: String,
    ) {
        if (n > 0) add("$n $what")
    }

    private fun skipped(plan: ImportPlan): String {
        val kinds =
            plan.skippedFiles
                .map(::extensionOf)
                .groupingBy { it }
                .eachCount()
        return "${plan.skippedFiles.size} non-data file(s) skipped: " +
            kinds.entries.joinToString { "${it.value} .${it.key}" }
    }

    private fun extensionOf(path: Path): String = path.fileName.toString().substringAfterLast('.', "(none)")

    private fun oneSidedBars(basis: PriceBasis?): String {
        val side = basis?.name?.lowercase()
        val where = if (basis == PriceBasis.BID) "low" else "high"
        return "the bars are $side prices and the file has no spread: qkt reads bar prices as mid, " +
            "so they sit about half a spread $where"
    }
}
