package com.qkt.app.order

import com.qkt.app.StackTracker
import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TriggerType
import com.qkt.execution.withCloseTicket
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Turns a triggered engine-held order into the order the venue executes: a stop becomes a
 * market (closing its ticket when it protects one), a stop-limit a limit, an if-touched a market
 * or limit, a trailing stop-limit a limit offset from its level. A risk halt can still refuse it
 * at this last moment.
 */
internal class TriggerFiring(
    private val book: OrderBook,
    private val stacks: StackTracker,
    private val stops: ManagedStopBook,
    private val closeTickets: EngineHeldCloseTickets,
    private val broker: Broker,
    private val clock: Clock,
    private val ops: OrderOps,
    private val closeTicket: (OrderRequest) -> String?,
    private val engineHeldSubmissionBlockReason: (OrderRequest) -> String?,
) {
    private val log = LoggerFactory.getLogger(TriggerFiring::class.java)

    /** Sends the triggered engine-held order [managed] to the venue as the order it becomes. */
    fun fire(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        // The caller's triggered list is a snapshot. An earlier synchronous fill can cancel this order
        // before its turn in the loop; terminal-state protection rejects the state transition,
        // but without this guard the stale snapshot would still be submitted to the broker.
        if (book[managed.id]?.state != OrderState.PENDING) return
        val stackOwner = stacks.stackOwning(managed.id)
        if (stackOwner != null) {
            val layerIdx = managed.id.substringAfterLast("-l").toIntOrNull() ?: 0
            log.info(
                "stack fire stack_id={} strategy_id={} layer={} qty={} trigger_price={}",
                stackOwner,
                managed.request.strategyId,
                layerIdx,
                managed.request.quantity,
                tickPrice,
            )
        }
        val internal: OrderRequest =
            when (val req = managed.request) {
                is OrderRequest.Stop -> {
                    val ticket = closeTickets.ticketFor(req.id)
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        closesTicket = ticket,
                        legIntent = req.legIntent.withCloseTicket(ticket),
                    )
                }
                is OrderRequest.StopLimit ->
                    OrderRequest.Limit(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        limitPrice = req.limitPrice,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = req.legIntent,
                    )
                is OrderRequest.IfTouched ->
                    if (req.onTrigger == TriggerType.MARKET) {
                        OrderRequest.Market(
                            id = req.id,
                            symbol = req.symbol,
                            side = req.side,
                            quantity = req.quantity,
                            timeInForce = req.timeInForce,
                            timestamp = clock.now(),
                            strategyId = req.strategyId,
                            closesTicket = req.closesTicket,
                            partialClose = req.partialClose,
                            legIntent = req.legIntent,
                        )
                    } else {
                        OrderRequest.Limit(
                            id = req.id,
                            symbol = req.symbol,
                            side = req.side,
                            quantity = req.quantity,
                            limitPrice = req.limitPrice!!,
                            timeInForce = req.timeInForce,
                            timestamp = clock.now(),
                            strategyId = req.strategyId,
                            legIntent = req.legIntent,
                        )
                    }
                is OrderRequest.TrailingStop ->
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = req.legIntent,
                    )
                is OrderRequest.ArmedTrailingStop -> {
                    // Close the exact venue position by ticket when this exit belongs to an
                    // independent leg (hedging) — otherwise a plain market opens a counter.
                    val ticket = closeTicket(req)
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        closesTicket = ticket,
                        legIntent = req.legIntent.withCloseTicket(ticket),
                    )
                }
                is OrderRequest.SteppedStop, is OrderRequest.TimeTighteningStop -> {
                    val ticket = closeTicket(req)
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        closesTicket = ticket,
                        legIntent = req.legIntent.withCloseTicket(ticket),
                    )
                }
                is OrderRequest.TrailingStopLimit -> {
                    val level = stops.trailLevel(managed) ?: error("TrailingStopLimit level missing for ${managed.id}")
                    val limitPrice =
                        if (req.side == Side.SELL) level - req.limitOffset else level + req.limitOffset
                    OrderRequest.Limit(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        limitPrice = limitPrice.setScale(Money.SCALE, Money.ROUNDING),
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = req.legIntent,
                    )
                }
                else -> error("Not a Tier 2 fallback type: ${req::class.simpleName}")
            }
        val blockReason = engineHeldSubmissionBlockReason(internal)
        if (blockReason != null) {
            ops.rejectEngineHeld(internal, blockReason)
            return
        }
        closeTickets.remove(managed.id)
        ops.update(managed.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        ops.persistSubmissionIntent(internal.strategyId)
        broker.submit(internal)
    }
}
