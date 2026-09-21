package com.qkt.connector.mt5

import com.qkt.broker.SubmitAck
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Moves the stop-loss / take-profit of an open position, and tells the position poller which
 * protection changes were the engine's own. E.g. trailing ticket 3258722177's stop to 2398.50:
 * the new level is recorded as expected BEFORE the request goes out, so when the poller sees the
 * venue's stop move to 2398.50 it is not reported as an out-of-band change. A level inside the
 * symbol's freeze distance is refused locally without a gateway round-trip.
 */
internal class MT5PositionModify(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val priceTracker: MarketPriceProvider?,
    private val mt5Symbol: MT5Symbol,
    private val books: MT5BrokerState,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun modifyPosition(
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): SubmitAck {
        val t = ticket.toLongOrNull() ?: return positionModifyAck(ticket, false, "modifyPosition: bad ticket $ticket")
        positionModifyPreflightRejection(ticket, t, sl, tp)?.let { return it }
        // Register before the venue request: the position poller can observe the
        // accepted protection change before the synchronous response is returned.
        books.expectedProtectionByTicket[t] = MT5PositionProtection(sl, tp)
        val response =
            runCatching { client.modifyPosition(t, sl, tp) }
                .getOrElse { ex ->
                    books.expectedProtectionByTicket.remove(t)
                    return positionModifyAck(ticket, false, ex.message)
                }
        return handlePositionModifyResult(ticket, t, sl, tp, response)
    }

    fun modifyPositionAsync(
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
        onResult: (SubmitAck) -> Unit,
    ) {
        val t = ticket.toLongOrNull()
        if (t == null) {
            onResult(positionModifyAck(ticket, false, "modifyPosition: bad ticket $ticket"))
            return
        }
        positionModifyPreflightRejection(ticket, t, sl, tp)?.let {
            onResult(it)
            return
        }
        // The poller runs independently of this callback, so publish the expected
        // protection before sending the request to avoid a false out-of-band event.
        books.expectedProtectionByTicket[t] = MT5PositionProtection(sl, tp)
        runCatching {
            client.modifyPositionAsync(t, sl, tp) { response ->
                onResult(handlePositionModifyResult(ticket, t, sl, tp, response))
            }
        }.onFailure { error ->
            books.expectedProtectionByTicket.remove(t)
            onResult(positionModifyAck(ticket, false, error.message))
        }
    }

    private fun positionModifyPreflightRejection(
        ticket: String,
        t: Long,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): SubmitAck? {
        val qktSymbol = books.positionBook.symbol(t)
        if (qktSymbol != null) {
            val brokerSymbol = mt5Symbol.toBroker(qktSymbol.substringAfter(':'))
            val info = books.symbolMeta[brokerSymbol]
            val current = priceTracker?.lastPrice(qktSymbol)
            if (info != null && current != null && info.tradeFreezeLevel > 0) {
                val minDistance = info.point.multiply(BigDecimal(info.tradeFreezeLevel))
                val blocked =
                    listOfNotNull(sl, tp).firstOrNull { level ->
                        (level - current).abs() < minDistance
                    }
                if (blocked != null) {
                    return positionModifyAck(
                        ticket,
                        accepted = false,
                        reason =
                            "modify inside tradeFreezeLevel for $qktSymbol: " +
                                "level=$blocked current=$current minDistance=$minDistance",
                    )
                }
            }
        }
        return null
    }

    private fun handlePositionModifyResult(
        ticket: String,
        t: Long,
        sl: BigDecimal?,
        tp: BigDecimal?,
        response: MT5OrderResponse,
    ): SubmitAck {
        val ok = isOrderSuccessful(response.result.retcode)
        if (!ok) {
            books.expectedProtectionByTicket.remove(t)
            log.warn(
                "MT5Broker {} modifyPosition({}) rejected: {}",
                profile.name,
                ticket,
                response.errorMessage ?: response.result.retcode,
            )
        }
        if (ok) {
            books.positionBook.updateMeta(t) { meta ->
                val current = meta.protection
                meta.copy(
                    protection =
                        MT5PositionProtection(
                            stopLoss = sl ?: current?.stopLoss,
                            takeProfit = tp ?: current?.takeProfit,
                        ),
                )
            }
        }
        return positionModifyAck(ticket, ok, if (ok) null else response.errorMessage)
    }

    private fun positionModifyAck(
        ticket: String,
        accepted: Boolean,
        reason: String? = null,
    ): SubmitAck = SubmitAck(clientOrderId = ticket, brokerOrderId = ticket, accepted = accepted, rejectReason = reason)

    fun isExpectedProtectionChange(event: BrokerEvent.PositionProtectionChanged): Boolean {
        val ticket = event.ticket.toLongOrNull() ?: return false
        val expected = books.expectedProtectionByTicket[ticket] ?: return false
        val stopChanged = event.oldStopLoss.compareTo(event.newStopLoss) != 0
        val takeProfitChanged = event.oldTakeProfit.compareTo(event.newTakeProfit) != 0
        // Venue-scale comparison: the gateway reports SL/TP quantized to the symbol's
        // digits, while the engine registered its full-precision request (#1063).
        val stopMatches =
            !stopChanged || ProtectionExpectation.matchesVenue(expected.stopLoss, event.newStopLoss)
        val takeProfitMatches =
            !takeProfitChanged || ProtectionExpectation.matchesVenue(expected.takeProfit, event.newTakeProfit)
        if (!stopMatches || !takeProfitMatches) return false
        books.expectedProtectionByTicket.remove(ticket, expected)
        return true
    }
}
