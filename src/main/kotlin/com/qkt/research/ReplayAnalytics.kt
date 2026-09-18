package com.qkt.research

import com.qkt.backtest.BookReturnCollector
import com.qkt.backtest.BookRiskMonitor
import com.qkt.backtest.EquityCurveCollector
import com.qkt.backtest.ReturnAutocorrCollector
import com.qkt.backtest.SampleCadence
import com.qkt.bus.EventBus
import com.qkt.instrument.InstrumentRegistry
import com.qkt.risk.book.BookRiskController
import com.qkt.risk.book.EngineBookStateSource
import java.math.BigDecimal

/**
 * The bus-driven samplers a replay reports from: the equity curve, the conditional return
 * autocorrelation, the book return series and the book risk monitor. Each subscribes to the bus
 * when constructed, so they are built in this fixed order at one point of the replay wiring.
 */
internal class ReplayAnalytics(
    cadence: SampleCadence,
    bus: EventBus,
    books: ReplayBooks,
    strategyIds: List<String>,
    strategyCount: Int,
    startingBalance: BigDecimal,
    symbols: List<String>,
    initialTimestamp: Long,
    instruments: InstrumentRegistry,
    bookRiskController: BookRiskController?,
) {
    val collector =
        EquityCurveCollector(
            cadence = cadence,
            bus = bus,
            pnl = books.pnl,
            strategyPnL = books.strategyPnL,
            strategyIds = strategyIds,
            startingBalance = startingBalance,
            candleSymbols = symbols.toSet(),
            windowStartMs = initialTimestamp,
        )

    val autocorr = ReturnAutocorrCollector(bus)

    val bookReturns =
        BookReturnCollector(
            cadence = cadence,
            bus = bus,
            pnl = books.pnl,
            strategyPnL = books.strategyPnL,
            strategyIds = strategyIds,
            startingBalance = startingBalance,
        )

    val bookRiskMonitor =
        BookRiskMonitor(
            cadence = cadence,
            bus = bus,
            source =
                EngineBookStateSource(
                    strategyIds = strategyIds,
                    pnl = books.pnl,
                    strategyPnL = books.strategyPnL,
                    positions = books.strategyPositions,
                    prices = books.priceTracker,
                    instruments = instruments,
                    startingBalance = startingBalance,
                    accounting = books.accounting,
                ),
            strategyCount = strategyCount,
            startingBalance = startingBalance,
            controller = bookRiskController,
        )
}
