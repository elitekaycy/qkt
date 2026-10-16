package com.qkt.marketdata.ingest.store

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.SeriesRecord
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.ManifestLock
import java.nio.file.Path
import java.time.LocalDate

/**
 * Writes imported bars (and series, as daily bars whose four prices are the value) through
 * [LocalBarStore] into `bars/<SRC>/<SYM>/<tf>/<day>.csv` and its manifest, the files `qkt fetch` writes
 * and backtests read for `SRC:SYM` streams. A day with no bars is written header-only, as `qkt fetch`
 * does for a closed day: the backtest uses this store only when every calendar day of its window has a
 * file.
 */
class BarDayTarget(
    private val root: Path,
    private val source: String,
    private val symbol: String,
    private val timeframe: String,
    private val timeframeMs: Long,
    clock: Clock,
) : DayTarget {
    private val store = LocalBarStore(root, clock)

    override val dir: Path =
        root
            .resolve("bars")
            .resolve(source)
            .resolve(symbol)
            .resolve(timeframe)

    override fun stored(day: LocalDate): List<Path> =
        if (store.hasDay(
                source,
                symbol,
                timeframe,
                day,
            )
        ) {
            listOf(store.dayFile(source, symbol, timeframe, day))
        } else {
            emptyList()
        }

    override fun write(
        day: LocalDate,
        records: List<IngestRecord>,
    ) {
        val candles = records.map(::candle)
        store.writeDay(source, symbol, timeframe, day, candles)
    }

    override fun readStored(day: LocalDate): DayPrices =
        DayPrices(store.readDay(source, symbol, timeframe, day).map { PricePoint(it.startTime, it.close) })

    override fun commit(days: Collection<LocalDate>) {
        if (days.isEmpty()) return
        ManifestLock(root).withLock {
            for (day in days.sorted()) store.recordDay(source, symbol, timeframe, day)
        }
    }

    private fun candle(r: IngestRecord): Candle {
        val qktSymbol = "$source:$symbol"
        return when (r) {
            is BarRecord ->
                Candle(
                    qktSymbol,
                    r.open,
                    r.high,
                    r.low,
                    r.close,
                    r.volume,
                    r.timeMs,
                    r.timeMs + timeframeMs,
                )
            is SeriesRecord -> {
                val v = r.value.setScale(Money.SCALE, Money.ROUNDING)
                Candle(qktSymbol, v, v, v, v, Money.ZERO, r.timeMs, r.timeMs + timeframeMs)
            }
            else -> error("a bar store cannot hold $r")
        }
    }
}
