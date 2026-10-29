package com.qkt.connector.gateway

import com.qkt.broker.VenueLeg
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import java.math.BigDecimal

/** What qkt's fill accounting made of one gateway fill: its [leg], the gross P&L it realized, its strategy. */
internal data class GatewayLegFact(
    val leg: VenueLeg,
    val grossRealized: BigDecimal,
    val strategyId: String,
)

/**
 * qkt's accounting of one gateway account's fills, kept to state on each venue deal what VGP v1 does not
 * carry: its leg, the position it belongs to (a qkt-minted id, [VenueLeg]) and the P&L qkt realized on it.
 * A deal is matched by the venue's `fill_id` and its order: the ledger notes each fill it books, in order
 * ([booked]), and the n-th execution qkt accounts for an order ([record], engine threads) is that order's
 * n-th booked fill. Never by time: a gateway may stamp a fill in its deal list apart from the time its
 * `fill` event carried (the paper gateway, 26 ms earlier). The oldest of [capacity] orders are forgotten.
 */
internal class GatewayFillLegs(
    private val capacity: Int = 20_000,
) {
    private class OrderFills {
        val fillIds = mutableListOf<String>()
        val facts = mutableListOf<GatewayLegFact>()
    }

    private val orders =
        object : LinkedHashMap<String, OrderFills>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OrderFills>) = size > capacity
        }
    private val open = HashMap<String, OpenPosition>()

    /** The open position of one symbol as qkt last booked it: its id, the strategies that hold it, its open time. */
    data class OpenPosition(
        val positionId: String,
        val owners: Set<String>,
        val openedAt: Long? = null,
    )

    /** The ledger booked venue fill [fillId] of engine order [engineOrderId]: its next accounted execution. */
    @Synchronized
    fun booked(
        engineOrderId: String,
        fillId: String,
    ) {
        orders.getOrPut(engineOrderId) { OrderFills() }.fillIds += fillId
    }

    /** Takes one accounted fill; only an execution is a deal, but any accounting may move the position. */
    @Synchronized
    fun record(e: FillAccountedEvent) {
        val after = e.accountPositionAfter?.takeIf { it.quantity.signum() != 0 }
        val current = after?.let(VenueLeg::idOf)
        if (current == null) {
            open.remove(e.symbol)
        } else {
            val held = open[e.symbol]?.takeIf { it.positionId == current }?.owners.orEmpty()
            open[e.symbol] = OpenPosition(current, held + e.strategyId, after?.openedAt)
        }
        if (e.kind != FillAccountingKind.EXECUTION) return
        val fact = GatewayLegFact(VenueLeg.of(e.accountPositionBefore, after), e.grossAccountRealized, e.strategyId)
        orders.getOrPut(e.orderId) { OrderFills() }.facts += fact
    }

    /** What qkt booked for venue fill [fillId] of engine order [engineOrderId], or null when it booked none. */
    @Synchronized
    fun of(
        fillId: String,
        engineOrderId: String,
    ): GatewayLegFact? {
        val order = orders[engineOrderId] ?: return null
        val index = order.fillIds.indexOf(fillId).takeIf { it >= 0 } ?: return null
        return order.facts.getOrNull(index)
    }

    /** [symbol]'s open position as qkt last booked it, or null when flat or never booked here. */
    @Synchronized
    fun open(symbol: String): OpenPosition? = open[symbol]
}
