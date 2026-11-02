package com.qkt.broker

import com.qkt.common.Side
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import java.math.BigDecimal

/** The order id prefix of a settlement's closes ([closesFor]). */
internal const val SETTLE_PREFIX = "settle:"

/**
 * One strategy's holding of [contract] closed by the venue's settlement, as qkt's accounting booked it at
 * [atMs]: the leg it made, the gross P&L it realized and the venue's costs on it, in the account's currency.
 * No venue fill carries any of it, so this is all a settlement's deal can be stated from.
 */
data class SettledClose(
    val contract: String,
    val strategyId: String,
    val side: Side,
    val quantity: BigDecimal,
    val leg: VenueLeg,
    val grossRealized: BigDecimal,
    val venueCosts: BigDecimal,
    val atMs: Long,
)

/**
 * This accounting as a settlement's close ([closesFor]), or null when it is not one. The contract is the
 * one its order id names (`settle:<contract>:<strategy>`): a continuous stream's lane books the close on the
 * stream, so the event's own symbol is the stream's.
 */
internal fun FillAccountedEvent.settledClose(): SettledClose? {
    if (kind != FillAccountingKind.EXECUTION || !orderId.startsWith(SETTLE_PREFIX)) return null
    val moved =
        (strategyPositionAfter?.quantity ?: BigDecimal.ZERO) - (strategyPositionBefore?.quantity ?: BigDecimal.ZERO)
    return SettledClose(
        contract = orderId.removePrefix(SETTLE_PREFIX).substringBeforeLast(':'),
        strategyId = strategyId,
        side = if (moved.signum() > 0) Side.BUY else Side.SELL,
        quantity = moved.abs(),
        leg = VenueLeg.of(accountPositionBefore, accountPositionAfter?.takeIf { it.quantity.signum() != 0 }),
        grossRealized = grossAccountRealized,
        venueCosts = venueCostsAccount,
        atMs = executedAt,
    )
}
