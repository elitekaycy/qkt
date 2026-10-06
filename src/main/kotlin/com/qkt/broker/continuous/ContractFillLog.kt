package com.qkt.broker.continuous

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * One engine fill on a continuous [stream], as it executed: on [contract] at [contractPrice], seen by
 * the engine at [streamPrice] in the adjusted series.
 */
data class ContractFill(
    val atMs: Long,
    val strategyId: String,
    val stream: String,
    val orderId: String,
    val contract: String,
    val side: Side,
    val quantity: BigDecimal,
    val contractPrice: BigDecimal,
    val streamPrice: BigDecimal,
)

/** Every engine fill a run's continuous streams executed, in order; the source of `contracts.csv`. */
class ContractFillLog {
    private val recorded = mutableListOf<ContractFill>()

    /** Record [fill]. */
    fun record(fill: ContractFill) {
        recorded += fill
    }

    /** The fills recorded so far, oldest first. */
    val entries: List<ContractFill> get() = recorded.toList()
}

/** [engineFill] as the engine saw it, executed as [venueFill] on the contract. */
internal fun contractFill(
    venueFill: com.qkt.events.BrokerEvent.OrderFilled,
    engineFill: com.qkt.events.BrokerEvent.OrderFilled,
): ContractFill =
    ContractFill(
        atMs = venueFill.timestamp,
        strategyId = engineFill.strategyId,
        stream = engineFill.symbol,
        orderId = engineFill.clientOrderId,
        contract = venueFill.symbol,
        side = engineFill.side,
        quantity = engineFill.quantity,
        contractPrice = venueFill.price,
        streamPrice = engineFill.price,
    )
