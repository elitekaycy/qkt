package com.qkt.connector.gateway

import com.qkt.candles.TimeWindow
import com.qkt.connectivity.DirectPosition
import com.qkt.connectivity.DirectResult
import com.qkt.connectivity.DirectVenue
import com.qkt.connectivity.VenueFacts
import com.qkt.execution.OrderRequest
import com.qkt.instrument.TickSteps
import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/**
 * One VGP v1 account spoken to directly for a `qkt bot` one-shot command. Orders map through
 * [GatewayOrders] (the engine's own mapping, so a one-shot price lands on the same tick grid), are sent
 * with an idempotent `client_order_id`, and are then read back until the venue says what became of
 * them. A position here carries no stop or target (VGP v1 has none), so a protected entry is refused
 * and pointed at a session, where the engine holds the bracket.
 */
class GatewayDirectVenue(
    private val name: String,
    prefix: String,
    private val client: GatewayClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val pollTimeoutMs: Long = 10_000,
    private val pollMs: Long = 200,
    private val clock: com.qkt.common.Clock = com.qkt.common.SystemClock(),
) : DirectVenue {
    private val symbols = GatewaySymbols(prefix)
    private val reads = GatewayDirectReads(name, client)
    private val listing: Map<String, WireInstrument> by lazy {
        client.instruments().also(symbols::updateListing).associateBy { it.code }
    }

    override fun venueSymbol(qktSymbol: String): String =
        listing.let { symbols.venue(qktSymbol) } ?: error("gateway $name does not list $qktSymbol")

    override fun facts(qktSymbol: String): VenueFacts {
        val instrument = listing.getValue(venueSymbol(qktSymbol))
        val quote = gatewayQuoteOnce(baseUrl, apiKey, instrument.code)
        val bid = quote.bid?.let(::BigDecimal) ?: error("gateway quote for $qktSymbol has no bid")
        val ask = quote.ask?.let(::BigDecimal) ?: error("gateway quote for $qktSymbol has no ask")
        val account = client.account()
        val tick = BigDecimal(instrument.tickSize)
        return VenueFacts(
            bid = bid,
            ask = ask,
            equity = BigDecimal(account.equity),
            balance = BigDecimal(account.balance),
            accountCurrency = account.currency,
            contractSize = BigDecimal(instrument.contractSize),
            quoteCurrency = instrument.currency,
            volumeMin = BigDecimal(instrument.volumeMin),
            volumeStep = BigDecimal(instrument.volumeStep),
            volumeMax = null,
            digits = tick.stripTrailingZeros().scale().coerceAtLeast(0),
            tickSize = tick,
            reference = quote.mark?.let(::BigDecimal)?.takeIf { it.signum() > 0 },
        )
    }

    override fun place(
        order: OrderRequest,
        stopLoss: BigDecimal?,
        takeProfit: BigDecimal?,
        clientId: String,
    ): DirectResult {
        require(stopLoss == null && takeProfit == null) {
            "a gateway venue holds no stop for a position; run a session (`qkt bot session start`) and the " +
                "engine holds the bracket"
        }
        val code = venueSymbol(order.symbol)
        val grid = listing[code]?.tickSize?.let { TickSteps(BigDecimal(it)) }
        val body =
            when (val mapping = GatewayOrders.map(order, code, NoPositions, BigDecimal.ZERO, grid)) {
                is GatewayOrderMapping.Unsupported -> throw IllegalArgumentException(mapping.reason)
                is GatewayOrderMapping.Send -> mapping.body
            }
        return when (val sent = client.submit(body)) {
            is GatewaySubmit.Refused ->
                DirectResult(
                    false,
                    0,
                    body.clientOrderId,
                    "",
                    BigDecimal.ZERO,
                    "${sent.code}: ${sent.message}",
                    "rejected",
                )
            is GatewaySubmit.Placed -> settle(body.clientOrderId)
        }
    }

    override fun close(
        position: DirectPosition,
        volume: BigDecimal?,
        clientId: String,
    ): DirectResult {
        client.closePosition(position.symbol, volume, clientId)
        return settle(clientId)
    }

    override fun modify(
        position: DirectPosition,
        stopLoss: BigDecimal?,
        takeProfit: BigDecimal?,
    ): DirectResult =
        throw IllegalArgumentException(
            "a gateway venue holds no stop or target on a position; run a session and the engine holds them",
        )

    override fun cancel(ticket: String): DirectResult {
        val order = client.cancel(ticket)
        val ok = order.status == "cancelled"
        val error = if (ok) null else "order $ticket is ${order.status}; nothing to cancel"
        return DirectResult(ok, 0, ticket, "", BigDecimal.ZERO, error, order.status, BigDecimal(order.filledQuantity))
    }

    override fun account() = reads.account()

    override fun positions() = reads.positions()

    override fun pendingOrders() = reads.pendingOrders()

    override fun deals(
        fromMs: Long,
        toMs: Long,
    ) = reads.deals(fromMs, toMs)

    override fun tick(qktSymbol: String) = reads.tick(gatewayQuoteOnce(baseUrl, apiKey, venueSymbol(qktSymbol)))

    override fun bars(
        qktSymbol: String,
        window: TimeWindow,
        count: Int,
        nowMs: Long,
    ): List<Candle> = reads.bars(qktSymbol, venueSymbol(qktSymbol), window, count, nowMs)

    /**
     * Reads order [id] until the venue has decided it: filled, cancelled or rejected, or working for an
     * order that rests. A market order still unsettled at the deadline is reported as it stands.
     */
    private fun settle(id: String): DirectResult {
        val deadline = clock.now() + pollTimeoutMs
        var order = client.order(id) ?: error("gateway $name lost order $id")
        while (!settled(order) && clock.now() < deadline) {
            Thread.sleep(pollMs)
            order = client.order(id) ?: order
        }
        val fills = if (BigDecimal(order.filledQuantity).signum() > 0) client.dealsOf(id) else emptyList()
        val filled = fills.fold(BigDecimal.ZERO) { q, f -> q + BigDecimal(f.quantity) }
        val price =
            if (filled.signum() > 0) {
                fills
                    .fold(
                        BigDecimal.ZERO,
                    ) { v, f ->
                        v + BigDecimal(
                            f.quantity,
                        ) * BigDecimal(f.price)
                    }.divide(filled, java.math.MathContext.DECIMAL64)
            } else {
                order.limitPrice?.let(::BigDecimal) ?: BigDecimal.ZERO
            }
        val ok = order.status == "working" || order.status == "filled" || filled.signum() > 0
        val error =
            when {
                ok -> null
                order.status == "rejected" -> order.rejectReason ?: "rejected by the venue"
                else -> "order ${order.status} at the venue with nothing filled"
            }
        return DirectResult(ok, 0, id, fills.firstOrNull()?.fillId.orEmpty(), price, error, order.status, filled)
    }

    private fun settled(order: WireOrder): Boolean =
        order.status in setOf("filled", "cancelled", "rejected") ||
            (order.status == "working" && order.type != "market")

    private object NoPositions : PositionProvider {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions(): Map<String, Position> = emptyMap()
    }
}
