package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * One stream's venue events, back to the engine: everything its venue publishes on [venueBus] belongs to
 * the stream and is republished on [bus] in continuous space (the engine's order id, the stream's symbol,
 * prices mapped by the contract the order worked on), except the roll's own orders ([legs]). Every
 * execution, sliced or whole, roll legs too, is booked in the stream's contract [book] and in each
 * strategy's [positions] on the stream; a slice reaches the engine as the partial fill it is, after the
 * lane is [save]d, so a crash between the two cannot book it twice. A resting order's cancel at a roll,
 * once confirmed, is handed to [pulled].
 */
internal class LaneVenueEvents(
    private val bus: EventBus,
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    venueBus: EventBus,
    private val book: StrategyPositionTracker,
    private val orders: ContinuousOrderMap,
    private val legs: RollLegs,
    private val positions: MutableMap<String, BigDecimal>,
    private val fills: ContractFillLog,
    private val space: (Int) -> PriceSpace,
    private val save: () -> Unit,
    private val pulled: (String) -> Unit,
) {
    private val chain: ContinuousChain get() = chainOf()

    init {
        venueBus.subscribe<BrokerEvent.OrderFilled> { e -> book.applyFill(e, LegIntent.Net) }
        venueBus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> book.applyFill(e.asFill(), LegIntent.Net) }
        venueBus.subscribe<BrokerEvent.OrderAccepted> { e ->
            orders
                .byVenueId(
                    e.clientOrderId,
                )?.takeIf { it.isOriginal }
                ?.let { bus.publish(e.copy(clientOrderId = it.request.id)) }
        }
        venueBus.subscribe<BrokerEvent.OrderRejected> { e -> if (!legs.onRejected(e)) onRejected(e) }
        venueBus.subscribe<BrokerEvent.OrderCancelled> { e ->
            when {
                legs.cancelConfirmed(e.clientOrderId) -> pulled(e.clientOrderId)
                legs.onCancelled(e) -> Unit
                else ->
                    orders
                        .removeByVenueId(
                            e.clientOrderId,
                        )?.let { bus.publish(e.copy(clientOrderId = it.request.id)) }
            }
        }
        venueBus.subscribe<BrokerEvent.OrderPartiallyFilled> { e ->
            if (!legs.onPartiallyFilled(e)) onPartiallyFilled(e)
        }
        venueBus.subscribe<BrokerEvent.OrderFilled> { e -> if (!legs.onFilled(e)) onFilled(e) }
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

    private fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled) {
        val order = orders.fill(e.clientOrderId, e.quantity)
        legs.slicedWhileCancelling(e.clientOrderId, e.quantity)
        val index = order?.contractIndex ?: contractIndexOf(e.symbol)
        positions.merge(e.strategyId, e.asFill().signedQuantity(), BigDecimal::add)
        val engineSlice =
            e.copy(
                clientOrderId = order?.request?.id ?: e.clientOrderId,
                symbol = chain.symbol,
                price = space(index).toContinuous(e.price),
                cumulativeFilled = order?.filled ?: e.cumulativeFilled,
            )
        fills.record(contractFill(e.asFill(), engineSlice.asFill()))
        save()
        bus.publish(engineSlice)
    }

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        val order = orders.removeByVenueId(e.clientOrderId)
        legs.forgetCancel(e.clientOrderId)
        val index = order?.contractIndex ?: contractIndexOf(e.symbol)
        positions.merge(e.strategyId, e.signedQuantity(), BigDecimal::add)
        val engineFill =
            e.copy(
                clientOrderId = order?.request?.id ?: e.clientOrderId,
                symbol = chain.symbol,
                price = space(index).toContinuous(e.price),
            )
        fills.record(contractFill(e, engineFill))
        save()
        bus.publish(engineFill)
    }

    private fun contractIndexOf(contract: String): Int =
        requireNotNull(
            chain.indexOf(contract),
        ) { "${chain.symbol} received a fill on $contract, which is not in its chain" }
}
