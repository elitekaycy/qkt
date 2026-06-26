package com.qkt.risk.book

import com.qkt.accounting.AccountingEngine
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * Backtest-side book state. One engine holds every strategy, so the book is read directly: each
 * strategy's net position per symbol becomes a [Leg] valued at last price x contract size, book
 * equity comes from the global PnL, and per-strategy PnL from [StrategyPnL].
 */
class EngineBookStateSource(
    private val strategyIds: List<String>,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
    private val positions: StrategyPositionTracker,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry,
    private val startingBalance: BigDecimal,
    private val accounting: AccountingEngine = AccountingEngine(),
) : BookStateSource {
    override fun sample(timestampMs: Long): BookSnapshot {
        val legs = ArrayList<Leg>()
        for ((strategyId, bySymbol) in positions.allByStrategy()) {
            for ((symbol, pos) in bySymbol) {
                if (pos.quantity.signum() == 0) continue
                val price = prices.lastPrice(symbol) ?: continue
                val cs = instruments.lookup(symbol)?.contractSize ?: BigDecimal.ONE
                legs.add(Leg(strategyId, symbol, pos.quantity, price, cs))
            }
        }
        val bookEquity = startingBalance.add(pnl.realizedTotal()).add(pnl.unrealizedTotal())
        val perStrategyPnl = strategyIds.associateWith { strategyPnL.totalFor(it) }
        return BookSnapshot(timestampMs, bookEquity, bookExposure(legs, accounting, timestampMs), perStrategyPnl)
    }
}
