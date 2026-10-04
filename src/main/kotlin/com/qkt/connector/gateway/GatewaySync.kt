package com.qkt.connector.gateway

import com.qkt.broker.PositionAccountingMode
import java.math.BigDecimal

/** What a resynchronization found: the account's [equity], [accounting] mode and net [positions] by venue code. */
internal data class GatewaySyncState(
    val equity: BigDecimal,
    val accounting: PositionAccountingMode,
    val positions: Map<String, BigDecimal>,
    val tickets: Map<String, Map<String, BigDecimal>> = emptyMap(),
)

/**
 * Brings the engine back to the gateway's truth after a start, a `reset` or a lost sequence: every
 * working order, every deal and every settlement since the last booked deal go through [onOrder],
 * [onFill] and [onSettlement] (each drops what was already reported); an order still open in the engine
 * but no longer working at the gateway is resolved by id, so one the venue ended meanwhile ends here
 * too. The deal window starts at [fromMs] and moves only with deals of orders [isOwned].
 */
internal class GatewaySync(
    private val client: GatewayClient,
    private val onOrder: (WireOrder) -> Unit,
    private val onFill: (WireFill) -> Unit,
    private val onSettlement: (WireSettlement) -> Unit,
    private val isOwned: (String) -> Boolean,
    @Volatile private var fromMs: Long,
    private val now: () -> Long,
) {
    /** Reconciles; [open] are the orders the engine still holds open. */
    fun run(open: Set<String>): GatewaySyncState {
        val account = client.account()
        val positions = client.positions()
        val accounting =
            when (positions.accounting) {
                "netting" -> PositionAccountingMode.NETTING
                "hedging" -> PositionAccountingMode.HEDGING
                else -> throw GatewayProtocolException("position accounting '${positions.accounting}'")
            }
        val working = client.orders()
        val to = now()
        val deals = client.deals(fromMs, to)
        val settlements = client.settlements(fromMs, to)
        working.forEach(onOrder)
        deals.forEach(onFill)
        settlements.forEach(onSettlement)
        for (id in open - working.map { it.clientOrderId }.toSet()) {
            client.order(id)?.let { ended ->
                client.dealsOf(id).forEach(onFill)
                onOrder(ended)
            }
        }
        deals.filter { isOwned(it.clientOrderId) }.maxOfOrNull { it.time }?.let { newest ->
            fromMs =
                maxOf(fromMs, newest)
        }
        val net =
            positions.positions
                .groupBy { it.symbol }
                .mapValues { (_, held) -> held.fold(BigDecimal.ZERO) { q, p -> q.add(BigDecimal(p.quantity)) } }
        val tickets =
            positions.positions
                .filter { it.ticket != null }
                .groupBy { it.symbol }
                .mapValues { (_, held) -> held.associate { it.ticket!! to BigDecimal(it.quantity) } }
        return GatewaySyncState(BigDecimal(account.equity), accounting, net, tickets)
    }
}
