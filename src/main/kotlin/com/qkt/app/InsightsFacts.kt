package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsFillFacts
import com.qkt.observe.insights.InsightsSink
import com.qkt.observe.insights.InsightsVenues
import com.qkt.observe.insights.PositionFacts
import com.qkt.positions.PositionProvider
import com.qkt.strategy.Strategy

/**
 * One session's insights facts: what its venue brokers state about themselves and their instruments
 * ([venues]) and what qkt resolved for each fill ([fills]), shared by the bus wiring, the closed-trade
 * envelopes and the state poller. Unbound (no brokers, no positions) until [wireBus].
 */
internal class InsightsFacts(
    private val insightsSink: InsightsSink?,
    private val insightsEvents: Set<InsightsEventFamily>,
    private val strategies: List<Pair<String, Strategy>>,
) {
    /** The venue and instrument facts envelopes state. */
    @Volatile var venues: InsightsVenues = InsightsVenues()
        private set

    /** What qkt resolved for each fill. */
    @Volatile var fills: InsightsFillFacts = InsightsFillFacts()
        private set

    /**
     * Streams the session's [bus] to the sink ([InsightsBusWiring]) with facts drawn from its venue
     * [brokers], its account [positions] and its [instruments]; what it resolves per venue position goes
     * to [shared], the process-wide store every session's poller reads. A no-op without a sink.
     */
    fun wireBus(
        bus: EventBus,
        prices: MarketPriceProvider,
        positions: PositionProvider,
        instruments: InstrumentRegistry?,
        shared: PositionFacts,
        brokers: () -> List<Broker>,
    ) {
        val sink = insightsSink ?: return
        val described = InsightsVenues(brokers, instruments)
        val mode = { s: String ->
            (described.describedFor(s) as? Broker)?.positionAccountingMode(s) ?: PositionAccountingMode.UNKNOWN
        }
        venues = described
        fills = InsightsFillFacts(described, positions, mode, shared = shared)
        InsightsBusWiring(insightsEvents, strategies).wire(bus, sink, prices, fills, described)
    }
}
