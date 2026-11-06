package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerDeal
import com.qkt.broker.CloseReason
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.persistence.PersistedLeg
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/** Order id prefix of what books a downtime close, `reconcile:<legId>`; it stays out of today's loss budget. */
internal const val DOWNTIME_CLOSE_ID_PREFIX = "reconcile:"

/**
 * Positions that closed at the venue while the daemon was down. The startup reconcile retires each such leg
 * here; once the pipeline exists its close is booked like a venue close the engine saw live: the leg is put
 * back and a closing fill `reconcile:<legId>` at the venue's price, time and reason runs through the fill path,
 * so positions, P&L, the order journal and insights all record it, e.g. ticket 9001 stopped out overnight at
 * 2000.00 books a SELL fill closing that leg at the next start. The reconcile already dropped the leg from disk,
 * so a later start never books it again. The owning strategy's rule edges are cleared so its entry can fire again.
 */
internal class DowntimeCloses(
    private val broker: Broker,
    private val clock: Clock,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    // Legs that closed while down, with their venue closing deals. Booked once the pipeline exists.
    private val closes = ArrayList<DowntimeClose>()

    // Strategies with a leg that closed while the daemon was down. Their persisted rule
    // edges still describe the bar that opened the position, so an entry condition that is
    // true again after the restart would show no rising edge and never fire (the same trap
    // as a stop --flatten inside the entry bar). Cleared once the strategies are bound.
    private val edgeResetStrategies = LinkedHashSet<String>()

    /** The reconcile retired [leg]: remember its strategy and the venue deals that closed it, if any. */
    fun onLegRetired(
        strategyId: String,
        leg: PersistedLeg,
    ) {
        edgeResetStrategies += strategyId
        // The closing (non-IN) deals of the leg's position ticket; a venue with no deal history
        // just retires the leg with a warning.
        val ticket = leg.brokerTicket
        val closing =
            runCatching { broker.deals(leg.openedAt - 1L, clock.now()) }
                .getOrDefault(emptyList())
                .filter { d -> d.positionTicket == ticket && d.entry != "IN" }
        if (closing.isEmpty()) {
            log.warn(
                "{}: leg {} (ticket {}) closed while down; no closing deal found in venue history, " +
                    "realized PnL not booked",
                strategyId,
                leg.legId,
                ticket,
            )
            return
        }
        closes += DowntimeClose(strategyId, leg, closing)
        log.warn(
            "{}: leg {} (ticket {}) closed while down; booking its close from {} closing deal(s), venue realized {}",
            strategyId,
            leg.legId,
            ticket,
            closing.size,
            closing.realized().toPlainString(),
        )
    }

    /** Clear the rule edges of every strategy that lost a position to a downtime close. */
    fun clearRuleEdges(strategies: List<Pair<String, Strategy>>) {
        for (strategyId in edgeResetStrategies) {
            val strategy = strategies.firstOrNull { it.first == strategyId }?.second as? DslCompiledStrategy ?: continue
            runCatching { strategy.clearRuleEdges() }
                .onSuccess {
                    log.warn(
                        "{}: rule edges cleared — a position closed while the daemon was down",
                        strategyId,
                    )
                }.onFailure { t -> log.warn("could not clear rule edges for {} after downtime close", strategyId, t) }
        }
    }

    /**
     * Book every downtime close through [pipeline], once: the leg goes back into its book and the closing fill
     * runs the live fill path. Deals that carry no price cannot close a leg, so only their amount is booked.
     */
    fun bookInto(pipeline: TradingPipeline) {
        for (close in closes) {
            val leg = close.leg
            val fill = close.fill()
            if (fill == null) {
                log.warn("{}: leg {} closing deals carry no price; booking their amount", close.strategyId, leg.legId)
                pipeline.applyReconciledRealized(close.strategyId, close.deals.realized(), leg.legId, leg.symbol)
                continue
            }
            pipeline.strategyPositions.reattachLeg(close.strategyId, leg.toPositionLeg())
            pipeline.bus.publish(fill)
        }
        closes.clear()
    }
}

/** A leg that closed while the daemon was down, with the venue deals that closed it. */
private class DowntimeClose(
    val strategyId: String,
    val leg: PersistedLeg,
    val deals: List<BrokerDeal>,
) {
    /** The fill closing [leg] at the venue's (volume-weighted) price, time and reason; null when no deal is priced. */
    fun fill(): BrokerEvent.OrderFilled? {
        val priced = deals.filter { it.price.signum() > 0 && it.qty.signum() > 0 }
        if (priced.isEmpty()) return null
        val price =
            priced.singleOrNull()?.price
                ?: priced
                    .fold(BigDecimal.ZERO) { total, d -> total + d.price.multiply(d.qty) }
                    .divide(priced.fold(BigDecimal.ZERO) { total, d -> total + d.qty }, Money.CONTEXT)
        val closedAt = deals.maxOf { it.ts }
        return BrokerEvent.OrderFilled(
            clientOrderId = DOWNTIME_CLOSE_ID_PREFIX + leg.legId,
            brokerOrderId = leg.brokerTicket,
            symbol = leg.symbol,
            side = if (leg.side == Side.BUY) Side.SELL else Side.BUY,
            price = price,
            quantity = leg.quantity,
            strategyId = strategyId,
            timestamp = closedAt,
            updatesOrderExecution = false,
            // Venue costs are positive when charged; a deal states a charge as a negative amount.
            venueCosts = deals.fold(BigDecimal.ZERO) { sum, d -> sum + d.charges() }.negate(),
            exitReason = deals.map { exitReasonOf(it.closeReason) }.toSet().singleOrNull() ?: ExitReason.CLOSE,
            venueTime = closedAt,
        )
    }
}

/** What the venue realized on [this] deals: profit, commission, swap and fee, in account currency. */
private fun List<BrokerDeal>.realized(): BigDecimal = fold(BigDecimal.ZERO) { acc, d -> acc + d.profit + d.charges() }

/** Commission, swap and fee as the venue states them (a charge is negative). */
private fun BrokerDeal.charges(): BigDecimal = commission + swap + (fee ?: BigDecimal.ZERO)

private fun exitReasonOf(reason: CloseReason?): ExitReason =
    when (reason) {
        CloseReason.STOP -> ExitReason.STOP
        CloseReason.TAKE_PROFIT -> ExitReason.TAKE_PROFIT
        CloseReason.STOP_OUT, CloseReason.LIQUIDATION -> ExitReason.LIQUIDATION
        CloseReason.EXPIRY -> ExitReason.EXPIRY
        else -> ExitReason.CLOSE
    }
