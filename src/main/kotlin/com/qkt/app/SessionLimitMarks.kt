package com.qkt.app

import com.qkt.common.Money
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * Values a session's open positions for the daemon's hard limit, per symbol, trusting a mark only when it is real
 * and fresh. A flatten must not come from a bad value, so a symbol that is not gets no value at all: it counts as
 * nothing and is named in the reading, which can only delay a flatten, never cause one. Runs on the engine thread.
 */
internal class SessionLimitMarks(
    private val positions: StrategyPositionTracker,
    private val pnl: StrategyPnL,
    private val prices: MarketPriceTracker,
    private val gate: MarketDataGate,
) {
    fun read(
        strategyIds: List<String>,
        realizedToday: BigDecimal,
        book: StopFlatten.Book,
    ): DailyLimitReading {
        var open = Money.ZERO
        val unmeasured = linkedMapOf<String, String>()
        for (id in strategyIds) {
            for (symbol in positions.positionsFor(id).keys) {
                val why = untrusted(symbol)
                val value =
                    if (why == null) {
                        runCatching { pnl.unrealizedFor(id, symbol) }
                            .onFailure {
                                unmeasured[symbol] = "not valued: ${it.message}"
                            }.getOrNull()
                    } else {
                        unmeasured[symbol] = why
                        null
                    }
                if (value != null) open = open.add(value)
            }
        }
        return DailyLimitReading(strategyIds.first(), realizedToday, open, unmeasured, book.openLegs.size, book.closing)
    }

    private fun untrusted(symbol: String): String? {
        val price = prices.lastPrice(symbol)
        return when {
            price == null -> "no price"
            price.signum() <= 0 -> "invalid price $price"
            symbol !in gate.bySymbol -> "no live tick since this session started"
            !gate.isHealthy(symbol) -> "stale or out of session"
            else -> null
        }
    }
}

/** The legs [ids] still hold, and whether a close of one is still working (engine thread). */
internal fun stopBook(
    ids: List<String>,
    pipeline: TradingPipeline,
    positions: StrategyPositionTracker,
): StopFlatten.Book {
    val closing =
        pipeline.orderManager.activeOrders().any { o ->
            val r = o.request
            r.strategyId in ids &&
                r is OrderRequest.Market &&
                (r.closesTicket != null || r.legIntent is LegIntent.Close)
        }
    return StopFlatten.Book(
        ids.flatMap { id ->
            positions.allLegsFor(id).map { "${it.legId} (${it.symbol})" }
        },
        closing,
    )
}
