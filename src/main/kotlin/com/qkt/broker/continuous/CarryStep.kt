package com.qkt.broker.continuous

import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * Where one strategy's carry across a roll stands (design: live continuous futures §2.4a). A waiting
 * state names the leg it waits for, so a carry can be persisted and resumed; [quantity] is the signed
 * position carried.
 */
internal sealed interface CarryStep {
    val strategyId: String
    val quantity: BigDecimal

    /** The closing leg [leg] is out on the old contract. */
    data class Closing(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val leg: OrderRequest.Market,
    ) : CarryStep

    /** The old contract was closed at [close]; the opening leg [leg] is out on the new one. */
    data class Opening(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val close: BrokerEvent.OrderFilled,
        val leg: OrderRequest.Market,
    ) : CarryStep

    /** Carried to the new contract, as [entry] records. */
    data class Carried(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val entry: RollEntry,
    ) : CarryStep

    /** Not carried, for [reason]; [close] is the position's close on the stream when the venue closed it, or part of it. */
    data class Stopped(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val reason: String,
        val close: BrokerEvent.OrderFilled?,
    ) : CarryStep
}
