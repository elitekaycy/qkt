package com.qkt.marketdata.source

import com.qkt.marketdata.TickFeed

/**
 * A live feed that can take more symbols while it runs: [add] returns those it now serves (new or already), and
 * [drop] names symbols it need not serve any more (an expired contract), which a feed may stop reading.
 */
interface ExpandableTickFeed : TickFeed {
    fun add(symbols: Collection<String>): Set<String>

    fun drop(symbols: Collection<String>) {}
}

/**
 * A live [MarketSource] whose one session feed can take more symbols while it runs, for a symbol set whose members
 * move (runtime membership plan, step 8): the feed [liveTicks] opens keeps serving its symbols, [subscribe] adds
 * more and [unsubscribe] lets go of those no longer needed. A [delegate] whose feed can add symbols itself
 * ([ExpandableTickFeed]) does; any other is served by a [MergedTickFeed] that keeps one more feed of the delegate for
 * everything added and runs [onClose] when it closes. [onOpen] runs each time a feed is opened, so whatever feeds
 * it (a listing poller) only runs while there is a feed. Every other read goes to [delegate].
 */
class ExpandableLiveSource(
    private val delegate: MarketSource,
    private val onOpen: () -> Unit = {},
    private val onClose: () -> Unit = {},
) : MarketSource by delegate {
    @Volatile private var feed: ExpandableTickFeed? = null

    override fun liveTicks(symbols: List<String>): TickFeed {
        val opened = delegate.liveTicks(symbols)
        val expandable = opened as? ExpandableTickFeed ?: MergedTickFeed(opened, symbols, delegate::liveTicks, onClose)
        feed = expandable
        onOpen()
        return expandable
    }

    /**
     * Subscribes [symbols] on the running feed and returns those it now serves; none before the feed opened, and a
     * symbol whose subscription fails is left out, so its caller never treats it as fed.
     */
    fun subscribe(symbols: Collection<String>): Set<String> = feed?.add(symbols) ?: emptySet()

    /** Tells the running feed [symbols] are no longer needed (they expired or were delisted). */
    fun unsubscribe(symbols: Collection<String>) {
        feed?.drop(symbols)
    }
}
