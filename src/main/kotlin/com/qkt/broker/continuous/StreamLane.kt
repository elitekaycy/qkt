package com.qkt.broker.continuous

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.instrument.PriceAdjustment
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.time.Instant

/**
 * One continuous stream's translation to its contracts. The stream's venue runs on a private bus
 * and a private contract price view; everything it publishes belongs to this stream and is
 * republished on [bus] in continuous space: the engine's order id, the continuous symbol, and
 * prices mapped by the contract the order worked on. The lane keeps each strategy's position on the
 * stream from those fills and rolls it ([RollExecutor]) when the schedule moves to the next contract;
 * a strategy whose position could not be carried places no further orders on the stream.
 */
internal class StreamLane(
    private val bus: EventBus,
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    ledger: RollLedger,
    private val fills: ContractFillLog,
    venueFactory: (EventBus, MarketPriceProvider, PositionProvider) -> ContractVenue,
) {
    /** The chain as it stands now: a live session extends it with each roll it measures. */
    private val chain: ContinuousChain get() = chainOf()

    private val venueBus = EventBus(clock, MonotonicSequenceGenerator())
    private val contractPrices = MarketPriceTracker()

    /** The stream's contract positions as its venue account holds them: every venue fill, roll legs too, netted. */
    private val book = StrategyPositionTracker(clock = clock::now)
    private val venue = venueFactory(venueBus, contractPrices, book.account)
    private val orders = ContinuousOrderMap()
    private val legs = RollLegs()
    private val rolls = RollExecutor(bus, clock, chainOf, venue, contractPrices, orders, legs, ledger, fills)
    private val positions = LinkedHashMap<String, BigDecimal>()
    private val stops = HashMap<String, String>()
    private var current: Int? = null
    private val spaces = HashMap<Int, PriceSpace>()

    init {
        venueBus.subscribe<BrokerEvent.OrderFilled> { e -> book.applyFill(e, LegIntent.Net) }
        venueBus.subscribe<BrokerEvent.OrderAccepted> { e ->
            orders.byVenueId(e.clientOrderId)?.takeIf { it.isOriginal }?.let {
                bus.publish(e.copy(clientOrderId = it.request.id))
            }
        }
        venueBus.subscribe<BrokerEvent.OrderRejected> { e -> if (!legs.onRejected(e)) onRejected(e) }
        venueBus.subscribe<BrokerEvent.OrderCancelled> { e ->
            if (!legs.onCancelled(e)) {
                orders.removeByVenueId(e.clientOrderId)?.let { bus.publish(e.copy(clientOrderId = it.request.id)) }
            }
        }
        venueBus.subscribe<BrokerEvent.OrderFilled> { e -> if (!legs.onFilled(e)) onFilled(e) }
    }

    /** Whether the engine order [engineId] works on this stream. */
    fun owns(engineId: String): Boolean = orders.byEngineId(engineId) != null

    fun submit(request: OrderRequest): SubmitAck {
        val now = clock.now()
        refusal(now)?.let { return reject(request, it) }
        val index = requireNotNull(catchUp(now))
        stops[request.strategyId]?.let { return reject(request, it) }
        val space =
            try {
                space(index)
            } catch (e: IllegalArgumentException) {
                return reject(request, e.message ?: "${chain.symbol} cannot price contract $index")
            }
        val translated =
            toContract(request, request.id, chain.contractSymbol(index), space)
                ?: return reject(request, "${chain.symbol} does not accept ${request::class.simpleName} orders")
        orders.add(ContinuousOrder(request, request.id, index))
        return venue.broker.submit(translated)
    }

    fun cancel(engineId: String) {
        val order = orders.byEngineId(engineId) ?: return
        venue.broker.cancel(order.venueId)
    }

    /** Roll if the schedule has moved on, then hand the contract tick behind [tick] to the venue. */
    fun onTick(tick: Tick) {
        val index = catchUp(tick.timestamp) ?: return
        val space = space(index)
        val contractTick =
            tick.copy(
                symbol = chain.contractSymbol(index),
                price = space.priceToContract(tick.price),
                bid = tick.bid?.let(space::priceToContract),
                ask = tick.ask?.let(space::priceToContract),
            )
        contractPrices.update(contractTick)
        venue.onTick(contractTick)
    }

    /** The contract followed at [nowMs], after rolling onto it if the lane was still on an earlier one. */
    private fun catchUp(nowMs: Long): Int? {
        val index = chain.indexAt(nowMs) ?: return null
        val previous = current
        current = index
        if (previous != null && previous != index) {
            rolls.roll(previous, index, positions) { outcome ->
                stops.putAll(outcome.stopped)
                for (close in outcome.closes) {
                    positions.merge(close.strategyId, signed(close), BigDecimal::add)
                    bus.publish(close)
                }
                outcome.costs.forEach(bus::publish)
            }
        }
        return index
    }

    private fun signed(fill: BrokerEvent.OrderFilled): BigDecimal =
        if (fill.side ==
            Side.BUY
        ) {
            fill.quantity
        } else {
            fill.quantity.negate()
        }

    private fun onRejected(e: BrokerEvent.OrderRejected) {
        val order = orders.removeByVenueId(e.clientOrderId) ?: return
        if (order.isOriginal) {
            bus.publish(e.copy(clientOrderId = order.request.id))
            return
        }
        val reason = "re-placing on ${chain.contractSymbol(order.contractIndex)} at the roll was rejected: ${e.reason}"
        bus.publish(
            BrokerEvent.OrderCancelled(
                order.request.id,
                e.brokerOrderId,
                reason,
                order.request.strategyId,
                clock.now(),
            ),
        )
    }

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        val order = orders.removeByVenueId(e.clientOrderId)
        val index = order?.contractIndex ?: contractIndexOf(e.symbol)
        positions.merge(e.strategyId, signed(e), BigDecimal::add)
        val engineFill =
            e.copy(
                clientOrderId = order?.request?.id ?: e.clientOrderId,
                symbol = chain.symbol,
                price = space(index).toContinuous(e.price),
            )
        fills.record(contractFill(e, engineFill))
        bus.publish(engineFill)
    }

    /** Contract [index]'s price mapping, built once per contract. */
    private fun space(index: Int): PriceSpace = spaces.getOrPut(index) { chain.spaceFor(index) }

    private fun contractIndexOf(contract: String): Int =
        requireNotNull(
            chain.schedule.contracts.indices
                .firstOrNull { chain.contractSymbol(it) == contract },
        ) {
            "${chain.symbol} received a fill on $contract, which is not in its chain"
        }

    private fun refusal(now: Long): String? {
        val stream = chain.symbol
        if (chain.adjust != PriceAdjustment.PANAMA) {
            return "continuous futures orders need adjust: panama; $stream uses ${chain.adjust.name.lowercase()}"
        }
        if (now < chain.servedFromMs) return "$stream is served from ${Instant.ofEpochMilli(chain.servedFromMs)}"
        if (chain.indexAt(now) == null) return "$stream has no contract at ${Instant.ofEpochMilli(now)}"
        if (rolls.inFlight) return "$stream is rolling to its next contract; resend once the roll is done"
        return null
    }

    private fun reject(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        bus.publish(BrokerEvent.OrderRejected(request.id, null, reason, request.strategyId, clock.now()))
        return SubmitAck(request.id, null, accepted = false, rejectReason = reason)
    }
}
