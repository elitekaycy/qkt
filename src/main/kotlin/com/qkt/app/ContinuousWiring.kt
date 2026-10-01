package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.source.ContinuousMarketSource
import com.qkt.marketdata.source.LiveContinuousStreams
import com.qkt.marketdata.source.MarketSource

/** What a session's brokers need to trade continuous futures streams: their [chains], and a way to bind each lane's bus. */
internal interface ContinuousRouting {
    /** The chains of the session's continuous streams, shared with its market data; null when it declares none. */
    val chains: ContinuousChains?

    /** Binds a lane's private [bus] to the session's engine loop, before the lane's venue is built. */
    fun bindLane(bus: EventBus)
}

/**
 * How a live session serves and trades its continuous futures streams (design: live continuous futures
 * §2.2, §2.3). One set of [chains] is shared by the session's market data ([source]: the streams served
 * from their contracts, a roll measured live extending the chains) and its brokers' lanes, which roll on
 * the same measurement. A stream needs its root's roll history on disk, where the session appends what it
 * measures. Lane buses are queued on the session's mailbox ([bindMailbox], before any broker is built) and
 * routed to its engine loop once it exists ([attach]). A session declaring no stream gets [source] as given.
 */
internal class ContinuousWiring(
    symbols: Collection<String>,
    registry: InstrumentRegistry?,
    source: MarketSource,
    clock: Clock,
) : ContinuousRouting {
    override val chains: ContinuousChains?
    val source: MarketSource
    private var lanes: LaneBuses? = null

    init {
        val directory = registry?.futures()
        val candidates = directory?.let(::ContinuousChains)
        val streams = symbols.filter { candidates?.isContinuous(it) == true }
        if (directory == null || candidates == null || streams.isEmpty()) {
            chains = null
            this.source = source
        } else {
            val store =
                requireNotNull(directory.historyStore()) {
                    "continuous futures streams $streams need their roll histories on disk; build them with qkt fetch <ROOT> --rolls"
                }
            streams.forEach { requireNotNull(candidates.chainFor(it)) }
            chains = candidates
            this.source =
                ContinuousMarketSource(
                    source,
                    candidates,
                    registry,
                    LiveContinuousStreams(candidates, directory, store, source, clock),
                )
        }
    }

    /** Queues lane buses on [mailbox] from now on. */
    fun bindMailbox(mailbox: EngineMailbox) {
        lanes = LaneBuses(mailbox)
    }

    override fun bindLane(bus: EventBus) =
        checkNotNull(lanes) {
            "a lane bus was bound before the session's mailbox existed"
        }.bind(bus)

    /** Routes every lane bus onto the engine loop running on [thread]. */
    fun attach(thread: Thread) {
        lanes?.attach(thread)
    }
}
