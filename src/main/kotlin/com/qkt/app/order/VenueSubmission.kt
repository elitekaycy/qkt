package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.SubmitAck
import com.qkt.broker.validatesSubmittedProtection
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.isTerminal
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * The last step before the venue, and the local refusals that stand in for venue rejections.
 * An order the venue would certainly reject (an expired GTD deadline, a bracket whose absolute
 * protection is already crossed) is refused here with a precise reason instead of round-tripping
 * into an opaque venue error — and, in a simulated tier, instead of filling where live never
 * would. Venue-bound intent is persisted synchronously before the submit.
 */
internal class VenueSubmission(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val broker: Broker,
    private val bus: EventBus,
    private val priceProvider: MarketPriceProvider,
    private val clock: Clock,
    private val ops: OrderOps,
    private val log: Logger,
) {
    /** Sends [request] to the venue; a refusal marks it rejected and releases its exposure. */
    fun submitToBroker(request: OrderRequest): SubmitAck {
        val expiresAt = request.expiresAt
        if (expiresAt != null && expiresAt <= clock.now()) return rejectExpiredBeforeSubmit(request, expiresAt)
        ops.update(request.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        ops.persistSubmissionIntent(request.strategyId)
        val ack = broker.submit(request)
        if (!ack.accepted && book[request.id]?.state?.isTerminal != true) {
            ops.update(request.id) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
            exposure.remove(request.id)
        }
        return ack
    }

    /** Sends [request] to the venue after moving its exposure entry onto it. */
    fun submitRegisteredToBroker(request: OrderRequest): SubmitAck {
        val entry = exposureEntryRequest(request)
        val existingGroup = if (entry.id == request.id) null else exposure.remove(entry.id)
        exposure.register(request, existingGroup)
        return submitToBroker(request)
    }

    /** Refuses an engine-held [request] before it reaches the venue, e.g. after a risk halt. */
    fun rejectEngineHeld(
        request: OrderRequest,
        reason: String,
    ) {
        ops.update(request.id) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
        exposure.remove(request.id)
        log.warn("engine-held order {} blocked before broker submission: {}", request.id, reason)
        bus.publish(RiskRejectedEvent(request, reason, timestamp = clock.now()))
    }

    /** A local rejection for [request] when the venue would refuse its submitted protection, else null. */
    fun crossedProtectionRejection(request: OrderRequest.Bracket): SubmitAck? {
        // Venue-faithful stops validation (#1076), mirroring the MT5 gateway's validate_sl_tp:
        // the reference is the price the entry executes at — the ask for a market BUY, the bid
        // for a market SELL, the trigger price for a pending entry — and a BUY stop must sit
        // strictly below it, a BUY target strictly above it (SELL mirrored). Refusing locally
        // keeps every simulated tier consistent with live: on a gap tick the entry is never
        // taken instead of filling with inverted protection that fires on the next print.
        // An absolute AT target is judged on every venue. A BY/PCT/RR target's pre-fill placeholder
        // is judged only where the venue receives it: an attach venue (and its simulator) ships it
        // with the entry and validates it exactly as an absolute level, so a placeholder inside the
        // spread is refused live (the scale-burst stack trace); a venue that splits the bracket
        // never sees it, and the target re-anchors on the fill.
        val stopsReference =
            when (val entry = request.entry) {
                is OrderRequest.Limit -> entry.limitPrice
                is OrderRequest.Stop -> entry.stopPrice
                else -> priceProvider.executionPrice(request.symbol, request.side)?.takeIf { it.signum() != 0 }
            } ?: return null
        val buy = request.side == Side.BUY
        (request.stopLoss as? StopLossSpec.Fixed)?.price?.let { sl ->
            if (if (buy) sl >= stopsReference else sl <= stopsReference) {
                val venueText = "For ${request.side} orders, SL must be ${if (buy) "below" else "above"} entry price"
                return rejectCrossedProtection(request, stopsReference, sl, "stop loss", venueText)
            }
        }
        val tp = request.takeProfit
        val targetOnWire = request.takeProfitAst is ChildAt || broker.validatesSubmittedProtection(request.symbol)
        if (targetOnWire && (if (buy) tp <= stopsReference else tp >= stopsReference)) {
            val venueText = "For ${request.side} orders, TP must be ${if (buy) "above" else "below"} entry price"
            return rejectCrossedProtection(request, stopsReference, tp, "take profit", venueText)
        }
        return null
    }

    /**
     * A bracket whose absolute protection is already crossed at submit can only round-trip
     * into a venue rejection (MT5 retcode 10016 Invalid stops) — or, in a simulated tier,
     * fill and instantly stop out, which live would never do (#1076). Refuse locally with
     * the levels in the reason.
     */
    private fun rejectCrossedProtection(
        request: OrderRequest.Bracket,
        reference: BigDecimal,
        level: BigDecimal,
        leg: String,
        venueText: String,
    ): SubmitAck {
        val reason =
            "invalid stops: $venueText — $leg $level already crossed for ${request.side} at reference " +
                "$reference (venue would reject, retcode 10016)"
        log.warn("order {} {} — rejected locally, not sent to broker", request.id, reason)
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }

    // A GTD deadline at or past the current clock can only round-trip into a venue
    // rejection (MT5 retcode 10022), so it is refused here with both clocks in the
    // reason — a bar-clock vs wall-clock divergence (#811) is visible at its first
    // occurrence instead of masquerading as a venue error.

    /**
     * A bracket whose absolute protection is already crossed at submit can only round-trip
     * into a venue rejection (MT5 retcode 10016 Invalid stops) — or, in a simulated tier,
     * fill and instantly stop out, which live would never do (#1076). Refuse locally with
     * the levels in the reason.
     */
    private fun rejectExpiredBeforeSubmit(
        request: OrderRequest,
        expiresAt: Long,
    ): SubmitAck {
        val now = clock.now()
        val reason = "expired before submit: expiresAt=$expiresAt now=$now"
        log.warn("order {} {} — rejected locally, not sent to broker", request.id, reason)
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = now,
            ),
        )
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }
}
