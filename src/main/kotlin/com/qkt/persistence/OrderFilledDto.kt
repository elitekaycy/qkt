package com.qkt.persistence

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** On-disk shape of one [BrokerEvent.OrderFilled], every field kept so a restored fill is the fill that was saved. */
@Serializable
internal data class OrderFilledDto(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val symbol: String,
    val side: String,
    val price: String,
    val quantity: String,
    val strategyId: String,
    val timestamp: Long,
    val sequenceId: Long,
    val updatesOrderExecution: Boolean,
    val venueCosts: String,
    val typedVenueCosts: List<VenueCostDto>,
    val exitReason: String?,
) {
    fun toDomain() =
        BrokerEvent.OrderFilled(
            clientOrderId,
            brokerOrderId,
            symbol,
            Side.valueOf(side),
            BigDecimal(price),
            BigDecimal(quantity),
            strategyId,
            timestamp,
            sequenceId,
            updatesOrderExecution,
            BigDecimal(venueCosts),
            typedVenueCosts.map { it.toDomain() },
            exitReason?.let(ExitReason::valueOf),
        )

    companion object {
        fun of(f: BrokerEvent.OrderFilled) =
            OrderFilledDto(
                f.clientOrderId,
                f.brokerOrderId,
                f.symbol,
                f.side.name,
                f.price.toPlainString(),
                f.quantity.toPlainString(),
                f.strategyId,
                f.timestamp,
                f.sequenceId,
                f.updatesOrderExecution,
                f.venueCosts.toPlainString(),
                f.typedVenueCosts.map(VenueCostDto::of),
                f.exitReason?.name,
            )
    }
}

/** On-disk shape of one [VenueCost]. */
@Serializable
internal data class VenueCostDto(
    val kind: String,
    val amount: String,
    val currency: String,
    val timestamp: Long,
) {
    fun toDomain() = VenueCost(CostKind.valueOf(kind), MoneyAmount(BigDecimal(amount), currency), timestamp)

    companion object {
        fun of(c: VenueCost) =
            VenueCostDto(c.kind.name, c.amount.amount.toPlainString(), c.amount.currency, c.timestamp)
    }
}
