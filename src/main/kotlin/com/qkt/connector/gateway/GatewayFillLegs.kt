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
 * Fed by every attached broker's [FillAccountedEvent] (engine threads), read by the state poller. Keyed by
 * engine order id and execution time, the two a venue deal carries; the oldest of [capacity] are forgotten.
 */
internal class GatewayFillLegs(
    private val capacity: Int = 20_000,
) {
    private val facts = LinkedHashMap<String, MutableList<GatewayLegFact>>()
    private val open = HashMap<String, OpenPosition>()

    /** The open position of one symbol as qkt last booked it: its id and the strategies that hold it. */
    data class OpenPosition(
        val positionId: String,
        val owners: Set<String>,
    )

    /** Takes one accounted fill; only an execution is a deal, but any accounting may move the position. */
    @Synchronized
    fun record(e: FillAccountedEvent) {
        val after = e.accountPositionAfter?.takeIf { it.quantity.signum() != 0 }
        val current = after?.let(VenueLeg::idOf)
        if (current == null) {
            open.remove(e.symbol)
        } else {
            val held = open[e.symbol]?.takeIf { it.positionId == current }?.owners.orEmpty()
            open[e.symbol] = OpenPosition(current, held + e.strategyId)
        }
        if (e.kind != FillAccountingKind.EXECUTION) return
        val fact = GatewayLegFact(VenueLeg.of(e.accountPositionBefore, after), e.grossAccountRealized, e.strategyId)
        facts.getOrPut(key(e.orderId, e.executedAt)) { mutableListOf() } += fact
        if (facts.size > capacity) facts.remove(facts.keys.first())
    }

    /** What qkt booked for engine order [engineOrderId]'s fills executed at [executedAt], in booking order. */
    @Synchronized
    fun of(
        engineOrderId: String,
        executedAt: Long,
    ): List<GatewayLegFact> = facts[key(engineOrderId, executedAt)]?.toList().orEmpty()

    /** [symbol]'s open position as qkt last booked it, or null when flat or never booked here. */
    @Synchronized
    fun open(symbol: String): OpenPosition? = open[symbol]

    private fun key(
        orderId: String,
        executedAt: Long,
    ) = "$orderId@$executedAt"
}
