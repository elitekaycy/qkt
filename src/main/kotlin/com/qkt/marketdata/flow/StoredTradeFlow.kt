package com.qkt.marketdata.flow

import com.qkt.common.Side
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Trade flow a backtest replays from a [TapeStore]: a window's sums from its day's stored prints (a window never
 * spans two days: its length divides a day). A day not stored has no flow, which coverage refuses before a run
 * starts. Day files are read once and the newest few kept, as a replay asks in time order; the newest windows'
 * sums are kept too, since a rule reads both sides of the same window.
 */
class StoredTradeFlow(
    private val store: TapeStore,
) : TradeFlow {
    private class Day(
        val times: LongArray,
        val sizes: Array<BigDecimal>,
        val buys: BooleanArray,
    )

    private data class DayKey(
        val symbol: String,
        val kind: FlowKind,
        val day: LocalDate,
    )

    private data class WindowKey(
        val symbol: String,
        val kind: FlowKind,
        val windowMs: Long,
        val startMs: Long,
    )

    private val days = lru<DayKey, Day?>(CACHED_DAYS)
    private val windows = lru<WindowKey, SideVolumes?>(CACHED_WINDOWS)

    override fun window(
        symbol: String,
        kind: FlowKind,
        windowMs: Long,
        startMs: Long,
    ): SideVolumes? =
        synchronized(this) {
            val key = WindowKey(symbol, kind, windowMs, startMs)
            if (windows.containsKey(key)) return windows[key]
            val dayKey = DayKey(symbol, kind, LocalDate.ofInstant(Instant.ofEpochMilli(startMs), ZoneOffset.UTC))
            val day = if (days.containsKey(dayKey)) days[dayKey] else load(dayKey).also { days[dayKey] = it }
            day?.sum(startMs, startMs + windowMs).also { windows[key] = it }
        }

    private fun load(key: DayKey): Day? {
        val prints = store.read(key.symbol, key.kind, key.day) ?: return null
        return Day(
            LongArray(prints.size) { prints[it].timeMs },
            Array(prints.size) { prints[it].size },
            BooleanArray(prints.size) { prints[it].side == Side.BUY },
        )
    }

    private fun Day.sum(
        fromMs: Long,
        toMs: Long,
    ): SideVolumes {
        var buy = BigDecimal.ZERO
        var sell = BigDecimal.ZERO
        var i = firstAtOrAfter(fromMs)
        while (i < times.size && times[i] < toMs) {
            if (buys[i]) buy += sizes[i] else sell += sizes[i]
            i++
        }
        return SideVolumes(buy, sell)
    }

    private fun Day.firstAtOrAfter(ms: Long): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private companion object {
        const val CACHED_DAYS = 4
        const val CACHED_WINDOWS = 64

        fun <K, V> lru(size: Int) =
            object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = this.size > size
            }
    }
}
