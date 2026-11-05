package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.derivatives.futures.LiveRoll
import com.qkt.derivatives.futures.LiveRollMeasurer
import com.qkt.derivatives.futures.PrematureRolls
import com.qkt.derivatives.futures.RollQuoteTaps
import com.qkt.derivatives.futures.RollTransition
import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.FuturesDirectory
import com.qkt.instrument.RollHistoryStore
import com.qkt.marketdata.TickFeed
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The continuous futures streams of one live session over one account's market data ([account]: its
 * contracts' live ticks and closed 1-minute bars). Each stream is served by a [ContinuousLiveFeed]
 * reading [chains]; when it reaches a roll the history does not hold, the roll is measured from the
 * account's bars ([LiveRollMeasurer]), appended to the root's history in [store], and [chains] are
 * extended with it, so the stream's lane rolls on the same measurement. A root's measurements are
 * taken one at a time: `@front` and `@next` roll at the same instant, and each must append to the
 * history the other wrote. The records of [roots] dated ahead of the session's start are not taken ([PrematureRolls]):
 * the streams are served without them, so each such roll is priced live at its own minute. Each feed reads
 * the old contract its lane's roll holds on [taps] and hands that contract's quotes back through them.
 */
class LiveContinuousStreams(
    private val chains: ContinuousChains,
    private val directory: FuturesDirectory,
    private val store: RollHistoryStore,
    private val account: MarketSource,
    private val clock: Clock,
    roots: Collection<String> = emptyList(),
    private val taps: RollQuoteTaps? = null,
) {
    private val rootLocks = ConcurrentHashMap<String, Any>()
    private val premature = PrematureRolls.at(store, roots, clock.now())

    init {
        for (root in roots) {
            val history = store.read(root) ?: continue
            val served = premature.trimmed(history)
            if (served != history) chains.useHistory(root, served)
        }
    }

    /** The live feed of continuous stream [symbol] (`VENUE:ROOT@front`). */
    fun feed(symbol: String): TickFeed {
        requireNotNull(chains.chainFor(symbol)) { "$symbol is not a continuous futures stream" }
        return ContinuousLiveFeed(
            chain = { requireNotNull(chains.chainFor(symbol)) },
            ticks = account::liveTicks,
            measure = { roll -> measure(symbol, roll) },
            clock = clock,
            taps = taps,
        )
    }

    /** Measures [roll] for [symbol]'s stream and extends the chains with it. */
    fun measure(
        symbol: String,
        roll: RollTransition,
    ): LiveRoll {
        val rootId = requireNotNull(directory.rootOfContinuous(symbol)) { "$symbol is not a continuous futures stream" }
        val root = requireNotNull(directory.root(rootId)) { "futures root $rootId is not declared" }
        val catalog = requireNotNull(directory.catalog(rootId)) { "no contract catalog for $rootId" }
        val selector =
            requireNotNull(ContinuousSelector.parse(symbol.substringAfter('@'))) { "unknown selector in $symbol" }
        val minuteBars = { code: String, fromMs: Long, toMs: Long ->
            account
                .bars(
                    "${root.venue}:$code",
                    TimeWindow.ONE_MINUTE,
                    TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)),
                ).toList()
        }
        return synchronized(rootLocks.computeIfAbsent(rootId) { Any() }) {
            LiveRollMeasurer(minuteBars, store) { premature.isPremature(rootId, it) }
                .measure(root, catalog, selector, roll)
                .also {
                    if (it is LiveRoll.Measured) {
                        premature.settle(rootId, it.record)
                        chains.useHistory(rootId, premature.trimmed(requireNotNull(store.read(rootId))))
                    }
                }
        }
    }
}
