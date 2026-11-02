package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Sends a market order on a thin contract ([collars]) as an immediate-or-cancel limit, [band] (a fraction) from the
 * contract's reference: its last price, which on a gateway is the venue's mark, not a book side. A stray far touch cannot
 * fill it. E.g. at 0.25 around 77,400, a SELL's limit is 58,050 and a bid of 38,937.5 does not fill it. The engine still
 * sees one market order: an attempt the venue cancels is sent again, for what is left, 1 s and then 2 s later around
 * the reference as it then stands, under a fresh gateway id ([GatewayClientIds]).
 *
 * An entry is given up after [MAX_ATTEMPTS], with a reason saying so, because missing a trade costs less than the fill
 * it avoids. So is an entry with no reference, which cannot be collared.
 *
 * An exit ([exits]) is never collared: it goes as a plain market order, with a warning. It must not get stuck, and the
 * far fill it may take is what it always risked. A collared exit would add a way to strand a position: a restart
 * between attempts knows only the first attempt's gateway id, and would tell the engine the exit was cancelled.
 *
 * A venue refusing an entry's limit itself (one whose own price limit is tighter than [band] may) gets a plain market
 * order, since such a limit also bounds that order's fill. The gateway's kill switch is final.
 */
internal class GatewayMarketCollar(
    private val band: BigDecimal,
    private val prices: MarketPriceProvider,
    private val clock: Clock,
    private val collars: (symbol: String) -> Boolean,
    private val exits: (OrderRequest.Market) -> Boolean,
    private val send: (OrderRequest) -> Unit,
    private val later: (delayMs: Long, action: () -> Unit) -> Unit,
    private val publish: (BrokerEvent) -> Unit,
) {
    private val log = LoggerFactory.getLogger(GatewayMarketCollar::class.java)
    private val working = ConcurrentHashMap<String, CollaredMarketOrder>()

    /** Why [request] may not go at all: an entry on a collared contract no reference can price; null otherwise. */
    fun refusal(request: OrderRequest): String? {
        if (request !is OrderRequest.Market || !collars(request.symbol)) return null
        if (reference(request.symbol) != null || exits(request)) return null
        return "market order on ${request.symbol} refused: no price heard to protect it with"
    }

    /** What to send first for [request]: the collared limit, tracked from here on, or [request] itself. */
    fun first(request: OrderRequest): OrderRequest {
        if (request !is OrderRequest.Market || !collars(request.symbol)) return request
        if (exits(request)) return request.also(::uncollared)
        val w = CollaredMarketOrder(request)
        val limit = limitFor(w, request.quantity) ?: return request
        w.sentAt = limit.timestamp
        working[request.id] = w
        return limit
    }

    /** The engine cancels [engineId]. True when that is settled here: the order was between attempts. */
    fun cancelled(engineId: String): Boolean {
        val w = working[engineId] ?: return false
        w.cancelling = true
        if (!w.waiting || !working.remove(engineId, w)) return false
        publish(BrokerEvent.OrderCancelled(engineId, null, "cancelled by qkt", w.request.strategyId, clock.now()))
        return true
    }

    /** The gateway refused [engineId]'s attempt with [reason]; true when this takes it from here. */
    fun refused(
        engineId: String,
        reason: String,
    ): Boolean {
        val w = working[engineId] ?: return false
        if (w.plain || reason.startsWith(KILL_SWITCH) || w.cancelling) {
            working.remove(engineId, w)
            return false
        }
        log.warn("{} refused market order {} as a limit: {}; sending it plain", w.request.symbol, engineId, reason)
        sendPlain(w)
        return true
    }

    /** An event of the attached session's orders, on its way to the engine, which hears one order throughout. */
    fun heard(event: BrokerEvent) {
        val w = (event as? BrokerEvent.OrderEvent)?.let { working[it.clientOrderId] } ?: return publish(event)
        when (event) {
            is BrokerEvent.OrderAccepted -> if (w.attempts == 1 && !w.plain) publish(event)
            is BrokerEvent.OrderPartiallyFilled -> {
                val before = w.filled
                w.filled = before + event.quantity
                publish(event.copy(cumulativeFilled = w.filled))
            }
            is BrokerEvent.OrderFilled -> publish(event).also { working.remove(event.clientOrderId, w) }
            is BrokerEvent.OrderCancelled ->
                if (w.plain || w.cancelling) {
                    publish(event).also { working.remove(event.clientOrderId, w) }
                } else {
                    attemptEnded(w)
                }
            is BrokerEvent.OrderRejected -> if (!refused(event.clientOrderId, event.reason)) publish(event)
            else -> publish(event)
        }
    }

    private fun attemptEnded(w: CollaredMarketOrder) {
        if (w.attempts < MAX_ATTEMPTS) {
            val gap = RETRY_GAP_MS * w.attempts
            w.attempts += 1
            w.waiting = true
            later(gap) { resend(w) }
            return
        }
        working.remove(w.request.id, w)
        val reason =
            "market order on ${w.request.symbol} not filled: nothing to trade within ${band.toPlainString()} of its " +
                "reference price in $MAX_ATTEMPTS attempts (${w.last})"
        val id = w.request.id
        val now = clock.now()
        if (w.filled.signum() > 0) {
            publish(BrokerEvent.OrderCancelled(id, null, reason, w.request.strategyId, now))
        } else {
            publish(BrokerEvent.OrderRejected(id, null, reason, w.request.strategyId, now))
        }
    }

    private fun resend(w: CollaredMarketOrder) {
        w.waiting = false
        if (working[w.request.id] !== w || w.cancelling) return
        val limit = limitFor(w, w.request.quantity - w.filled)
        if (limit == null) {
            working.remove(w.request.id, w)
            val reason = "market order on ${w.request.symbol} not filled: its price went away between attempts"
            return publish(BrokerEvent.OrderRejected(w.request.id, null, reason, w.request.strategyId, clock.now()))
        }
        w.sentAt = limit.timestamp
        send(limit)
    }

    private fun sendPlain(w: CollaredMarketOrder) {
        w.plain = true
        send(w.request.copy(quantity = w.request.quantity - w.filled, timestamp = nextStamp(w)))
    }

    private fun limitFor(
        w: CollaredMarketOrder,
        quantity: BigDecimal,
    ): OrderRequest.Limit? {
        val request = w.request
        val reference = reference(request.symbol) ?: return null
        val side = if (request.side == Side.BUY) BigDecimal.ONE + band else BigDecimal.ONE - band
        val price = reference.multiply(side)
        w.last = "reference ${reference.toPlainString()}, limit ${price.toPlainString()}"
        return OrderRequest.Limit(
            request.id,
            request.symbol,
            request.side,
            quantity,
            price,
            TimeInForce.IOC,
            nextStamp(w),
            request.strategyId,
        )
    }

    /** A timestamp no earlier attempt of [w] used: it is what makes the attempt's gateway id new. */
    private fun nextStamp(w: CollaredMarketOrder): Long = maxOf(clock.now(), maxOf(w.sentAt, w.request.timestamp) + 1)

    private fun reference(symbol: String): BigDecimal? = prices.lastPrice(symbol)?.takeIf { it.signum() > 0 }

    private fun uncollared(request: OrderRequest.Market) =
        log.warn("exit {} on {} goes as a plain market order and may fill at a far touch", request.id, request.symbol)

    companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_GAP_MS = 1_000L
        private const val KILL_SWITCH = "kill_switch"
    }
}
