package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Books the fill of an engine close the venue acknowledged as done. A priced ack books at its own
 * price with no extra venue read beyond the one for costs. Async-fill venues (dealer desks) answer
 * DONE with price 0.0 and deal 0 before the fill exists, so an unpriced ack books at the closing
 * deal's price, read again up to [CLOSE_PRICE_LOOKUPS] times on the unknown-resolve executor; if
 * the deal never shows, at the market price for the closing side, logged as provisional. A zero
 * price is never booked: with no price at all the close stays pending and is read again on the
 * unknown-outcome cadence. E.g. ack price 0.0 / deal 0 closing ticket 42, deal at 2401.35 on the
 * second read: the fill books 2401.35 rather than realizing the whole entry notional.
 */
internal class MT5AcknowledgedCloseFill(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val engineCloses: MT5EngineCloseMarkers,
    private val unknownResolver: MT5UnknownResolveScheduler,
    private val closeTruth: MT5CloseVenueTruth,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /** A close the venue acknowledged as done: the request, the ticket, the ack, and the filled size. */
    data class AcknowledgedClose(
        val request: OrderRequest.Market,
        val ticket: Long,
        val brokerSymbol: String,
        val ack: MT5OrderResult,
        val filledQuantity: BigDecimal,
        val closeStartedAtMs: Long,
        val positionClosed: Boolean,
    )

    /**
     * Book [close]. Runs on the OkHttp callback thread; retries of an unpriced ack are scheduled on
     * the unknown-resolve executor, never slept on the caller. Until the fill is booked the engine
     * close marker stays pending, so the position poller neither publishes nor adopts the close.
     */
    fun book(close: AcknowledgedClose) {
        val ackPrice = close.ack.price.takeIf { it.signum() > 0 }
        if (ackPrice == null) {
            resolveUnpriced(close, attempt = 1, bookedCosts = BigDecimal.ZERO)
            return
        }
        publish(close, ackPrice, lookup(close).costs)
    }

    private fun resolveUnpriced(
        close: AcknowledgedClose,
        attempt: Int,
        bookedCosts: BigDecimal,
    ) {
        val truth = lookup(close)
        val costs = bookedCosts.add(truth.costs)
        truth.closingDealPrice?.let { return publish(close, it, costs) }
        if (attempt < CLOSE_PRICE_LOOKUPS) {
            unknownResolver.scheduleUnknownResolution(closeTruth.retryDelayMs(attempt + 1)) {
                resolveUnpriced(close, attempt + 1, costs)
            }
            return
        }
        val market = closeTruth.marketClosePrice(close.request.symbol, close.brokerSymbol, close.request.side)
        if (market != null) {
            log.error(
                "MT5Broker {} close {} ticket {} acknowledged with price {} and no closing deal after {} reads; " +
                    "booking at market {} — realized PnL is PROVISIONAL and must be reconciled from venue deals",
                profile.name,
                close.request.id,
                close.ticket,
                close.ack.price.toPlainString(),
                CLOSE_PRICE_LOOKUPS,
                market.toPlainString(),
            )
            publish(close, market, costs)
            return
        }
        log.error(
            "MT5Broker {} close {} ticket {} has no closing deal and no market price; fill NOT booked, " +
                "reading the venue again on the unknown-outcome cadence",
            profile.name,
            close.request.id,
            close.ticket,
        )
        unknownResolver.scheduleUnknownResolution { resolveUnpriced(close, attempt, costs) }
    }

    private fun lookup(close: AcknowledgedClose): MT5CloseVenueTruth.CloseVenueTruth =
        try {
            closeTruth.venueTruthForPositionClose(close.ticket, close.ack, close.closeStartedAtMs, close.positionClosed)
        } catch (failure: RuntimeException) {
            log.warn("MT5Broker {} close {} deal-history read failed", profile.name, close.request.id, failure)
            MT5CloseVenueTruth.CloseVenueTruth(costs = BigDecimal.ZERO, closingDealPrice = null)
        }

    private fun publish(
        close: AcknowledgedClose,
        price: BigDecimal,
        venueCosts: BigDecimal,
    ) {
        engineCloses.confirmEngineClose(close.ticket)
        if (close.positionClosed) {
            books.positionBook.forgetAttribution(close.ticket)
            books.positionBook.forgetOpenedAt(close.ticket)
        }
        val request = close.request
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = close.ticket.toString(),
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = close.ticket.toString(),
                symbol = request.symbol,
                side = request.side,
                price = price,
                quantity = close.filledQuantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
                venueCosts = venueCosts,
                exitReason = ExitReason.CLOSE,
            ),
        )
    }

    private companion object {
        /** Deal-history reads for an unpriced close ack before falling back to the market price. */
        const val CLOSE_PRICE_LOOKUPS: Int = 4
    }
}
