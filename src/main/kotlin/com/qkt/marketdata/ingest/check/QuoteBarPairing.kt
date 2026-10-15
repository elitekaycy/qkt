package com.qkt.marketdata.ingest.check

import com.qkt.common.Money
import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import java.math.BigDecimal
import java.time.LocalDate

/** The quote side a bar file holds, read from its name. */
enum class QuoteSide { BID, ASK }

/**
 * Joins bid and ask bar files into the mid bars qkt stores. Dukascopy, and tools built on it, publish one
 * file per side (`EURUSD_Candlestick_1_M_BID_…csv` and `…_ASK_…csv`); imported together, each pair of bars
 * with the same open time becomes one bar whose open, high, low and close are the means of the two sides
 * and whose volume is their sum (as qkt's ticks sum bid and ask size). A bar on one side without its
 * partner is a problem, never half a quote. [sides] maps input index to side; inputs without a side pass
 * through unchanged.
 */
class QuoteBarPairing(
    private val sides: Map<Int, QuoteSide>,
    private val tally: CheckTally,
) {
    /** True when both sides are present, so bars are joined. */
    val active: Boolean = QuoteSide.BID in sides.values && QuoteSide.ASK in sides.values

    /** [day]'s sorted records with each bid/ask pair joined into one mid bar. */
    fun join(
        day: LocalDate,
        records: List<IngestRecord>,
        indexOf: (Long) -> Int,
    ): List<IngestRecord> {
        if (!active) return records
        val out = ArrayList<IngestRecord>(records.size / 2 + 1)
        for ((time, group) in records.groupBy { it.timeMs }) {
            val bars = group.filterIsInstance<BarRecord>()
            val bid = bars.filter { sides[indexOf(it.order)] == QuoteSide.BID }.distinctBy { it.copy(order = 0) }
            val ask = bars.filter { sides[indexOf(it.order)] == QuoteSide.ASK }.distinctBy { it.copy(order = 0) }
            if (bid.size != 1 || ask.size != 1) {
                tally.problem(day, time, "expected one bid and one ask bar, found ${bid.size} bid and ${ask.size} ask")
                continue
            }
            out += mid(bid.single(), ask.single())
        }
        return out
    }

    private fun mid(
        bid: BarRecord,
        ask: BarRecord,
    ): BarRecord =
        BarRecord(
            timeMs = bid.timeMs,
            order = bid.order,
            open = mean(bid.open, ask.open),
            high = mean(bid.high, ask.high),
            low = mean(bid.low, ask.low),
            close = mean(bid.close, ask.close),
            volume = bid.volume.add(ask.volume),
        )

    private fun mean(
        a: BigDecimal,
        b: BigDecimal,
    ): BigDecimal = a.add(b).divide(TWO).setScale(Money.SCALE, Money.ROUNDING)

    companion object {
        private val TWO = BigDecimal(2)
        private val TOKEN = Regex("""(?i)(^|[_\-. ])(BID|ASK)([_\-. ]|$)""")

        /** The side [fileName] names (`_BID_`, `-ask.`), or null when it names none or both. */
        fun sideOf(fileName: String): QuoteSide? =
            TOKEN
                .findAll(fileName)
                .map { QuoteSide.valueOf(it.groupValues[2].uppercase()) }
                .toSet()
                .singleOrNull()
    }
}
