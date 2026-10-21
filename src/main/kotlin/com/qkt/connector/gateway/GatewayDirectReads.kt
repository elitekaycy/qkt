package com.qkt.connector.gateway

import com.qkt.candles.TimeWindow
import com.qkt.connectivity.DirectAccount
import com.qkt.connectivity.DirectDeal
import com.qkt.connectivity.DirectPendingOrder
import com.qkt.connectivity.DirectPosition
import com.qkt.connectivity.DirectTick
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * A gateway's account, positions, orders, deals, quotes and bars in the shapes `qkt bot` prints. A netting
 * position's ticket is its venue code, so `qkt bot close --ticket` names it the way a hedging ticket would.
 */
internal class GatewayDirectReads(
    private val name: String,
    private val client: GatewayClient,
) {
    fun account(): DirectAccount {
        val health = client.health()
        val account = client.account()
        val equity = BigDecimal(account.equity)
        val used = BigDecimal(account.marginUsed)
        return DirectAccount(
            equity = equity,
            balance = BigDecimal(account.balance),
            currency = account.currency,
            margin = used,
            freeMargin = BigDecimal(account.marginAvailable),
            marginLevel =
                if (used.signum() >
                    0
                ) {
                    equity.divide(used, java.math.MathContext.DECIMAL64) * HUNDRED
                } else {
                    null
                },
            openProfit = equity - BigDecimal(account.balance),
            leverage = null,
            login = health.accountLogin,
            server = "$name (${health.adapter} ${health.tradeMode})",
            hedging = client.positions().accounting == "hedging",
        )
    }

    fun positions(): List<DirectPosition> =
        client.positions().positions.map {
            val quantity = BigDecimal(it.quantity)
            DirectPosition(
                ticket = it.ticket ?: it.symbol,
                symbol = it.symbol,
                side = if (quantity.signum() >= 0) "BUY" else "SELL",
                lots = quantity.abs(),
                entry = BigDecimal(it.avgPrice),
                current = null,
                sl = null,
                tp = null,
                profit = null,
                swap = null,
                openedAtMs = it.openedAt,
                comment = null,
            )
        }

    fun pendingOrders(): List<DirectPendingOrder> =
        client.orders().map {
            DirectPendingOrder(
                ticket = it.clientOrderId,
                symbol = it.symbol,
                type = "${it.side} ${it.type}",
                lots = BigDecimal(it.quantity) - BigDecimal(it.filledQuantity),
                price = (it.limitPrice ?: it.stopPrice)?.let(::BigDecimal),
                sl = null,
                tp = null,
                expiresAt = null,
                comment = it.clientOrderId,
            )
        }

    fun deals(
        fromMs: Long,
        toMs: Long,
    ): List<DirectDeal> = client.deals(fromMs, toMs).map(::deal)

    /** One venue execution as `qkt bot` prints it: VGP v1 states no position, leg or realized P&L for it. */
    fun deal(fill: WireFill): DirectDeal =
        fill.let {
            DirectDeal(
                dealTicket = it.fillId,
                positionTicket = null,
                symbol = it.symbol,
                side = it.side.uppercase(),
                entry = null,
                lots = BigDecimal(it.quantity),
                price = BigDecimal(it.price),
                profit = null,
                commission =
                    it.costs.takeIf { c -> c.isNotEmpty() }?.fold(BigDecimal.ZERO) { s, c ->
                        s +
                            BigDecimal(c.amount)
                    },
                swap = null,
                fee = null,
                timeMs = it.time,
                comment = it.clientOrderId,
            )
        }

    fun tick(quote: WireQuote): DirectTick? {
        val bid = quote.bid?.let(::BigDecimal) ?: quote.ask?.let(::BigDecimal) ?: return null
        val ask = quote.ask?.let(::BigDecimal) ?: bid
        return DirectTick(bid, ask, quote.time)
    }

    /** The newest [count] closed bars, over-fetched threefold to cover closed sessions. */
    fun bars(
        qktSymbol: String,
        code: String,
        window: TimeWindow,
        count: Int,
        nowMs: Long,
    ): List<Candle> {
        val ms = window.durationMs
        return client
            .bars(code, ms, nowMs - ms * count * 3, nowMs)
            .map {
                Candle(
                    qktSymbol,
                    BigDecimal(it.open),
                    BigDecimal(it.high),
                    BigDecimal(it.low),
                    BigDecimal(it.close),
                    BigDecimal(it.volume),
                    it.start,
                    it.start + ms,
                )
            }.filter { it.endTime <= nowMs }
            .takeLast(count)
    }

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}
