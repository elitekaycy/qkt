package com.qkt.connector.gateway

import com.qkt.broker.PositionAccountingMode
import java.math.BigDecimal

/**
 * What a resynchronization found: the account's [equity] and [accounting] mode; of the unconfirmed
 * submits, the ones the gateway knows ([found]: working, or filled in the deals) and the ones it
 * never placed ([neverPlaced]).
 */
internal data class GatewaySyncState(
    val equity: BigDecimal,
    val accounting: PositionAccountingMode,
    val found: Set<String>,
    val neverPlaced: Set<String>,
)

/**
 * Brings the engine back to the gateway's truth after a start, a `reset` or a lost sequence: every
 * working order, every deal and every contract settlement since the last booked fill go through
 * [onOrder], [onFill] and [onSettlement] (each drops what was already reported). A submit the gateway never
 * answered is found among them, or was never placed. The deal window starts at [fromMs] and moves to
 * the newest deal seen.
 */
internal class GatewaySync(
    private val client: GatewayClient,
    private val onOrder: (WireOrder) -> Unit,
    private val onFill: (WireFill) -> Unit,
    private val onSettlement: (WireSettlement) -> Unit,
    @Volatile private var fromMs: Long,
    private val now: () -> Long,
) {
    /** Reconciles; [unconfirmed] are the client order ids whose submit got no answer. */
    fun run(unconfirmed: Set<String>): GatewaySyncState {
        val account = client.account()
        val mode = client.positions().accounting
        val accounting =
            when (mode) {
                "netting" -> PositionAccountingMode.NETTING
                "hedging" -> PositionAccountingMode.HEDGING
                else -> throw GatewayProtocolException("position accounting '$mode'")
            }
        val working = client.orders()
        val to = now()
        val deals = client.deals(fromMs, to)
        val settlements = client.settlements(fromMs, to)
        working.forEach(onOrder)
        deals.forEach(onFill)
        settlements.forEach(onSettlement)
        deals.maxOfOrNull { it.time }?.let { newest -> fromMs = maxOf(fromMs, newest) }
        val known = working.map { it.clientOrderId }.toSet() + deals.map { it.clientOrderId }
        return GatewaySyncState(
            BigDecimal(account.equity),
            accounting,
            unconfirmed intersect known,
            unconfirmed - known,
        )
    }
}

/** An order a restart restored: [strategyId]'s order for [quantity], of which [alreadyFilled] was booked before. */
internal data class RecoveredOrder(
    val clientOrderId: String,
    val strategyId: String,
    val quantity: BigDecimal,
    val alreadyFilled: BigDecimal,
)
