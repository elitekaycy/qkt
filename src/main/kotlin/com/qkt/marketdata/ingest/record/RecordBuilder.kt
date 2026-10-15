package com.qkt.marketdata.ingest.record

import com.qkt.common.Money
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis
import com.qkt.marketdata.ingest.rows.ImportRow
import java.math.BigDecimal

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
        var bid = scaled(mapping.value("bid", row))
        var ask = scaled(mapping.value("ask", row))
        if (mapping.carryForward) {
            bid = bid ?: lastBid
            ask = ask ?: lastAsk
            lastBid = bid
            lastAsk = ask
        }
        val quote = bid != null && ask != null
        val price = scaled(mapping.value("price", row))?.takeUnless { quote && it.signum() == 0 }
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

        fun price(role: String) = need(role, row).add(shift).setScale(Money.SCALE, Money.ROUNDING)
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
