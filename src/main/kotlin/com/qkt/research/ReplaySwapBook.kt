package com.qkt.research

import com.qkt.instrument.InstrumentRegistry
import com.qkt.pnl.SwapFinancingBook

/** The swap financing book of a replay over [books], for [strategyIds] trading [symbols]. */
internal fun replaySwapBook(
    instruments: InstrumentRegistry,
    books: ReplayBooks,
    strategyIds: List<String>,
    symbols: List<String>,
): SwapFinancingBook =
    SwapFinancingBook(
        instruments = instruments,
        strategyPositions = books.strategyPositions,
        accounting = books.accounting,
        prices = books.priceTracker,
        strategyIds = strategyIds,
        symbols = symbols,
    )
