package com.qkt.connector.mt5

import com.qkt.broker.AdjustmentKind
import com.qkt.broker.AdjustmentSource
import com.qkt.broker.BrokerAdjustment

/**
 * The non-trade deals an MT5 account's history holds, as [BrokerAdjustment]s: every `ENUM_DEAL_TYPE` other than BUY
 * (0) and SELL (1). A broker that books a stock CFD dividend as a balance operation names it in the comment, so a
 * balance deal tied to a symbol or whose comment says "dividend" is a [AdjustmentKind.DIVIDEND]. Unknown types are
 * kept as [AdjustmentKind.UNKNOWN] rather than dropped.
 */
internal class MT5Adjustments(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
) : AdjustmentSource {
    private val symbols = MT5Symbol(profile.symbolPolicy)

    override fun adjustments(
        from: Long,
        to: Long,
    ): List<BrokerAdjustment> {
        val deals =
            client.getDeals(from, to)
                ?: error("MT5Broker ${profile.name} adjustments: gateway read failed (${client.lastReadFailure()})")
        val prefix = profile.name.uppercase()
        return deals.filter { it.type != BUY && it.type != SELL }.map { d ->
            val symbol = d.symbol.takeIf { it.isNotBlank() }?.let { "$prefix:${symbols.toQkt(it)}" }
            BrokerAdjustment(
                broker = prefix,
                dealTicket = d.ticket.toString(),
                kind = kindOf(d.type, d.comment, symbol != null),
                amount = d.profit + d.commission + d.swap + d.fee,
                symbol = symbol,
                positionTicket = d.positionTicket.takeIf { it != 0L }?.toString(),
                magic = d.magic.takeIf { it != 0 },
                comment = d.comment,
                ts = d.timeMs,
            )
        }
    }

    companion object {
        private const val BUY = 0
        private const val SELL = 1
        private val DIVIDEND_COMMENT = Regex("divid", RegexOption.IGNORE_CASE)

        /** The kind of MT5 deal [type] (`ENUM_DEAL_TYPE`), reading a balance deal's [comment] and symbol for a dividend. */
        fun kindOf(
            type: Int,
            comment: String?,
            hasSymbol: Boolean,
        ): AdjustmentKind =
            when (type) {
                2 ->
                    if (hasSymbol || DIVIDEND_COMMENT.containsMatchIn(comment.orEmpty())) {
                        AdjustmentKind.DIVIDEND
                    } else {
                        AdjustmentKind.BALANCE
                    }
                3 -> AdjustmentKind.CREDIT
                4 -> AdjustmentKind.CHARGE
                5, 13, 14 -> AdjustmentKind.CORRECTION
                6 -> AdjustmentKind.BONUS
                in 7..11 -> AdjustmentKind.COMMISSION
                12 -> AdjustmentKind.INTEREST
                15, 16 -> AdjustmentKind.DIVIDEND
                17 -> AdjustmentKind.TAX
                else -> AdjustmentKind.UNKNOWN
            }
    }
}
