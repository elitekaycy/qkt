package com.qkt.marketdata.ingest.store

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.TickRecord
import java.math.BigDecimal

/** A price at a time: a tick's mid (or trade price), or a bar's close at its open time. */
data class PricePoint(
    val timeMs: Long,
    val price: BigDecimal,
)

/**
 * One day's prices, as stored or as about to be written, reduced to what overlap decisions need: how much
 * of the day it covers (distinct UTC hours with data) and a one-minute series to compare two sources by.
 */
class DayPrices(
    val points: List<PricePoint>,
) {
    /** Distinct UTC hours holding at least one price: a day missing its morning covers fewer. */
    val coveredHours: Int = points.map { Math.floorDiv(it.timeMs, HOUR_MS) }.distinct().size

    /** The first and last price of the day, for the seam between two sources. */
    val first: PricePoint? get() = points.minByOrNull { it.timeMs }

    val last: PricePoint? get() = points.maxByOrNull { it.timeMs }

    /** The last price in each minute (or each bar, for bars of a minute or longer), as candles for a comparison. */
    fun minuteCandles(stepMs: Long): List<Candle> =
        points
            .groupBy { Math.floorDiv(it.timeMs, stepMs) * stepMs }
            .toSortedMap()
            .map { (start, inBucket) ->
                val p = inBucket.maxBy { it.timeMs }.price
                Candle("OVERLAP:X", p, p, p, p, Money.ZERO, start, start + stepMs)
            }

    companion object {
        const val HOUR_MS = 3_600_000L

        /** The prices of [records] about to be written. */
        fun of(records: List<IngestRecord>): DayPrices =
            DayPrices(
                records.mapNotNull { r ->
                    when (r) {
                        is BarRecord -> PricePoint(r.timeMs, r.close)
                        is TickRecord -> priceOf(r)?.let { PricePoint(r.timeMs, it) }
                        else -> null
                    }
                },
            )

        private fun priceOf(t: TickRecord): BigDecimal? {
            t.price?.let { return it }
            val bid = t.bid ?: return null
            val ask = t.ask ?: return null
            return bid.add(ask).divide(TWO).setScale(Money.SCALE, Money.ROUNDING)
        }

        private val TWO = BigDecimal(2)
    }
}
