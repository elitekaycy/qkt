package com.qkt.broker.continuous

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.instrument.PriceAdjustment
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.time.Instant

/**
 * One continuous stream's translation to its contracts. The stream's venue runs on a private bus
 * and a private contract price view; everything it publishes belongs to this stream and is
 * republished on [bus] in continuous space: the engine's order id, the continuous symbol, and
 * prices mapped by the contract the order worked on.
 */
internal class StreamLane(
    private val bus: EventBus,
    private val clock: Clock,
    private val chain: ContinuousChain,
    venueFactory: (EventBus, MarketPriceProvider) -> ContractVenue,
) {
    private val venueBus = EventBus(clock, MonotonicSequenceGenerator())
    private val contractPrices = MarketPriceTracker()
    private val venue = venueFactory(venueBus, contractPrices)
    private val orders = ContinuousOrderMap()

    init {
        venueBus.subscribe<BrokerEvent.OrderAccepted> { e ->
            orders.byVenueId(e.clientOrderId)?.let { bus.publish(e.copy(clientOrderId = it.request.id)) }
        }
        venueBus.subscribe<BrokerEvent.OrderRejected> { e ->
            orders.removeByVenueId(e.clientOrderId)?.let { bus.publish(e.copy(clientOrderId = it.request.id)) }
        }
        venueBus.subscribe<BrokerEvent.OrderCancelled> { e ->
            orders.removeByVenueId(e.clientOrderId)?.let { bus.publish(e.copy(clientOrderId = it.request.id)) }
        }
        venueBus.subscribe<BrokerEvent.OrderFilled> { e -> onFilled(e) }
    }

    /** Whether the engine order [engineId] works on this stream. */
    fun owns(engineId: String): Boolean = orders.byEngineId(engineId) != null

    fun submit(request: OrderRequest): SubmitAck {
        val now = clock.now()
        refusal(now)?.let { return reject(request, it) }
        val index = requireNotNull(chain.indexAt(now))
        val space =
            try {
                chain.spaceFor(index)
            } catch (e: IllegalArgumentException) {
                return reject(request, e.message ?: "${chain.symbol} cannot price contract $index")
            }
        val translated =
            toContract(request, chain.contractSymbol(index), space)
                ?: return reject(request, "${chain.symbol} does not accept ${request::class.simpleName} orders")
        orders.add(ContinuousOrder(request, request.id, index))
        return venue.broker.submit(translated)
    }

    fun cancel(engineId: String) {
        val order = orders.byEngineId(engineId) ?: return
        venue.broker.cancel(order.venueId)
    }

    /** Derive the contract tick behind the engine's continuous [tick] and hand it to the venue. */
    fun onTick(tick: Tick) {
        val index = chain.indexAt(tick.timestamp) ?: return
        val space = chain.spaceFor(index)
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

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        val order = orders.removeByVenueId(e.clientOrderId)
        val index = order?.contractIndex ?: contractIndexOf(e.symbol)
        bus.publish(
            e.copy(
                clientOrderId = order?.request?.id ?: e.clientOrderId,
                symbol = chain.symbol,
                price = chain.spaceFor(index).toContinuous(e.price),
            ),
        )
    }

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
        return null
    }

    private fun reject(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(request.id, null, accepted = false, rejectReason = reason)
    }
}

/** [request] as the same order on [contract], its levels mapped through [space]; null for other shapes. */
internal fun toContract(
    request: OrderRequest,
    contract: String,
    space: PriceSpace,
): OrderRequest? =
    when (request) {
        is OrderRequest.Market -> request.copy(symbol = contract)
        is OrderRequest.Limit ->
            request.copy(
                symbol = contract,
                limitPrice = space.limitToContract(request.limitPrice, request.side),
            )
        is OrderRequest.Stop ->
            request.copy(
                symbol = contract,
                stopPrice = space.stopToContract(request.stopPrice, request.side),
            )
        is OrderRequest.StopLimit ->
            request.copy(
                symbol = contract,
                stopPrice = space.stopToContract(request.stopPrice, request.side),
                limitPrice = space.limitToContract(request.limitPrice, request.side),
            )
        else -> null
    }
