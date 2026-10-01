package com.qkt.marketdata.source

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.LiveRoll
import com.qkt.derivatives.futures.RollTransition
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import org.slf4j.LoggerFactory

/**
 * One continuous futures stream served live (design: live continuous futures §2.2): the live ticks of
 * the contract the stream follows, mapped onto the adjusted series by that contract's price space, as
 * [ContinuousMarketSource] maps stored ticks, and re-stamped with the stream's symbol. It reads the
 * followed contract and the next one ([ticks]), so the next is flowing when the stream switches; the
 * next contract's ticks never reach the strategy. When a tick shows a roll the [chain] has no
 * measurement for (a roll now, or one that fell in a downtime), the roll is measured ([measure]),
 * again every [retryMs] while its minute has not closed at the venue, the chain is rebuilt and the new
 * pair read; the ticks seen meanwhile are stale and not served. A roll the history cannot take, or one
 * still unpriced [measureForMs] after it, ends this stream, logged, as an unpriceable roll ends a
 * backtest's. Not thread-safe: one reader.
 */
class ContinuousLiveFeed(
    private val chain: () -> ContinuousChain,
    private val ticks: (contracts: List<String>) -> TickFeed,
    private val measure: (RollTransition) -> LiveRoll,
    private val clock: Clock,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val retryMs: Long = 15_000,
    private val measureForMs: Long = 600_000,
) : TickFeed {
    private val log = LoggerFactory.getLogger(ContinuousLiveFeed::class.java)
    private var current = chain()
    private var feed: TickFeed? = null
    private var following = -1

    override fun next(): Tick? {
        while (true) {
            val source = feed ?: follow(current.indexAt(clock.now()) ?: return end(clock.now()))
            val tick = source.next() ?: return null
            val index = current.indexAt(tick.timestamp) ?: return end(tick.timestamp)
            if (!current.covers(index)) {
                if (!rollInto(tick.timestamp)) return null
                continue
            }
            if (index != following) follow(index)
            if (tick.symbol != current.contractSymbol(index)) continue
            return tick.inSpace(current.spaceFor(index), current.symbol)
        }
    }

    override fun close() {
        feed?.close()
    }

    /** Reads contract [index] and the one after it from now on. */
    private fun follow(index: Int): TickFeed {
        feed?.close()
        val contracts = listOfNotNull(current.contractSymbol(index), current.contractSymbolOrNull(index + 1))
        following = index
        return ticks(contracts).also { feed = it }
    }

    /** Measures the last roll at or before [atMs]; true once the chain covers it and the new pair is read. */
    private fun rollInto(atMs: Long): Boolean {
        val roll = current.schedule.transitions.last { it.atMs <= atMs }
        val deadline = roll.atMs + measureForMs
        while (true) {
            when (val result = measure(roll)) {
                is LiveRoll.Measured -> {
                    current = chain()
                    val index = checkNotNull(current.indexAt(atMs))
                    check(current.covers(index)) {
                        "${current.symbol}: the roll at ${roll.atMs} was measured but the chain does not cover it"
                    }
                    follow(index)
                    return true
                }
                is LiveRoll.NotNext -> return stop("${current.symbol} cannot roll at ${roll.atMs}: ${result.reason}")
                is LiveRoll.Unpriced -> {
                    if (clock.now() + retryMs >
                        deadline
                    ) {
                        return stop("${current.symbol} roll at ${roll.atMs} still unpriced: ${result.reason}")
                    }
                    sleep(retryMs)
                }
            }
        }
    }

    private fun stop(reason: String): Boolean {
        log.error("{}; the stream stops", reason)
        return false
    }

    private fun end(atMs: Long): Tick? {
        log.warn("{} has no contract at {} ({}); the stream ends", current.symbol, atMs, current.endReason)
        return null
    }

    private fun ContinuousChain.contractSymbolOrNull(index: Int): String? =
        if (index < schedule.contracts.size) contractSymbol(index) else null
}
