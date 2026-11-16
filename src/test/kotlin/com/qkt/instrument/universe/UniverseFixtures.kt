package com.qkt.instrument.universe

import com.qkt.instrument.DerivativeTerms
import com.qkt.instrument.EquityCosts
import com.qkt.instrument.EventContracts
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.OptionRight
import com.qkt.instrument.OptionTerms
import com.qkt.instrument.TickSteps
import com.qkt.instrument.TradeMode
import java.math.BigDecimal

/** A small cross-venue catalog for filter and resolver tests. */
internal object UniverseFixtures {
    const val NOW_MS = 1_794_000_000_000L
    const val DAY_MS = 86_400_000L

    fun meta(
        symbol: String,
        category: String? = null,
        currency: String? = null,
        contractSize: String = "1",
        pointSize: String = "0.01",
        tickSize: String? = null,
        tradeMode: TradeMode = TradeMode.FULL,
        derivative: DerivativeTerms? = null,
        equity: Boolean = false,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal(contractSize),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal(pointSize),
        digits = 2,
        tradeStopsLevelPoints = 0,
        currency = currency,
        derivative = derivative,
        tickSize = tickSize?.let(::BigDecimal),
        category = category,
        tradeMode = tradeMode,
        equityCosts = if (equity) EquityCosts() else null,
    )

    val eurusd = meta("EXNESS:EURUSD", category = "Forex\\Majors", currency = "USD", contractSize = "100000")
    val xauusd = meta("EXNESS:XAUUSD", category = "Metals", currency = "USD", contractSize = "100", tickSize = "0.05")
    val aapl =
        meta("EXNESS:AAPLm", category = "Stocks\\US", currency = "USD", equity = true, tradeMode = TradeMode.CLOSE_ONLY)
    val btcPerp =
        meta(
            "BYBIT_LINEAR:BTCUSDT",
            currency = "USDT",
            derivative = FutureTerms("BYBIT_LINEAR:BTCUSDT", null, perpetual = true),
        )
    val esDec =
        meta(
            "CME:ESZ6",
            currency = "USD",
            contractSize = "50",
            derivative = FutureTerms("CME:ES", expiryMs = NOW_MS + 30 * DAY_MS),
        )
    val btcCall =
        meta(
            "DERIBIT:BTC_27MAR26_100000_C",
            derivative =
                OptionTerms(
                    root = "DERIBIT:BTC_USDC",
                    underlyingIndex = "btc_usdc",
                    strike = BigDecimal(100_000),
                    right = OptionRight.CALL,
                    expiryMs = NOW_MS + 90 * DAY_MS,
                    tickSteps = TickSteps(BigDecimal("0.0005")),
                ),
        )
    val event = EventContracts.meta()

    val all = listOf(eurusd, xauusd, aapl, btcPerp, esDec, btcCall, event)
}
