package com.qkt.dsl.sets

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.marketdata.Candle
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/**
 * Gives a joining set member the closed bars its warmup asks for, so its indicators and lags read one unbroken
 * series: what [hub] already holds for its stream, and in front of that the older bars [read] (key, bar count,
 * latest bar end) fetches. The same code warms a backtest's joiner from its store and a live one from its venue.
 */
internal class MemberWarmup(
    private val hub: CandleHub,
    private val read: (HubKey, Int, Long) -> List<Candle>,
) {
    private var stepped = false

    /** The set's first membership step is done: from now on the hub has been fed for every bar that closes. */
    fun stepped() {
        stepped = true
    }

    /**
     * Registers [instance]'s streams on the hub under [owner] and seeds the history its own stream [key] lacks for a
     * join at the window close [closeMs].
     */
    fun warm(
        instance: CompiledStrategy,
        key: HubKey,
        owner: String,
        closeMs: Long,
    ) {
        val stream = WarmupStream(key.qktSymbol, TimeWindow.parse(key.timeframe))
        val need = (instance.perStreamWarmup[stream] as? WarmupSpec.Bars)?.count ?: 0
        val forming = stepped && hub.retention(key) > 0
        for (k in instance.declaredStreams.values.toSet() + instance.retentionByKey.keys) {
            val retention = instance.retentionByKey[k] ?: 1
            hub.register(k, if (k == key) maxOf(retention, need) else retention, owner)
        }
        val held = hub.seededHistory(key)
        if (need > held.size) {
            val bars = read(key, need - held.size, endMs(held, key, closeMs, forming))
            if (bars.isNotEmpty()) hub.seed(key, bars)
        }
    }

    /**
     * The latest bar end a joiner's warmup may read, so its series has no hole and no bar twice.
     *
     * - The hub holds bars for the stream: the start of the oldest, so fetched history only goes in front of them.
     * - It holds none but is [forming] the bar that ends at the join close (the stream was fed for that whole bar):
     *   the start of that bar, which reaches the hub as it closes.
     * - Otherwise (the first evaluation of a session, or a member subscribed while the session runs, whose ticks
     *   before the join were never aggregated): the join close itself, so the bar just closed is read too.
     *
     * e.g. 1h bars, join at 12:00 after a restart at 12:30: bars ending by 12:00 are read, not only by 11:00.
     */
    private fun endMs(
        held: List<Candle>,
        key: HubKey,
        closeMs: Long,
        forming: Boolean,
    ): Long =
        held.firstOrNull()?.startTime
            ?: when {
                closeMs == Long.MIN_VALUE -> Long.MAX_VALUE
                forming -> closeMs - TimeWindow.parse(key.timeframe).durationMs
                else -> closeMs
            }
}
