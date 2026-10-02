package com.qkt.connector.mt5

import java.math.BigDecimal

/**
 * Symbol metadata reported by the venue — used for size/price validation and PnL math.
 *
 * [contractSize] is the venue's lot contract size (XAUUSD = 100 oz/lot, EURUSD = 100,000
 * units). Multiplied through `PaperBroker` PnL math and the DSL's `SIZING RISK` so
 * backtest results are in the same dollar units as live MT5 fills.
 */
data class MT5SymbolInfo(
    val ask: BigDecimal,
    val bid: BigDecimal,
    val digits: Int,
    val point: BigDecimal,
    val tradeStopsLevel: Int,
    val volumeMin: BigDecimal,
    val volumeStep: BigDecimal,
    val contractSize: BigDecimal,
    val volumeMax: BigDecimal? = null,
    val tradeFreezeLevel: Int = 0,
    /** The symbol's swap terms, or null when the gateway does not report them. */
    val swap: MT5SymbolSwap? = null,
)

/**
 * A symbol's swap terms as MT5 reports them: [mode] is `SYMBOL_SWAP_MODE` (0 disabled, 1 points,
 * others in money or interest), [long] and [short] are per lot per rollover in that mode's units,
 * and [tripleDay] is `swap_rollover3days` (0 Sunday … 6 Saturday).
 */
data class MT5SymbolSwap(
    val mode: Int,
    val long: BigDecimal,
    val short: BigDecimal,
    val tripleDay: Int,
)
