package com.qkt.connector.mt5

import com.qkt.broker.BrokerDeal
import com.qkt.broker.BrokerPositionTicket

/**
 * Answers the engine's read-only questions about the venue in engine terms: deal history, resting
 * orders, open position tickets, net open positions and instrument rules. Symbols come back
 * venue-qualified, e.g. the gateway's `XAUUSDm` position 3258722177 is reported as `EXNESS:XAUUSD`.
 * [positionBook] and [symbolMeta] are the broker's own shared instances, not copies.
 */
internal class MT5BrokerVenueReads(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val mt5Symbol: MT5Symbol,
    private val positionBook: MT5PositionBook,
    private val symbolMeta: MutableMap<String, MT5SymbolInfo>,
) {
    fun deals(
        from: Long,
        to: Long,
    ): List<BrokerDeal> {
        val deals = runCatching { client.getDeals(from, to) }.getOrNull() ?: return emptyList()
        // Range queries also return balance operations (deposits/withdrawals, type 2+) with
        // no symbol and zero volume — only trade deals (0=BUY, 1=SELL) belong in history.
        return deals.filter { it.type == 0 || it.type == 1 }.map { d ->
            BrokerDeal(
                broker = profile.name.uppercase(),
                dealTicket = d.ticket.toString(),
                positionTicket = d.positionTicket.takeIf { it != 0L }?.toString(),
                orderTicket = d.orderTicket.takeIf { it != 0L }?.toString(),
                symbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(d.symbol)}",
                side = if (d.type == 0) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL,
                entry = mt5DealEntryName(d.entry),
                qty = d.volume,
                price = d.price,
                profit = d.profit,
                commission = d.commission,
                swap = d.swap,
                magic = d.magic,
                comment = d.comment,
                ts = d.timeMs,
                fee = d.fee,
                clientOrderId = d.clientOrderId,
            )
        }
    }

    fun pendingOrders(): List<com.qkt.broker.BrokerPendingOrder> {
        val orders = runCatching { client.getPendingOrders(magic = profile.magic) }.getOrNull() ?: return emptyList()
        return orders.map { o ->
            com.qkt.broker.BrokerPendingOrder(
                ticket = o.ticket.toString(),
                symbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(o.symbol)}",
                side = if (o.type.contains("BUY")) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL,
                orderType = o.type,
                qty = o.volume,
                price = o.priceOpen,
                stopLoss = o.sl,
                takeProfit = o.tp,
                expiresAt = o.timeExpiration.takeIf { it != 0L },
                createdAt = o.timeSetup.takeIf { it != 0L },
                magic = o.magic,
                comment = o.comment,
                clientOrderId = o.clientOrderId,
            )
        }
    }

    fun positionTickets(): List<BrokerPositionTicket> {
        // A failed gateway read must throw, not read as "no positions": the state
        // poller prunes its ticket attributions to this list, and an empty answer
        // on a transient outage would wipe them.
        val positions =
            client.getPositions(magic = profile.magic)
                ?: error(
                    "MT5Broker ${profile.name} positionTickets: gateway read failed" +
                        client.lastReadFailure()?.let { " ($it)" }.orEmpty(),
                )
        return positions.map { p ->
            BrokerPositionTicket(
                ticket = p.ticket.toString(),
                symbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(p.symbol)}",
                side = if (p.type == 0) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL,
                qty = p.volume,
                entryPrice = p.priceOpen,
                currentPrice = p.priceCurrent,
                profit = p.profit,
                swap = p.swap,
                openedAt = p.openTime,
                comment = p.comment,
                stopLoss = p.sl,
                takeProfit = p.tp,
                requestedStopLoss = positionBook.meta(p.ticket)?.protection?.stopLoss,
                requestedTakeProfit = positionBook.meta(p.ticket)?.protection?.takeProfit,
                magic = p.magic,
                clientOrderId = p.clientOrderId,
            )
        }
    }

    fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> {
        // A failed read must surface, not read as flat — a session that believes it is
        // flat while holding leveraged positions re-enters and doubles up (#376).
        val positions =
            client.getPositions(magic = profile.magic)
                ?: error(
                    "MT5Broker ${profile.name} getOpenPositions: gateway read failed" +
                        client.lastReadFailure()?.let { " ($it)" }.orEmpty(),
                )
        val out: MutableMap<String, MutableList<com.qkt.positions.Position>> = mutableMapOf()
        for (p in positions) {
            val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(p.symbol)}"
            val signedQty = if (p.type == 0) p.volume else p.volume.negate()
            out.getOrPut(qktSymbol) { mutableListOf() }.add(
                com.qkt.positions.Position(
                    symbol = qktSymbol,
                    quantity = signedQty,
                    avgEntryPrice = p.priceOpen,
                ),
            )
        }
        return out
    }

    /**
     * Resolve [InstrumentMeta] for a qkt-side symbol (e.g. `EXNESS:XAUUSD`).
     *
     * Reads through the same `/symbol_info` cache that powers v0.26.3 volume quantization
     * and v0.26.4 price rounding — primes the cache on first call, hits memory after.
     * Used by [com.qkt.connector.mt5.MT5InstrumentRegistry] so the trading pipeline gets a
     * consistent meta picture regardless of mode.
     */
    fun instrumentMeta(qktSymbol: String): com.qkt.instrument.InstrumentMeta? {
        val prefix = "${profile.name.uppercase()}:"
        val bare = qktSymbol.removePrefix(prefix)
        val brokerSymbol = mt5Symbol.toBroker(bare)
        val info =
            symbolMeta[brokerSymbol]
                ?: client.getSymbolInfo(brokerSymbol)?.also { symbolMeta[brokerSymbol] = it }
                ?: return null
        return com.qkt.instrument.InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = info.contractSize,
            volumeStep = info.volumeStep,
            volumeMin = info.volumeMin,
            volumeMax = info.volumeMax,
            pointSize = info.point,
            digits = info.digits,
            tradeStopsLevelPoints = info.tradeStopsLevel,
        )
    }
}
