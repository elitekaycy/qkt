package com.qkt.connector.gateway

import com.qkt.broker.BookedLeg
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.instrument.TickSteps
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/**
 * One trading session's [Broker] on a VGP v1 gateway account: a thin view over the account's shared
 * [GatewaySession], serving [strategy] (null: every strategy of a multi-strategy session). It sends that
 * session's orders and publishes their events, and every contract settlement, on [bus]; `reduce_only`
 * is judged against [positions], the session's view of what it holds, and the account's. A gateway
 * account's venue positions are account-wide, whether one strategy trades it or several: startup reconcile
 * trusts each strategy's persisted book, and the account checks their total against the venue whenever a
 * strategy is ready ([GatewaySession.ready]). [optionGrid] is an option contract's declared price grid.
 */
class GatewayBroker internal constructor(
    private val session: GatewaySession,
    private val bus: EventBus,
    private val clock: Clock,
    private val positions: PositionProvider,
    strategy: String?,
    private val optionGrid: (String) -> TickSteps? = { null },
) : Broker {
    private val attachment = session.attach(strategy, positions, bus::publish)

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
        val code = session.symbols.venue(request.symbol)
        if (code == null) {
            session.refreshListing()
            return refuse(request, "${request.symbol} is not in the gateway's listing (refreshing it)")
        }
        // An option's grid steps up with its price, which the listing's one tick cannot say.
        val grid = optionGrid(request.symbol) ?: session.symbols.tick(request.symbol)?.let(::TickSteps)
        val body =
            when (val mapping = GatewayOrders.map(request, code, positions, session.account.quantity(code), grid)) {
                is GatewayOrderMapping.Unsupported -> return refuse(request, mapping.reason)
                is GatewayOrderMapping.Send -> mapping.body
            }
        session.submit(request.strategyId, attachment, body) { reason -> reject(request, reason) }
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
                    GatewayClientIds.of(it.request),
                    it.request.strategyId,
                    it.request.quantity,
                    it.cumulativeFilledQuantity,
                )
            },
            attachment,
        )

    /** Called once the session is restored: settles contracts that expired while away and checks the account total. */
    override fun watchBookedLegs(supplier: () -> List<BookedLeg>) = session.ready(attachment)

    override fun getOpenPositions(): Map<String, List<Position>> =
        session.client
            .positions()
            .positions
            .map { p ->
                session.symbols.qkt(p.symbol).let { s ->
                    s to
                        Position(s, BigDecimal(p.quantity), BigDecimal(p.avgPrice), p.openedAt)
                }
            }.groupBy({ it.first }, { it.second })

    override fun isAccountWide(symbol: String): Boolean = session.symbols.owns(symbol)

    /**
     * The account's equity read now from `/v1/account` (the equity poller's thread; unrealized P&L moves
     * with every price, and the gateway sends no `account` event to keep it current).
     */
    override fun accountEquity(): BigDecimal? =
        session.client
            .account()
            .also(session.account::account)
            .equity
            .let(::BigDecimal)

    override fun positionAccountingMode(symbol: String): PositionAccountingMode = session.account.accounting

    override fun shutdown() {
        session.detach(attachment)
    }

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
