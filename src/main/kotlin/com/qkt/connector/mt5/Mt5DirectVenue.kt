package com.qkt.connector.mt5

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.connectivity.DirectAccount
import com.qkt.connectivity.DirectDeal
import com.qkt.connectivity.DirectPendingOrder
import com.qkt.connectivity.DirectPosition
import com.qkt.connectivity.DirectResult
import com.qkt.connectivity.DirectTick
import com.qkt.connectivity.DirectVenue
import com.qkt.connectivity.VenueFacts
import com.qkt.connector.mt5.marketdata.Mt5BarFetcher
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant

/**
 * One MT5 login spoken to directly: synchronous gateway calls, no event bus, no pollers. Orders go
 * through [MT5OrderTranslator] (the boundary the live broker uses); a stop and target ride on the
 * order itself, which MT5 accepts on market and pending orders. The client never retries a write, so
 * a timeout is reported, never resent.
 */
class Mt5DirectVenue(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
) : DirectVenue {
    init {
        if (profile.hasExpectedAccount) MT5AccountVerifier.fetchAndVerify(profile, client)
    }

    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol)

    override fun venueSymbol(qktSymbol: String): String = mt5Symbol.toBroker(qktSymbol.substringAfter(':'))

    override fun facts(qktSymbol: String): VenueFacts {
        val broker = venueSymbol(qktSymbol)
        val tick =
            client.getTick(broker)
                ?: error("venue did not return a quote for $qktSymbol ($broker); is the gateway up?")
        val info = client.getSymbolInfo(broker)
        val account = client.getAccount()
        return VenueFacts(
            bid = tick.bid,
            ask = tick.ask,
            equity = account?.equity,
            balance = account?.balance,
            accountCurrency = account?.currency?.ifBlank { null },
            contractSize = info?.contractSize,
            quoteCurrency = null,
            volumeMin = info?.volumeMin,
            volumeStep = info?.volumeStep,
            volumeMax = null,
            digits = info?.digits,
        )
    }

    override fun place(
        order: OrderRequest,
        stopLoss: BigDecimal?,
        takeProfit: BigDecimal?,
        clientId: String,
    ): DirectResult {
        val wire =
            when (val t = translator.translate(order)) {
                is MT5Translation.Single -> t.request
                is MT5Translation.Composite -> error("composite orders are not supported one-shot")
            }
        return client.placeOrder(wire.copy(sl = wire.sl ?: stopLoss, tp = wire.tp ?: takeProfit)).toDirect()
    }

    override fun close(
        position: DirectPosition,
        volume: BigDecimal?,
        clientId: String,
    ): DirectResult = client.closePosition(ticketOf(position.ticket), volume).toDirect()

    override fun modify(
        position: DirectPosition,
        stopLoss: BigDecimal?,
        takeProfit: BigDecimal?,
    ): DirectResult = client.modifyPosition(ticketOf(position.ticket), stopLoss, takeProfit).toDirect()

    override fun cancel(ticket: String): DirectResult {
        val ok = client.cancelOrder(ticketOf(ticket)).isNotEmpty()
        return DirectResult(ok, 0, ticket, "0", BigDecimal.ZERO, if (ok) null else "venue refused the cancel")
    }

    override fun account(): DirectAccount? =
        client.getAccount()?.let {
            DirectAccount(
                equity = it.equity,
                balance = it.balance,
                currency = it.currency,
                margin = it.margin,
                freeMargin = it.marginFree,
                marginLevel = it.marginLevel,
                openProfit = it.profit,
                leverage = it.leverage.toLong(),
                login = it.login.toString(),
                server = it.server,
                hedging = it.isHedging,
            )
        }

    override fun positions(): List<DirectPosition>? = client.getPositions()?.map { it.toDirect() }

    override fun pendingOrders(): List<DirectPendingOrder>? =
        client.getPendingOrders()?.map {
            DirectPendingOrder(
                it.ticket.toString(),
                it.symbol,
                it.type,
                it.volume,
                it.priceOpen,
                it.sl,
                it.tp,
                it.timeExpiration,
                it.comment,
            )
        }

    override fun deals(
        fromMs: Long,
        toMs: Long,
    ): List<DirectDeal>? = client.getDeals(fromMs, toMs)?.map { it.toDirect() }

    override fun tick(qktSymbol: String): DirectTick? =
        client.getTick(venueSymbol(qktSymbol))?.let { DirectTick(it.bid, it.ask, it.timeMs) }

    override fun bars(
        qktSymbol: String,
        window: TimeWindow,
        count: Int,
        nowMs: Long,
    ): List<Candle> {
        val fetcher =
            Mt5BarFetcher(
                baseUrl = profile.gatewayUrl,
                serverTimeZone = profile.serverTimeZone,
                apiKey = profile.apiKey,
            )
        // Over-fetch 3x to survive weekends and closed sessions, then keep the newest bars.
        val fromMs = nowMs - window.durationMs * count * 3
        return fetcher
            .fetchRange(
                symbol = venueSymbol(qktSymbol),
                window = window,
                range = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(nowMs)),
            ).toList()
            .takeLast(count)
    }

    private fun ticketOf(ticket: String): Long = ticket.toLongOrNull() ?: error("invalid MT5 ticket '$ticket'")

    companion object {
        /** A one-shot venue for [profile]: its own client that never retries a write. */
        fun of(profile: MT5BrokerProfile): Mt5DirectVenue =
            Mt5DirectVenue(
                profile,
                MT5Client(
                    gatewayUrl = profile.gatewayUrl,
                    serverTimeZone = profile.serverTimeZone,
                    httpTimeoutMs = profile.httpTimeoutMs,
                    retryAttempts = 0,
                    apiKey = profile.apiKey,
                ),
            )
    }
}
