package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.SubmitAck
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
import org.slf4j.LoggerFactory

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
) {
    private val log = LoggerFactory.getLogger(VenueSubmission::class.java)

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

    /** A local rejection for [request] when its absolute protection is already crossed, else null. */
    fun crossedProtectionRejection(request: OrderRequest.Bracket): SubmitAck? {
        // Venue-faithful stops validation (#1076): MT5 rejects an order whose absolute stop
        // is already on the wrong side of the reference price (retcode 10016 Invalid stops).
        // Refusing locally keeps every simulated tier byte-consistent with live — on a gap
        // tick the entry is never taken, instead of filling with an INVERTED protective stop
        // that fires on the next print as a guaranteed instant loss. Market entries validate
        // against the current quote; pending entries against their own trigger price. Scope
        // is deliberately the stop side only: a take profit the market has already reached is
        // an instant profit-take, not broken protection, and BY-resolved targets are anchored
        // to the signal bar rather than the submit quote. Relative (BY/trail) stops resolve
        // off the fill and cannot invert.
        val stopsReference =
            when (val entry = request.entry) {
                is OrderRequest.Limit -> entry.limitPrice
                is OrderRequest.Stop -> entry.stopPrice
                else -> priceProvider.lastPrice(request.symbol)?.takeIf { it.signum() != 0 }
            }
        val fixedSl = (request.stopLoss as? StopLossSpec.Fixed)?.price
        if (stopsReference != null && fixedSl != null) {
            val slCrossed =
                if (request.side == Side.BUY) fixedSl >= stopsReference else fixedSl <= stopsReference
            if (slCrossed) {
                return rejectCrossedProtection(request, stopsReference, fixedSl, "stop loss")
            }
        }
        // The target needs the same check, but ONLY for an absolute `AT` level. A BY/PCT/RR
        // target is re-anchored off the fill by resolveBracketAtFill and cannot invert, and
        // its pre-fill value is a placeholder — checking that would reject healthy brackets.
        // An inverted absolute target is not a free profit-take: measured on the gold RSI-fade
        // tape, a BUY filled at 1320.700 carrying TAKE_PROFIT 1320.019 closed instantly for a
        // 0.68/oz LOSS. MT5 rejects it under the same retcode 10016 the stop side gets.
        if (stopsReference != null && request.takeProfitAst is ChildAt) {
            val tp = request.takeProfit
            val tpCrossed =
                if (request.side == Side.BUY) tp <= stopsReference else tp >= stopsReference
            if (tpCrossed) {
                return rejectCrossedProtection(request, stopsReference, tp, "take profit")
            }
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
    ): SubmitAck {
        val reason =
            "invalid stops: $leg $level already crossed for ${request.side} at reference $reference " +
                "(venue would reject, retcode 10016)"
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
