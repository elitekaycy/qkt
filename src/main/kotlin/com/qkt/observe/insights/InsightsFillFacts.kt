package com.qkt.observe.insights

import com.qkt.backtest.FillState
import com.qkt.broker.CloseReason
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.VenueLeg
import com.qkt.common.Side
import com.qkt.events.FillAccountedEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.observe.insights.InsightsEntryRisks.Companion.bounded
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/**
 * What qkt knows about each fill that no connector states, resolved here so the collector never
 * guesses: the leg it made (`IN`/`OUT`/`INOUT`), the position it belongs to (the venue's ticket where it
 * names one, else qkt's id, [VenueLeg]), why it closed ([CloseReason]), its strategy, and for an entry its
 * initial stop, target and risk in account currency. A fill is judged before qkt's accounting applies it
 * ([seen], against the session's [positions]) and again, exactly, once accounted ([accounted]); a hedging
 * account's legs follow the order's intent instead, since its net position says nothing of one ticket.
 * Written on the engine thread; the state poller reads entry risk by position id ([entryFields]).
 */
class InsightsFillFacts(
    private val venues: InsightsVenues = InsightsVenues(),
    private val positions: PositionProvider? = null,
    private val accountingMode: (String) -> PositionAccountingMode = { PositionAccountingMode.UNKNOWN },
    capacity: Int = 10_000,
) {
    private data class Seen(
        val leg: VenueLeg,
        val brokerOrderId: String?,
        val exitReason: ExitReason?,
    )

    private val seen = bounded<String, Seen>(capacity)
    private val closing = bounded<String, Boolean>(capacity)
    private val risks = InsightsEntryRisks(venues, capacity)

    /** An order was submitted: remember a bracket's levels, and the orders sent to close a position. */
    fun submitted(request: OrderRequest) {
        risks.submitted(request)
        when (request) {
            is OrderRequest.Bracket -> submitted(request.entry)
            is OrderRequest.TimeExit -> submitted(request.target)
            is OrderRequest.Market ->
                if (request.closesTicket != null || request.legIntent is LegIntent.Close) closing[request.id] = true
            else -> Unit
        }
    }

    /** A fill, before accounting applies it: its leg against the position the session holds now. */
    fun seen(
        orderId: String,
        brokerOrderId: String?,
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        timestamp: Long,
        exitReason: ExitReason?,
    ) {
        val leg = VenueLeg.predict(symbol, positions?.positionFor(symbol), side, quantity, timestamp)
        seen[key(orderId, timestamp)] = Seen(leg, brokerOrderId, exitReason)
    }

    /** A fill as qkt accounted it: its exact leg, and the conversion its P&L went through. */
    fun accounted(e: FillAccountedEvent) {
        venues.converted(e.symbol, e.nativeCurrency, e.accountCurrency, e.conversionRate)
        val k = key(e.orderId, e.executedAt)
        val prior = seen[k] ?: return
        seen[k] = prior.copy(leg = VenueLeg.of(e.accountPositionBefore, e.accountPositionAfter))
    }

    /** The facts an `order.filled` or `order.partially_filled` of [orderId] at [timestamp] carries. */
    fun fillFields(
        orderId: String,
        strategyId: String,
        symbol: String,
        price: BigDecimal,
        quantity: BigDecimal,
        timestamp: Long,
    ): Map<String, Any?> {
        val s = seen[key(orderId, timestamp)] ?: return attribution(strategyId)
        val leg = legOf(orderId, symbol, s)
        val opened = if (leg.entry == "INOUT") leg.nextPositionId else leg.positionId.takeIf { leg.entry == "IN" }
        val risk = opened?.let { risks.entered(it, orderId, symbol, price, quantity) }.orEmpty()
        return attribution(strategyId) + legFields(leg, orderId, s.exitReason) + risk +
            ("instrument" to venues.instrumentFields(symbol))
    }

    /** The facts a `trade.closed` of [trade] carries; [fill] is what accounting made of it. */
    fun closedFields(
        trade: com.qkt.execution.Trade,
        strategyId: String,
        fill: FillState,
    ): Map<String, Any?> {
        val s = seen[key(trade.orderId, trade.timestamp)]
        val leg =
            if (s != null) {
                legOf(trade.orderId, trade.symbol, s)
            } else {
                VenueLeg.of(fill.accountPositionBefore, fill.accountPositionAfter)
            }
        return attribution(strategyId) + legFields(leg, trade.orderId, s?.exitReason)
    }

    /** `initialStop`, `initialTarget` and `riskAmount` of the position [positionId], when its entry had a bracket. */
    fun entryFields(positionId: String): Map<String, Any?> = risks.fields(positionId)

    private fun legOf(
        orderId: String,
        symbol: String,
        s: Seen,
    ): VenueLeg {
        val named = venues.describedFor(symbol)?.fillPositionId(s.brokerOrderId)
        if (accountingMode(symbol) == PositionAccountingMode.HEDGING) {
            val reason = CloseReason.fromOrderId(orderId)
            val closes = s.exitReason != null || closing[orderId] == true || reason in EXIT_IDS
            return VenueLeg(if (closes) "OUT" else "IN", named ?: s.leg.positionId)
        }
        return if (named == null) s.leg else s.leg.copy(positionId = named, nextPositionId = null)
    }

    private fun legFields(
        leg: VenueLeg,
        orderId: String,
        exitReason: ExitReason?,
    ): Map<String, Any?> =
        mapOf(
            "positionId" to leg.positionId,
            "nextPositionId" to leg.nextPositionId,
            "legEntry" to leg.entry,
            "closeReason" to CloseReason.of(exitReason, orderId).takeIf { leg.entry != "IN" },
        )

    private fun attribution(strategyId: String): Map<String, Any?> =
        mapOf(
            "strategyId" to strategyId.takeIf { it.isNotBlank() },
            "attribution" to if (strategyId.isBlank()) "none" else "order",
        )

    private fun key(
        orderId: String,
        timestamp: Long,
    ) = "$orderId@$timestamp"

    private companion object {
        val EXIT_IDS = setOf(CloseReason.STOP, CloseReason.TAKE_PROFIT, CloseReason.TIMED)
    }
}
