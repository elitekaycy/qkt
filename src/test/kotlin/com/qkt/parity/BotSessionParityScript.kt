package com.qkt.parity

import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import java.math.BigDecimal

/** The tick tape, the client's decision script and the comparable trade key both bot session arms share. */
internal object BotSessionParityScript {
    val symbol = "XAUUSD"

    /** 9 one-minute bars, two ticks each, plus a final tick that closes bar 9. */
    fun ticks(): List<Tick> =
        (0 until 9).flatMap { bar ->
            val base = bar * 60_000L
            listOf(
                Tick(symbol, BigDecimal(2400 + bar), base + 1_000L),
                Tick(symbol, BigDecimal(2402 + bar), base + 30_000L),
            )
        } + Tick(symbol, BigDecimal("2413"), 9 * 60_000L + 1_000L)

    /** The client's decision script, keyed by closed-bar count: identical in both arms. */
    fun decisions(): Map<Int, Signal> =
        mapOf(
            3 to Signal.Buy(symbol, BigDecimal.ONE),
            6 to Signal.Sell(symbol, BigDecimal.ONE),
        )

    data class SessionTrade(
        val symbol: String,
        val side: String,
        val quantity: BigDecimal,
        val price: BigDecimal,
        val timestamp: Long,
    )

    fun Trade.key(): SessionTrade =
        SessionTrade(
            symbol = symbol,
            side = side.toString(),
            quantity = quantity,
            price = price,
            timestamp = timestamp,
        )
}
