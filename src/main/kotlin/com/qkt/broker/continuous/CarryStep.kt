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

    /** A step waiting for the venue's answer to its [leg]. */
    sealed interface Waiting : CarryStep {
        val leg: OrderRequest.Market
    }

    /** The closing leg [leg] is out on the old contract. */
    data class Closing(
        override val strategyId: String,
        override val quantity: BigDecimal,
        override val leg: OrderRequest.Market,
    ) : Waiting

    /** The old contract was closed at [close]; the opening leg [leg] is out on the new one. */
    data class Opening(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val close: BrokerEvent.OrderFilled,
        override val leg: OrderRequest.Market,
    ) : Waiting

    /** Carried to the new contract: the old contract closed at [close], the new one opened at [open]. */
    data class Carried(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val close: BrokerEvent.OrderFilled,
        val open: BrokerEvent.OrderFilled,
    ) : CarryStep

    /** Not carried, for [reason]; [close] is the position's close on the stream when the venue closed it, or part of it. */
    data class Stopped(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val reason: String,
        val close: BrokerEvent.OrderFilled?,
    ) : CarryStep
}
