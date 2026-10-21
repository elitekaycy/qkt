package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.connectivity.DirectPosition
import com.qkt.connectivity.DirectResult
import java.math.BigDecimal
import java.math.MathContext

/**
 * What became of a `qkt bot` order on a VGP gateway. An order is read back under the id the gateway
 * answered with, until the venue decides it ([settle]); a close is sent again, same body, while the
 * gateway does not answer, and when no answer ever comes, or the resend finds nothing left to close,
 * the venue decides it: the fills that closed the position since the request, and what is still held
 * ([close]). Live, 2026-10-21: a close the venue filled was reported as a lost order, because the paper
 * gateway named the closing order itself (`close.mvigikbv.1`) and the id that was sent was looked up.
 */
internal class GatewayDirectOutcome(
    private val name: String,
    private val client: GatewayClient,
    private val clock: Clock,
    private val pollTimeoutMs: Long,
    private val pollMs: Long,
) {
    /** Closes [volume] (all when null) of [position] under [clientId] and reports what the venue did. */
    fun close(
        position: DirectPosition,
        volume: BigDecimal?,
        clientId: String,
    ): DirectResult {
        val since = clock.now()
        val deadline = since + pollTimeoutMs
        var unanswered: String? = null
        while (true) {
            try {
                return settle(clientId, client.closePosition(position.symbol, volume, clientId))
            } catch (e: GatewayUnavailableException) {
                unanswered = e.message
            } catch (e: GatewayException) {
                // Nothing left to close after an unanswered attempt: that attempt closed it.
                if (unanswered != null && e.code == "no_position") break
                if (e.status < SERVER_ERROR) throw e
                unanswered = e.message
            }
            if (clock.now() >= deadline) break
            Thread.sleep(pollMs)
        }
        return fromVenue(position, since, unanswered)
    }

    /**
     * Reads order [id] ([first]: as the gateway answered the request; its id is the one the gateway
     * keeps) until the venue has decided it: filled, cancelled or rejected, or working for an order that
     * rests. A market order still unsettled at the deadline is reported as it stands.
     */
    fun settle(
        id: String,
        first: WireOrder? = null,
    ): DirectResult {
        val deadline = clock.now() + pollTimeoutMs
        var order = first ?: client.order(id) ?: error("gateway $name lost order $id")
        while (!settled(order) && clock.now() < deadline) {
            Thread.sleep(pollMs)
            order = client.order(order.clientOrderId) ?: order
        }
        val fills =
            if (BigDecimal(order.filledQuantity).signum() > 0) client.dealsOf(order.clientOrderId) else emptyList()
        val filled = fills.fold(BigDecimal.ZERO) { q, f -> q + BigDecimal(f.quantity) }
        val price = if (filled.signum() > 0) average(fills, filled) else order.limitPrice?.let(::BigDecimal)
        val ok = order.status == "working" || order.status == "filled" || filled.signum() > 0
        val error =
            when {
                ok -> null
                order.status == "rejected" -> order.rejectReason ?: "rejected by the venue"
                else -> "order ${order.status} at the venue with nothing filled"
            }
        val deal = fills.firstOrNull()?.fillId.orEmpty()
        return DirectResult(ok, 0, order.clientOrderId, deal, price ?: BigDecimal.ZERO, error, order.status, filled)
    }

    private fun fromVenue(
        position: DirectPosition,
        sinceMs: Long,
        unanswered: String?,
    ): DirectResult {
        val closing = if (position.side == "BUY") "sell" else "buy"
        val fills =
            client
                .deals(sinceMs - CLOCK_SLACK_MS, clock.now() + CLOCK_SLACK_MS)
                .filter { it.symbol == position.symbol && it.side == closing }
        val filled = fills.fold(BigDecimal.ZERO) { q, f -> q + BigDecimal(f.quantity) }
        if (filled.signum() > 0) {
            val first = fills.first()
            return DirectResult(
                true,
                0,
                first.clientOrderId,
                first.fillId,
                average(fills, filled),
                null,
                "filled",
                filled,
            )
        }
        val held =
            client
                .positions()
                .positions
                .firstOrNull { it.symbol == position.symbol }
                ?.quantity ?: "0"
        val why = "close unanswered (${unanswered ?: "no answer"}); the venue holds $held and shows no closing fill"
        return DirectResult(false, 0, "", "", BigDecimal.ZERO, why, "unknown", BigDecimal.ZERO)
    }

    private fun average(
        fills: List<WireFill>,
        filled: BigDecimal,
    ): BigDecimal =
        fills
            .fold(BigDecimal.ZERO) { v, f -> v + BigDecimal(f.quantity) * BigDecimal(f.price) }
            .divide(filled, MathContext.DECIMAL64)

    private fun settled(order: WireOrder): Boolean =
        order.status in setOf("filled", "cancelled", "rejected") ||
            (order.status == "working" && order.type != "market")

    private companion object {
        const val SERVER_ERROR = 500

        /** How far a gateway's clock may stand from ours when the close's fills are looked up by time. */
        const val CLOCK_SLACK_MS = 5_000L
    }
}
