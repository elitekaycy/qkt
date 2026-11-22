package com.qkt.parity

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A venue whose live feeds cannot take more symbols, so a session with a moving set reads it through the production
 * merged feed: every [liveTicks] call opens one more feed over the same [ticks], each serving only its own symbols.
 *
 * The feeds hand out the tape strictly in order, one tick at a time: the next tick is released only once the session
 * has taken the one before ([reading] wraps the session's feed to say so), so a run is repeatable although each feed
 * is read on its own thread. Each of [polls] runs once its instant is reached, before the tick that reached it.
 */
internal class LiveSetMergedVenue(
    private val ticks: List<Tick>,
    polls: Map<Long, () -> Unit>,
) : MarketSource {
    private val lock = ReentrantLock()
    private val moved = lock.newCondition()
    private val due = polls.toSortedMap()
    private val feeds = mutableListOf<Feed>()
    private var cursor = 0
    private var inFlight = false

    /** The symbols of each feed opened so far, in the order they were opened. */
    val opened: List<List<String>> get() = lock.withLock { feeds.map { it.symbols } }

    private inner class Feed(
        val symbols: List<String>,
    ) : TickFeed {
        var closed = false

        override fun next(): Tick? =
            lock.withLock {
                while (!closed && cursor < ticks.size) {
                    val tick = ticks[cursor]
                    val owner = feeds.firstOrNull { !it.closed && tick.symbol in it.symbols }
                    when {
                        inFlight -> moved.await()
                        due.isNotEmpty() && due.firstKey() <= tick.timestamp -> due.remove(due.firstKey())?.invoke()
                        owner == null -> cursor++
                        owner !== this -> moved.signalAll().also { moved.await() }
                        else -> {
                            cursor++
                            inFlight = true
                            return tick
                        }
                    }
                }
                moved.signalAll()
                null
            }

        override fun close() =
            lock.withLock {
                closed = true
                moved.signalAll()
            }
    }

    override val name = "Venue"
    override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String) = true

    override fun liveTicks(symbols: List<String>): TickFeed =
        lock.withLock { Feed(symbols).also { feeds += it }.also { moved.signalAll() } }

    /** [source] as the session reads it: taking a tick releases the venue's next one. */
    fun reading(source: MarketSource): MarketSource =
        object : MarketSource by source {
            override fun liveTicks(symbols: List<String>): TickFeed {
                val feed = source.liveTicks(symbols)
                return object : TickFeed by feed {
                    override fun next(): Tick? {
                        lock.withLock {
                            inFlight = false
                            moved.signalAll()
                        }
                        return feed.next()
                    }
                }
            }
        }
}
