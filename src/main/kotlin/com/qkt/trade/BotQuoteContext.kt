package com.qkt.trade

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * Point-in-time venue facts a one-shot compile needs: the live quote (sided entry
 * pricing), account value (percent/risk sizing), and the instrument's contract and
 * volume constraints (lot conversion and quantization). Null fields mean the venue
 * did not report the value — consumers that need one must reject, never assume zero.
 */
data class BotQuoteContext(
    val bid: BigDecimal,
    val ask: BigDecimal,
    val equity: BigDecimal?,
    val balance: BigDecimal?,
    /** Units of the base asset per 1.0 lot, e.g. 100 for XAUUSD. */
    val contractSize: BigDecimal?,
    val accountCurrency: String,
    /** Currency the instrument is quoted in; null when the venue does not report it. */
    val quoteCurrency: String?,
    val volumeMin: BigDecimal?,
    val volumeStep: BigDecimal?,
    val volumeMax: BigDecimal?,
    /** Price decimal places for the instrument; exit prices are rounded to this. */
    val digits: Int?,
)

/**
 * A compiled one-shot order: the [request] journaled and egressed (a standard
 * [OrderRequest], [OrderRequest.Bracket] when both exits are present), plus the
 * resolved absolute [stopLoss]/[takeProfit] venue prices the gateway attaches to
 * the placement (MT5 rides SL/TP on the order itself).
 */
data class CompiledBotOrder(
    val request: OrderRequest,
    val stopLoss: BigDecimal?,
    val takeProfit: BigDecimal?,
)
