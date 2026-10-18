package com.qkt.marketdata.ingest.record

import com.qkt.common.Money
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis
import com.qkt.marketdata.ingest.rows.ImportRow
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Turns rows into [IngestRecord]s through a [RowMapping]. Every price and volume is held at qkt's money
 * scale (8 decimals, HALF_EVEN), the scale qkt's readers round to anyway, so a value never changes
 * between the import and the backtest that reads it; [rounded] counts values that had more digits.
 * A row that cannot become a record throws [IllegalArgumentException] naming the reason.
 */
class RecordBuilder(
    private val mapping: RowMapping,
) {
    private var lastBid: BigDecimal? = null
    private var lastAsk: BigDecimal? = null

    /** Values whose digits beyond the eighth decimal were rounded away. */
    var rounded: Long = 0
        private set

    /** Float32 prices stored as their shortest decimal ([RowMapping.float32]). */
    var float32Recovered: Long = 0
        private set

    /** Prices a float32 mapping found that are not exactly a float32, kept as written. */
    var notFloat32: Long = 0
        private set

    /** The record for [row], read as the [order]-th row of the import. */
    fun build(
        row: ImportRow,
        order: Long,
    ): IngestRecord {
        val timeMs = mapping.time.epochMs(row)
        return when (mapping.kind) {
            IngestKind.TICKS -> tick(row, timeMs, order)
            IngestKind.BARS -> bar(row, timeMs - mapping.labelShiftMs, order)
            IngestKind.FUNDING -> FundingRecord(timeMs, order, need("rate", row), scaled(mapping.value("price", row)))
            IngestKind.OPEN_INTEREST ->
                OpenInterestRecord(timeMs + mapping.availableLagMs, order, need("openInterest", row))
            IngestKind.SERIES -> series(row, timeMs, order)
        }
    }

    private fun tick(
        row: ImportRow,
        timeMs: Long,
        order: Long,
    ): TickRecord {
        var bid = scaled(priceValue("bid", row))
        var ask = scaled(priceValue("ask", row))
        if (mapping.carryForward) {
            bid = bid ?: lastBid
            ask = ask ?: lastAsk
            lastBid = bid
            lastAsk = ask
        }
        val quote = bid != null && ask != null
        val price = scaled(priceValue("price", row))?.takeUnless { quote && it.signum() == 0 }
        val volume = scaled(mapping.value("volume", row))?.takeUnless { quote && it.signum() == 0 }
        require(price != null || quote) {
            if (bid != null ||
                ask != null
            ) {
                "only one side of the quote"
            } else {
                "no price, bid or ask"
            }
        }
        return TickRecord(
            timeMs,
            order,
            price,
            volume,
            bid,
            ask,
            scaled(mapping.value("bidVolume", row)),
            scaled(mapping.value("askVolume", row)),
        )
    }

    private fun bar(
        row: ImportRow,
        timeMs: Long,
        order: Long,
    ): BarRecord {
        val spread = mapping.value("spread", row)
        val shift =
            when {
                spread == null -> BigDecimal.ZERO
                mapping.basis == PriceBasis.BID -> half(spread)
                mapping.basis == PriceBasis.ASK -> half(spread).negate()
                else -> BigDecimal.ZERO
            }

        fun price(role: String) =
            (scaled(priceValue(role, row)) ?: throw IllegalArgumentException("no $role"))
                .add(shift)
                .setScale(Money.SCALE, Money.ROUNDING)
        return BarRecord(
            timeMs,
            order,
            price("open"),
            price("high"),
            price("low"),
            price("close"),
            scaled(mapping.value("volume", row)) ?: Money.ZERO,
        )
    }

    private fun series(
        row: ImportRow,
        timeMs: Long,
        order: Long,
    ): SeriesRecord {
        val availableMs = mapping.available?.epochMs(row) ?: (timeMs + mapping.availableLagMs)
        require(availableMs >= timeMs) { "it became available before the period it describes" }
        val day = Math.floorDiv(availableMs + DAY_MS - 1, DAY_MS) * DAY_MS
        return SeriesRecord(day, order, timeMs, need("value", row))
    }

    private fun half(spread: BigDecimal): BigDecimal {
        val point =
            mapping.constants.point
                ?: throw IllegalArgumentException("a spread needs the instrument's digits (--digits)")
        return spread.multiply(point).divide(TWO)
    }

    private fun need(
        role: String,
        row: ImportRow,
    ): BigDecimal = scaled(mapping.value(role, row)) ?: throw IllegalArgumentException("no $role")

    /** A price cell, its float32 widening undone when the mapping says the source stores float32. */
    private fun priceValue(
        role: String,
        row: ImportRow,
    ): BigDecimal? {
        val value = mapping.value(role, row) ?: return null
        if (!mapping.float32) return value
        // The text is a float32 written at some precision: pandas writes the widened double's shortest text
        // (`316.9800109863281`), older yfinance six decimals (`1467.969971`). It is one when the nearest float32,
        // rounded to the text's own decimals, gives the text back; a genuine `1234567.89` does not (`.88`).
        val single = value.toFloat()
        val exact = if (single.isFinite()) BigDecimal(single.toDouble()) else null
        if (exact == null || exact.setScale(value.scale().coerceAtLeast(0), RoundingMode.HALF_EVEN).compareTo(value) != 0) {
            notFloat32++
            return value
        }
        // Float.toString is the shortest decimal that reads back as this float (JDK 19+).
        val shortest = BigDecimal(single.toString())
        if (shortest.compareTo(value) != 0) float32Recovered++
        return shortest
    }

    private fun scaled(value: BigDecimal?): BigDecimal? {
        if (value == null) return null
        val out = value.setScale(Money.SCALE, Money.ROUNDING)
        if (out.compareTo(value) != 0) rounded++
        return out
    }

    private companion object {
        val TWO = BigDecimal(2)
        const val DAY_MS = 86_400_000L
    }
}
