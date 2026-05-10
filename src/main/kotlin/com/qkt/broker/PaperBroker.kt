package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * In-process simulator that books fills against the latest tracker price.
 *
 * The default broker for backtests, `qkt run`, and paper-trading deployments. Market
 * orders fill at the tracker's latest price; limits, stops, and if-touched orders are
 * parked in a working list and resolved as ticks print through.
 *
 * No slippage model, no rejection model, no latency — by design. Strategies that want
 * those should test against [com.qkt.broker.composite.CompositeBroker] with a simulated
 * latency layer or run against an actual venue.
 */
class PaperBroker(
    private val bus: EventBus,
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider,
) : Broker {
    private val log = LoggerFactory.getLogger(PaperBroker::class.java)

    private val working: MutableList<OrderRequest> = mutableListOf()

    init {
        bus.subscribe<TickEvent> { e -> onTick(e.tick) }
    }

    override val name: String = "Paper"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
        )

    override fun submit(request: OrderRequest): SubmitAck {
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        when (request) {
            is OrderRequest.Market -> fillMarket(request)
            is OrderRequest.Limit, is OrderRequest.Stop,
            is OrderRequest.StopLimit, is OrderRequest.IfTouched,
            ->
                working.add(request)
            else -> error("PaperBroker received unexpected order type: ${request::class.simpleName}")
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    override fun cancel(orderId: String) {
        val match = working.firstOrNull { it.id == orderId }
        val removed = working.removeAll { it.id == orderId }
        if (removed) {
            bus.publish(
                BrokerEvent.OrderCancelled(
                    clientOrderId = orderId,
                    brokerOrderId = orderId,
                    reason = "user cancel",
                    strategyId = match?.strategyId ?: "",
                    timestamp = clock.now(),
                ),
            )
        }
    }

    fun onTick(tick: Tick) {
        if (working.isEmpty()) return
        val toFill = working.filter { req -> req.symbol == tick.symbol && checkTrigger(req, tick.price) }
        for (wo in toFill) {
            working.remove(wo)
            fillFromTrigger(wo, tick.price)
        }
    }

    private fun fillMarket(req: OrderRequest.Market) {
        val px = priceProvider.lastPrice(req.symbol)
        if (px == null) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = req.id,
                    brokerOrderId = req.id,
                    reason = "no price",
                    strategyId = req.strategyId,
                    timestamp = clock.now(),
                ),
            )
            return
        }
        publishFill(req.id, req.symbol, req.side, px, req.quantity, req.strategyId)
    }

    private fun fillFromTrigger(
        req: OrderRequest,
        tickPrice: BigDecimal,
    ) {
        val (fillPrice, side, qty) =
            when (req) {
                is OrderRequest.Limit -> Triple(tickPrice, req.side, req.quantity)
                is OrderRequest.Stop -> Triple(tickPrice, req.side, req.quantity)
                is OrderRequest.StopLimit -> Triple(req.limitPrice, req.side, req.quantity)
                is OrderRequest.IfTouched ->
                    if (req.onTrigger == TriggerType.MARKET) {
                        Triple(tickPrice, req.side, req.quantity)
                    } else {
                        Triple(req.limitPrice!!, req.side, req.quantity)
                    }
                is OrderRequest.Market -> error("Market should not reach fillFromTrigger")
                else -> error("PaperBroker fillFromTrigger received unexpected type: ${req::class.simpleName}")
            }
        publishFill(req.id, req.symbol, side, fillPrice, qty, req.strategyId)
    }

    private fun publishFill(
        clientOrderId: String,
        symbol: String,
        side: Side,
        price: BigDecimal,
        qty: BigDecimal,
        strategyId: String,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = clientOrderId,
                brokerOrderId = clientOrderId,
                symbol = symbol,
                side = side,
                price = price.setScale(Money.SCALE, Money.ROUNDING),
                quantity = qty,
                strategyId = strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    private fun checkTrigger(
        req: OrderRequest,
        tickPrice: BigDecimal,
    ): Boolean =
        when (req) {
            is OrderRequest.Limit ->
                if (req.side == Side.BUY) tickPrice <= req.limitPrice else tickPrice >= req.limitPrice
            is OrderRequest.Stop ->
                if (req.side == Side.BUY) tickPrice >= req.stopPrice else tickPrice <= req.stopPrice
            is OrderRequest.StopLimit ->
                if (req.side == Side.BUY) tickPrice >= req.stopPrice else tickPrice <= req.stopPrice
            is OrderRequest.IfTouched ->
                if (req.side == Side.BUY) tickPrice <= req.triggerPrice else tickPrice >= req.triggerPrice
            is OrderRequest.Market -> false
            else -> false
        }
}
