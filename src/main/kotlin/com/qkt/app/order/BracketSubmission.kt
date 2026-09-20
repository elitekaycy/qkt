package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.exitLegIntent
import com.qkt.execution.withStrategyId
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal

/**
 * How a bracket reaches the venue, chosen by what the venue can hold:
 * - attached: the venue holds SL/TP on the position (and can modify it), so the bracket ships
 *   keyed under its entry id and the engine runs any managed stop on top;
 * - native: the venue attaches a fixed SL/TP to the order and nothing needs trailing;
 * - decomposed: everything else (backtest, restricted venues) becomes an OTO whose child is an
 *   engine-watched exit OCO.
 */
internal class BracketSubmission(
    private val broker: Broker,
    private val priceProvider: MarketPriceProvider,
    private val exits: BracketExits,
    private val risk: BracketRiskRecorder,
    private val brackets: BracketBook,
    private val children: PendingChildBook,
    private val exposure: PendingExposureBook,
    private val book: OrderBook,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    /** Routes the already-tracked [request] by the venue's capabilities. */
    fun submit(request: OrderRequest.Bracket): SubmitAck {
        risk.recordAtSubmit(request, priceProvider.lastPrice(request.symbol) ?: BigDecimal.ZERO)
        val caps = broker.capabilitiesFor(request.symbol)
        val isEngineManagedStop =
            request.stopLoss is StopLossSpec.ArmedTrail ||
                request.stopLoss is StopLossSpec.SteppedStop ||
                request.stopLoss is StopLossSpec.TimeTighten
        val needsFillAnchor =
            (request.stopLossAst != null && request.stopLossAst !is ChildAt) ||
                (request.takeProfitAst != null && request.takeProfitAst !is ChildAt)
        val canAttach =
            OrderTypeCapability.BRACKET in caps && OrderTypeCapability.POSITION_MODIFY in caps
        return when {
            // Venue that both attaches SL/TP to an order and can modify an open position's SL/TP:
            // ship the bracket keyed under its entry id so the venue holds the SL/TP on the
            // position (closing that ticket on a hedging account instead of a resting exit
            // opening a counter) and the fill flows through the entry.id tracking paths. Armed
            // trail also runs the engine trail on top (fires close-by-ticket at the tightened
            // level, #278); the venue's attached stop is the offline backstop.
            canAttach -> submitAttached(request)
            // BRACKET but no position-modify, fixed SL: ship whole (venue attaches SL/TP,
            // nothing to trail).
            !isEngineManagedStop && !needsFillAnchor && OrderTypeCapability.BRACKET in caps ->
                ops.submitRegisteredToBroker(request)
            // No venue attach (backtest / restricted venue): decompose into engine-watched
            // resting exits.
            else -> submitDecomposed(request)
        }
    }

    private fun submitDecomposed(req: OrderRequest.Bracket): SubmitAck {
        val exitSide = if (req.side == Side.BUY) Side.SELL else Side.BUY
        val exit = req.exitLegIntent()
        val tp =
            OrderRequest.Limit(
                id = "${req.id}-tp",
                symbol = req.symbol,
                side = exitSide,
                quantity = req.quantity,
                limitPrice = req.takeProfit,
                timeInForce = req.timeInForce,
                timestamp = clock.now(),
                strategyId = req.strategyId,
                legIntent = exit,
            )
        // A fixed stop rests at its price. A managed stop (armed trail, stepped, time-tightening)
        // starts from the entry's intended fill and is moved by the engine on each tick. See #48.
        val sl =
            protectiveStop(
                "${req.id}-sl",
                req.stopLoss,
                req.symbol,
                exitSide,
                req.quantity,
                if (req.stopLoss is StopLossSpec.Fixed) null else exits.entryEstimate(req),
                req.timeInForce,
                clock.now(),
                req.strategyId,
                exit,
            )
        val oco =
            OrderRequest.StandaloneOCO(
                id = "${req.id}-oco",
                symbol = req.symbol,
                side = exitSide,
                quantity = req.quantity,
                leg1 = tp,
                leg2 = sl,
                timeInForce = req.timeInForce,
                timestamp = clock.now(),
                strategyId = req.strategyId,
            )
        val oto =
            OrderRequest.OTO(
                id = req.id,
                symbol = req.symbol,
                side = req.side,
                quantity = req.quantity,
                parent = req.entry.withStrategyId(req.strategyId),
                children = listOf(oco),
                timeInForce = req.timeInForce,
                timestamp = clock.now(),
                strategyId = req.strategyId,
            )
        brackets.preFill[req.entry.id] = req
        if (req.takeProfitAst != null || req.stopLossAst != null) {
            brackets.fillAnchoredFallback[req.entry.id] = req
        }
        book.evict(req.id)
        return ops.submit(oto)
    }

    // Ship keyed under the ENTRY id so the venue attaches the SL/TP to the position AND the fill
    // — with its ticket — flows through the same entry.id paths the position tracking uses (the
    // entry's leg intent, sibling-cancel, poller close attribution). A native bracket keyed under
    // its own id would fill under the bracket id and silently miss those registrations.
    private fun submitAttached(req: OrderRequest.Bracket): SubmitAck {
        val now = clock.now()
        val attached = req.copy(id = req.entry.id)
        brackets.preFill[attached.id] = req
        if (req.takeProfitAst != null || req.stopLossAst != null) {
            brackets.fillAnchoredAttached[attached.id] = req
        }
        // An armed trail is engine-managed on top of the venue's static pre-arm stop: dispatched
        // on the entry fill, it fires close-by-ticket at the tightened level. A fixed bracket has
        // no engine exit — the venue's attached SL/TP closes it outright.
        val managedStop = exits.managedStop(req, now)
        ops.update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOfNotNull(attached.id, managedStop?.id),
                lastUpdatedAt = now,
            )
        }
        ops.track(created(attached, req.id, now))
        if (managedStop != null) {
            ops.track(created(managedStop, req.id, now))
            // Arm the trail only once the position exists — dispatched on the entry's fill.
            children.hold(attached.id, listOf(managedStop))
        }
        exposure.register(attached)
        val ack = ops.submitToBroker(attached)
        return SubmitAck(req.id, req.id, accepted = ack.accepted, rejectReason = ack.rejectReason)
    }

    private fun created(
        request: OrderRequest,
        parentId: String,
        now: Long,
    ) = ManagedOrder(
        id = request.id,
        request = request,
        state = OrderState.CREATED,
        parentClientOrderId = parentId,
        createdAt = now,
        lastUpdatedAt = now,
    )
}
