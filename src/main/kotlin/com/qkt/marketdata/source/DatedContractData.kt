package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.deliveryPrice
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * What a dated futures contract serves around its expiry: its data up to the expiry, then — when
 * the requested range covers the expiry — one settlement print at the expiry, priced at the
 * catalog's delivery price (the last served price when the catalog has none). The print is the
 * exchange's final settlement: it lets a position held into expiry settle even when nothing else
 * trades afterwards. Symbols that are not dated contracts pass through unchanged.
 */
internal class DatedContractData(
    private val instruments: InstrumentRegistry,
) {
    /** [data] for [symbol] over [range], cut at expiry and closed by a flat zero-volume settlement bar. */
    fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
        data: Sequence<Candle>,
    ): Sequence<Candle> {
        val expiry = expiryOf(symbol) ?: return data
        val kept = data.filter { it.endTime <= expiry }
        val end = minOf(expiry + window.durationMs, range.to.toEpochMilli())
        if (!covers(range, expiry) || end - expiry < BAR_TICKS) return kept
        return withSettlement(symbol, kept, Candle::close) { px ->
            Candle(symbol, px, px, px, px, BigDecimal.ZERO, expiry, end)
        }
    }

    /** [data] for [symbol] over [range], cut at expiry and closed by a settlement tick at the expiry. */
    fun ticks(
        symbol: String,
        range: TimeRange,
        data: Sequence<Tick>,
    ): Sequence<Tick> {
        val expiry = expiryOf(symbol) ?: return data
        val kept = cut(expiry, data)
        if (!covers(range, expiry)) return kept
        return withSettlement(symbol, kept, Tick::price) { px -> Tick(symbol, px, expiry) }
    }

    /** [data] for [symbol] without anything printed at or after its expiry. */
    fun cut(
        symbol: String,
        data: Sequence<Tick>,
    ): Sequence<Tick> = expiryOf(symbol)?.let { cut(it, data) } ?: data

    private fun cut(
        expiry: Long,
        data: Sequence<Tick>,
    ): Sequence<Tick> = data.filter { it.timestamp < expiry }

    private fun <T> withSettlement(
        symbol: String,
        kept: Sequence<T>,
        priceOf: (T) -> BigDecimal,
        print: (BigDecimal) -> T,
    ): Sequence<T> =
        sequence {
            var last: BigDecimal? = null
            for (item in kept) {
                last = priceOf(item)
                yield(item)
            }
            val price = instruments.deliveryPrice(symbol) ?: last ?: return@sequence
            yield(print(price))
        }

    private fun expiryOf(symbol: String): Long? = (instruments.lookup(symbol)?.derivative as? FutureTerms)?.expiryMs

    private fun covers(
        range: TimeRange,
        expiry: Long,
    ): Boolean = range.from.toEpochMilli() <= expiry && expiry < range.to.toEpochMilli()

    private companion object {
        /** A bar replays as four ticks at distinct times, so a shorter settlement bar is not printed. */
        const val BAR_TICKS = 4L
    }
}
