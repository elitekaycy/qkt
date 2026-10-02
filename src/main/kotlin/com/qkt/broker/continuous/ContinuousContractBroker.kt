package com.qkt.broker.continuous

import com.qkt.broker.BookedLeg
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.events.TickEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionProvider

/**
 * The single translation boundary between continuous futures streams (`VENUE:ROOT@front`) and the
 * contracts they follow. Each stream in [symbols] gets its own [ContractVenue] from [venueFactory],
 * on a private bus and a private contract price view; orders are translated onto the contract the
 * roll schedule names at submit time, with limit and stop levels snapped so they never fill early,
 * and the venue's events are republished on [bus] in continuous space. The engine never sees a
 * contract symbol for a continuous stream. Each venue is handed the stream's contract positions, as its
 * account holds them, to judge what an order reduces. At each roll every position and resting order is carried
 * to the next contract and the roll is recorded in [ledger] with its cost booked ([RollExecutor]);
 * every engine fill is recorded in [fills] with the contract and price it executed at. A live session's
 * lanes keep their state in [store] across restarts; a backtest passes none.
 *
 * Orders are accepted only on streams adjusted by panama: there continuous-space P&L plus the
 * booked roll costs equals the P&L of the contract legs exactly.
 *
 * ```kotlin
 * val broker = ContinuousContractBroker(bus, clock, chains, setOf("BINANCE_UM:BTCUSDT@front"), ledger, fills) { venueBus, prices, _ ->
 *     ExchangeSimulator(venueBus, clock, prices, instruments).let { ContractVenue(it, it::onTick) }
 * }
 * ```
 */
class ContinuousContractBroker(
    bus: EventBus,
    clock: Clock,
    chains: ContinuousChains,
    private val symbols: Set<String>,
    ledger: RollLedger,
    fills: ContractFillLog,
    store: LaneStateStore? = null,
    venueFactory: (EventBus, MarketPriceTracker, PositionProvider) -> ContractVenue,
) : Broker {
    private val lanes: Map<String, StreamLane> =
        symbols.associateWith { symbol ->
            requireNotNull(chains.chainFor(symbol)) { "$symbol is not a continuous futures stream" }
            StreamLane(bus, clock, { requireNotNull(chains.chainFor(symbol)) }, ledger, fills, venueFactory, store)
        }

    override val name: String = "ContinuousFutures"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
        )

    init {
        bus.subscribe<TickEvent> { e -> lanes[e.tick.symbol]?.onTick(e.tick) }
    }

    override fun supports(symbol: String): Boolean = symbol in symbols

    override fun positionAccountingMode(symbol: String): PositionAccountingMode = PositionAccountingMode.NETTING

    /** A stream is one netting account its strategies share: each strategy's persisted book stands at a restart. */
    override fun isAccountWide(symbol: String): Boolean = symbol in symbols

    /** Each stream's lane has its venue take back what it had out, beside the engine's restored orders on it. */
    override fun recoverPendingOrders(
        orders: List<ManagedOrder>,
        bookedTickets: Set<String>,
    ): Set<String> {
        val byStream = orders.groupBy { it.request.symbol }
        return lanes.flatMapTo(LinkedHashSet()) { (symbol, lane) -> lane.recover(byStream[symbol].orEmpty()) }
    }

    /** The session is restored: every lane's venue is told so, and each lane goes on. */
    override fun watchBookedLegs(supplier: () -> List<BookedLeg>) = lanes.values.forEach { it.ready() }

    override fun submit(request: OrderRequest): SubmitAck {
        val lane = requireNotNull(lanes[request.symbol]) { "$name does not route ${request.symbol}" }
        return lane.submit(request)
    }

    override fun cancel(orderId: String) {
        lanes.values.firstOrNull { it.owns(orderId) }?.cancel(orderId)
    }

    override fun shutdown() = lanes.values.forEach { runCatching { it.shutdown() } }
}
