package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import com.qkt.execution.isTerminal
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.slf4j.LoggerFactory

class OrderManager(
    private val broker: Broker,
    private val bus: EventBus,
    private val priceProvider: MarketPriceProvider,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OrderManager::class.java)

    private val orders: MutableMap<String, ManagedOrder> = mutableMapOf()

    private val trailingHwm: MutableMap<String, BigDecimal> = mutableMapOf()

    private val lastObservedPrice: MutableMap<String, BigDecimal> = mutableMapOf()

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> onAccepted(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> onRejected(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onFilled(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> onCancelled(e) }
        bus.subscribe<TickEvent> { e -> evaluateTriggers(e.tick) }
    }

    fun submit(request: OrderRequest): SubmitAck {
        val now = clock.now()
        track(
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.CREATED,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        return dispatch(request)
    }

    fun cancel(clientOrderId: String) {
        val managed = orders[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        if (managed.childClientOrderIds.isNotEmpty()) {
            for (childId in managed.childClientOrderIds) cancel(childId)
            update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            return
        }
        when (managed.state) {
            OrderState.CREATED, OrderState.PENDING ->
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            else -> broker.cancel(clientOrderId)
        }
    }

    fun getOrder(clientOrderId: String): ManagedOrder? = orders[clientOrderId]

    fun activeOrders(): List<ManagedOrder> = orders.values.filter { !it.state.isTerminal }

    fun pendingOrders(): List<ManagedOrder> = orders.values.filter { it.state == OrderState.PENDING }

    private fun dispatch(request: OrderRequest): SubmitAck =
        when (request) {
            is OrderRequest.Market, is OrderRequest.Limit -> submitToBroker(request)

            is OrderRequest.Stop ->
                if (OrderTypeCapability.STOP in broker.capabilities) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.StopLimit ->
                if (OrderTypeCapability.STOP_LIMIT in broker.capabilities) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.IfTouched ->
                if (OrderTypeCapability.IF_TOUCHED in broker.capabilities) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> holdPending(request)

            else -> error("Order type ${request::class.simpleName} dispatch not yet implemented (added later in 7d-b)")
        }

    private fun submitToBroker(request: OrderRequest): SubmitAck {
        update(request.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        return broker.submit(request)
    }

    private fun holdPending(request: OrderRequest): SubmitAck {
        update(request.id) { it.copy(state = OrderState.PENDING, lastUpdatedAt = clock.now()) }
        if (request is OrderRequest.TrailingStop || request is OrderRequest.TrailingStopLimit) {
            val seed = lastObservedPrice[request.symbol] ?: priceProvider.lastPrice(request.symbol)
            if (seed != null) trailingHwm[request.id] = seed
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    private fun track(managed: ManagedOrder) {
        orders[managed.id] = managed
    }

    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ) {
        orders[id]?.let { orders[id] = change(it) }
    }

    private fun onAccepted(e: BrokerEvent.OrderAccepted) {
        update(e.clientOrderId) {
            if (it.state == OrderState.PENDING) {
                it.copy(brokerOrderId = e.brokerOrderId ?: it.brokerOrderId, lastUpdatedAt = clock.now())
            } else {
                it.copy(
                    state = OrderState.WORKING,
                    brokerOrderId = e.brokerOrderId ?: it.brokerOrderId,
                    lastUpdatedAt = clock.now(),
                )
            }
        }
    }

    private fun onRejected(e: BrokerEvent.OrderRejected) {
        update(e.clientOrderId) {
            it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now())
        }
    }

    private fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled) {
        update(e.clientOrderId) {
            it.copy(
                state = OrderState.PARTIALLY_FILLED,
                cumulativeFilledQuantity = e.cumulativeFilled,
                avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                lastUpdatedAt = clock.now(),
            )
        }
    }

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        update(e.clientOrderId) {
            val newCumulative = it.cumulativeFilledQuantity + e.quantity
            it.copy(
                state = OrderState.FILLED,
                cumulativeFilledQuantity = newCumulative,
                avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                lastUpdatedAt = clock.now(),
            )
        }
    }

    private fun onCancelled(e: BrokerEvent.OrderCancelled) {
        update(e.clientOrderId) {
            it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now())
        }
    }

    private fun evaluateTriggers(tick: Tick) {
        lastObservedPrice[tick.symbol] = tick.price
        // Update trailing HWMs first so trigger evaluation sees fresh levels.
        for (managed in orders.values.toList()) {
            if (managed.state != OrderState.PENDING) continue
            if (managed.request.symbol != tick.symbol) continue
            updateTrailingHwm(managed, tick.price)
        }

        val triggered: List<ManagedOrder> =
            orders.values
                .filter { it.state == OrderState.PENDING }
                .filter { it.request.symbol == tick.symbol }
                .filter { triggerHit(it, tick.price) }
                .toList()
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
        }
    }

    private fun updateTrailingHwm(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        val params = trailParams(managed.request) ?: return
        val current = trailingHwm[managed.id]
        when (params.side) {
            Side.SELL -> if (current == null || tickPrice > current) trailingHwm[managed.id] = tickPrice
            Side.BUY -> if (current == null || tickPrice < current) trailingHwm[managed.id] = tickPrice
        }
    }

    private fun trailParams(request: OrderRequest): TrailParams? =
        when (request) {
            is OrderRequest.TrailingStop ->
                TrailParams(request.side, request.trailAmount, request.trailMode, limitOffset = null)
            is OrderRequest.TrailingStopLimit ->
                TrailParams(request.side, request.trailAmount, request.trailMode, limitOffset = request.limitOffset)
            else -> null
        }

    private fun trailLevel(managed: ManagedOrder): BigDecimal? {
        val params = trailParams(managed.request) ?: return null
        val hwm = trailingHwm[managed.id] ?: return null
        return when (params.trailMode) {
            TrailMode.ABSOLUTE ->
                if (params.side == Side.SELL) hwm - params.trailAmount else hwm + params.trailAmount
            TrailMode.PERCENT -> {
                val factor = params.trailAmount.divide(BigDecimal("100"), Money.CONTEXT)
                if (params.side == Side.SELL) {
                    hwm
                        .multiply(BigDecimal.ONE - factor, Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING)
                } else {
                    hwm
                        .multiply(BigDecimal.ONE + factor, Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING)
                }
            }
        }
    }

    private fun triggerHit(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ): Boolean =
        when (val request = managed.request) {
            is OrderRequest.Stop ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice else tickPrice <= request.stopPrice
            is OrderRequest.StopLimit ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice else tickPrice <= request.stopPrice
            is OrderRequest.IfTouched ->
                if (request.side == Side.BUY) tickPrice <= request.triggerPrice else tickPrice >= request.triggerPrice
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> {
                val params = trailParams(request) ?: return false
                val level = trailLevel(managed) ?: return false
                if (params.side == Side.SELL) tickPrice <= level else tickPrice >= level
            }
            else -> false
        }

    private data class TrailParams(
        val side: Side,
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        val limitOffset: BigDecimal?,
    )

    private fun fireFallbackTrigger(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        update(managed.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        val internal: OrderRequest =
            when (val req = managed.request) {
                is OrderRequest.Stop ->
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                    )
                is OrderRequest.StopLimit ->
                    OrderRequest.Limit(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        limitPrice = req.limitPrice,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                    )
                is OrderRequest.IfTouched ->
                    if (req.onTrigger == TriggerType.MARKET) {
                        OrderRequest.Market(
                            id = req.id,
                            symbol = req.symbol,
                            side = req.side,
                            quantity = req.quantity,
                            timeInForce = req.timeInForce,
                            timestamp = clock.now(),
                        )
                    } else {
                        OrderRequest.Limit(
                            id = req.id,
                            symbol = req.symbol,
                            side = req.side,
                            quantity = req.quantity,
                            limitPrice = req.limitPrice!!,
                            timeInForce = req.timeInForce,
                            timestamp = clock.now(),
                        )
                    }
                is OrderRequest.TrailingStop ->
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                    )
                is OrderRequest.TrailingStopLimit -> {
                    val level = trailLevel(managed) ?: error("TrailingStopLimit level missing for ${managed.id}")
                    val limitPrice =
                        if (req.side == Side.SELL) level - req.limitOffset else level + req.limitOffset
                    OrderRequest.Limit(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        limitPrice = limitPrice.setScale(Money.SCALE, Money.ROUNDING),
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                    )
                }
                else -> error("Not a Tier 2 fallback type: ${req::class.simpleName}")
            }
        broker.submit(internal)
    }

    private fun blendAvg(
        oldAvg: BigDecimal?,
        oldQty: BigDecimal,
        newPrice: BigDecimal,
        newQty: BigDecimal,
    ): BigDecimal {
        if (oldAvg == null || oldQty.signum() == 0) return newPrice
        val totalQty = oldQty + newQty
        return (oldAvg * oldQty + newPrice * newQty)
            .divide(totalQty, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
