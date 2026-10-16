package com.qkt.marketdata.ingest.parse

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The unit of an epoch timestamp column. Vendors use all four, sometimes within one dataset: Binance
 * spot moved from milliseconds to microseconds on 2025-01-01, Polygon flat files are nanoseconds while
 * its REST API is milliseconds, Bybit writes seconds with a fraction. The unit is therefore decided per
 * file, from the magnitude of every sampled value, never once per source.
 */
enum class EpochUnit(
    private val perMilli: BigDecimal,
    private val digits: IntRange,
) {
    SECONDS(BigDecimal("0.001"), 9..10),
    MILLIS(BigDecimal.ONE, 12..13),
    MICROS(BigDecimal(1_000), 15..16),
    NANOS(BigDecimal(1_000_000), 18..19),
    ;

    /** [value] in this unit as epoch milliseconds, sub-millisecond digits floored away. */
    fun toMillis(value: BigDecimal): Long = value.divide(perMilli).setScale(0, RoundingMode.FLOOR).longValueExact()

    /** True when [value], in this unit, keeps digits finer than a millisecond. */
    fun hasSubMillis(value: BigDecimal): Boolean = value.divide(perMilli).stripTrailingZeros().scale() > 0

    companion object {
        /** Epoch milliseconds of 1990-01-01T00:00Z: older than any electronic tick history. */
        const val EARLIEST_MS = 631_152_000_000L

        /**
         * The single unit every one of [samples] fits by its count of integer digits, or null when they
         * disagree or fit none (e.g. `20240101`, a date, has eight). The caller then checks the range.
         */
        fun detect(samples: List<BigDecimal>): EpochUnit? {
            if (samples.isEmpty()) return null
            val units =
                samples
                    .map { sample ->
                        val digits =
                            sample
                                .abs()
                                .setScale(0, RoundingMode.FLOOR)
                                .toPlainString()
                                .length
                        entries.firstOrNull { digits in it.digits }
                    }.toSet()
            return units.singleOrNull()
        }
    }
}
