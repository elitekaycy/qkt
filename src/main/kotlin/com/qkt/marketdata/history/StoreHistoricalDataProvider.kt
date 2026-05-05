package com.qkt.marketdata.history

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.DataStore
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class StoreHistoricalDataProvider(
    private val store: DataStore,
    private val clock: Clock,
) : HistoricalDataProvider {
    override val capabilities: Set<DataCapability> =
        setOf(
            DataCapability.TICKS,
            DataCapability.CANDLES_INTRADAY,
            DataCapability.CANDLES_DAILY,
        )

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val now = Instant.ofEpochMilli(clock.now())
        require(range.to <= now) {
            "look-ahead bias: cannot query ticks beyond current time. now=$now, requested to=${range.to}; symbol=$symbol"
        }
        return sequence {
            val days = daysCovering(range)
            for (day in days) {
                val path = store.dayFile(symbol, day) ?: continue
                CsvTickFeed(path).use { feed ->
                    while (true) {
                        val t = feed.next() ?: break
                        if (t.timestamp < range.from.toEpochMilli()) continue
                        if (t.timestamp >= range.to.toEpochMilli()) return@use
                        yield(t)
                    }
                }
            }
        }
    }

    override fun candles(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        sequence {
            var bucketStart: Long = -1
            var bucketEnd: Long = -1
            var open: BigDecimal = Money.ZERO
            var high: BigDecimal = Money.ZERO
            var low: BigDecimal = Money.ZERO
            var close: BigDecimal = Money.ZERO
            var volume: BigDecimal = Money.ZERO
            var hasData = false

            for (tick in ticks(symbol, range)) {
                val ws = window.windowStartFor(tick.timestamp)
                if (!hasData) {
                    bucketStart = ws
                    bucketEnd = ws + window.durationMs
                    open = tick.price
                    high = tick.price
                    low = tick.price
                    close = tick.price
                    volume = tick.volume ?: Money.ZERO
                    hasData = true
                    continue
                }
                if (tick.timestamp >= bucketEnd) {
                    yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
                    bucketStart = ws
                    bucketEnd = ws + window.durationMs
                    open = tick.price
                    high = tick.price
                    low = tick.price
                    close = tick.price
                    volume = tick.volume ?: Money.ZERO
                } else {
                    if (tick.price > high) high = tick.price
                    if (tick.price < low) low = tick.price
                    close = tick.price
                    if (tick.volume != null) volume = volume.add(tick.volume)
                }
            }
            if (hasData) {
                yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
            }
        }

    private fun daysCovering(range: TimeRange): List<LocalDate> {
        val fromDay = range.from.atZone(ZoneOffset.UTC).toLocalDate()
        val toInclusiveDay = Instant.ofEpochMilli(range.to.toEpochMilli() - 1).atZone(ZoneOffset.UTC).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var d = fromDay
        while (!d.isAfter(toInclusiveDay)) {
            days.add(d)
            d = d.plusDays(1)
        }
        return days
    }
}
