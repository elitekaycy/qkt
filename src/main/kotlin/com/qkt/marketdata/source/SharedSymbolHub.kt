package com.qkt.marketdata.source

import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single upstream live subscription for one symbol of a [SharedLiveMarketSource]. Opens
 * the delegate feed on the first subscriber, runs one daemon publisher thread that copies each
 * tick to every [SharedSubscriberFeed], forwards lifecycle events, and stops when the last
 * subscriber leaves or the upstream ends. [name] is the owning source's name.
 */
internal class SharedSymbolHub(
    private val delegate: MarketSource,
    private val name: String,
    private val symbol: String,
    private val subscriberQueueCapacity: Int,
    private val clock: com.qkt.common.Clock,
    private val onEnded: (SharedSymbolHub) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val subscribers = linkedSetOf<SharedSubscriberFeed>()
    private val stopped = AtomicBoolean(false)
    private var upstream: TickFeed? = null
    private var publisher: Thread? = null
    private val recent = RecentTicks()

    fun isClosed(): Boolean = stopped.get()

    fun subscribe(): SharedSubscriberFeed? =
        synchronized(lock) {
            if (stopped.get()) return@synchronized null
            val subscriber = SharedSubscriberFeed(::unsubscribe, subscriberQueueCapacity)
            subscribers.add(subscriber)
            // A late subscriber starts where the first one did - at the start of the minute - so
            // every session builds its first bar from the same ticks. Handed over under the same
            // lock the publisher takes to record a tick and pick its recipients, so each tick
            // reaches this subscriber exactly once: from the backfill or from the publisher.
            if (upstream != null) recent.backfillFor(clock.now()).forEach(subscriber::offer)
            if (upstream == null) {
                try {
                    startUpstream()
                } catch (t: Throwable) {
                    subscribers.remove(subscriber)
                    stopped.set(true)
                    onEnded(this)
                    throw t
                }
            }
            subscriber
        }

    private fun startUpstream() {
        val feed = delegate.liveTicks(listOf(symbol))
        upstream = feed
        (feed as? MarketDataLifecycleFeed)?.let { lifecycle ->
            lifecycle.onDisconnect { broadcastDisconnect() }
            lifecycle.onReconnect { broadcastReconnect() }
        }
        publisher =
            Thread(
                {
                    var failure: String? = null
                    try {
                        while (!stopped.get()) {
                            val tick = feed.next()
                            if (tick == null) {
                                val lifecycle = feed as? MarketDataLifecycleFeed
                                failure =
                                    if (lifecycle?.expectsContinuousDelivery == true) {
                                        lifecycle.terminalFailureReason()
                                            ?: "continuous market-data feed ended"
                                    } else {
                                        null
                                    }
                                break
                            }
                            recordAndSnapshot(tick).forEach { it.offer(tick) }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (t: Throwable) {
                        if (!stopped.get()) {
                            failure = "shared market-data publisher failed: ${t.message ?: t::class.simpleName}"
                        }
                    } finally {
                        terminate(failure)
                    }
                },
                "qkt-shared-feed-${name.hashCode()}-${symbol.hashCode()}",
            ).apply {
                isDaemon = true
                start()
            }
    }

    private fun snapshotSubscribers(): List<SharedSubscriberFeed> = synchronized(lock) { subscribers.toList() }

    private fun recordAndSnapshot(tick: com.qkt.marketdata.Tick): List<SharedSubscriberFeed> =
        synchronized(lock) {
            recent.add(tick, clock.now())
            subscribers.toList()
        }

    private fun broadcastDisconnect() {
        val scope = MarketDataFeedScope(source = name, symbols = listOf(symbol))
        snapshotSubscribers().forEach { it.disconnected(scope) }
    }

    private fun broadcastReconnect() {
        val scope = MarketDataFeedScope(source = name, symbols = listOf(symbol))
        snapshotSubscribers().forEach { it.reconnected(scope) }
    }

    private fun unsubscribe(subscriber: SharedSubscriberFeed) {
        val stoppedByLastSubscriber =
            synchronized(lock) {
                subscribers.remove(subscriber) &&
                    subscribers.isEmpty() &&
                    stopped.compareAndSet(false, true)
            }
        if (!stoppedByLastSubscriber) return
        runCatching { upstream?.close() }
        publisher?.interrupt()
        onEnded(this)
    }

    private fun terminate(failure: String?) {
        if (!stopped.compareAndSet(false, true)) return
        val remaining = synchronized(lock) { subscribers.toList().also { subscribers.clear() } }
        remaining.forEach { it.ended(failure) }
        runCatching { upstream?.close() }
        onEnded(this)
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        val remaining = synchronized(lock) { subscribers.toList().also { subscribers.clear() } }
        remaining.forEach { it.ended(null) }
        runCatching { upstream?.close() }
        publisher?.interrupt()
        onEnded(this)
    }
}
