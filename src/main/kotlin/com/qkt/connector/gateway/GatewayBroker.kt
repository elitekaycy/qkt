package com.qkt.connector.gateway

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/**
 * One trading session's [Broker] on a VGP v1 gateway account: a thin view over the account's shared
 * [GatewaySession], serving [strategy] (null: every strategy of a multi-strategy session). It sends
 * that session's orders and publishes their events, and every contract settlement, on [bus].
 * `reduce_only` is judged against [positions], the session's view of what it holds.
 */
class GatewayBroker internal constructor(
    private val session: GatewaySession,
    private val bus: EventBus,
    private val clock: Clock,
    private val positions: PositionProvider,
    strategy: String?,
) : Broker {
    private val attachment = session.attach(strategy, bus::publish)

    override val name: String = "Gateway"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
        )

    override val supportsAccountEquity: Boolean get() = true

    override fun supports(symbol: String): Boolean = session.symbols.venue(symbol) != null

    override fun submit(request: OrderRequest): SubmitAck {
        val code =
            session.symbols.venue(request.symbol)
                ?: return refuse(request, "${request.symbol} is not one of the gateway's instruments")
        val body =
            when (val mapping = GatewayOrders.map(request, code, positions)) {
                is GatewayOrderMapping.Unsupported -> return refuse(request, mapping.reason)
                is GatewayOrderMapping.Send -> mapping.body
            }
        session.submit(request.strategyId, body) { reason -> reject(request, reason) }
        return SubmitAck(request.id, brokerOrderId = null, accepted = true)
    }

    override fun cancel(orderId: String) = session.cancel(orderId)

    override fun recoverPendingOrders(
        orders: List<ManagedOrder>,
        bookedTickets: Set<String>,
    ): Set<String> =
        session.recover(
            orders.map {
                RecoveredOrder(
                    it.id,
                    it.request.strategyId,
                    it.request.quantity,
                    it.cumulativeFilledQuantity,
                )
            },
        )

    override fun getOpenPositions(): Map<String, List<Position>> =
        session.client
            .positions()
            .positions
            .mapNotNull { p ->
                session.symbols.qkt(p.symbol)?.let { s ->
                    s to
                        Position(s, BigDecimal(p.quantity), BigDecimal(p.avgPrice), p.openedAt)
                }
            }.groupBy({ it.first }, { it.second })

    override fun accountEquity(): BigDecimal? = session.equity

    override fun positionAccountingMode(symbol: String): PositionAccountingMode = session.accounting

    override fun shutdown() = attachment.close()

    private fun reject(
        request: OrderRequest,
        reason: String,
    ) = bus.publish(BrokerEvent.OrderRejected(request.id, null, reason, request.strategyId, clock.now()))

    private fun refuse(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        reject(request, reason)
        return SubmitAck(request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }
}
