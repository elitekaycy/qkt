package com.qkt.app

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic seedable tick generator for stress / coexistence tests.
 *
 * Produces [candleCount] * [ticksPerCandle] ticks for a single symbol, with prices
 * following a seeded random walk plus a slow sine wave so trends form and indicators
 * (EMA cross, RSI swing, breakout) actually fire. Same seed → same tick sequence.
 */
class SyntheticTickStream(
    private val seed: Long,
    private val symbol: String,
    private val startPrice: BigDecimal,
    private val candleCount: Int,
    private val ticksPerCandle: Int = 6,
    private val candleTfMillis: Long = 60_000L,
    private val startTimestamp: Long = 0L,
) {
    init {
        require(candleCount > 0) { "candleCount must be positive: $candleCount" }
        require(ticksPerCandle > 0) { "ticksPerCandle must be positive: $ticksPerCandle" }
        require(candleTfMillis > 0) { "candleTfMillis must be positive: $candleTfMillis" }
    }

    fun build(): List<Tick> {
        val rng = Random(seed)
        val totalTicks = candleCount * ticksPerCandle
        val tickIntervalMs = candleTfMillis / ticksPerCandle
        val stepSize = startPrice.toDouble() * 0.0008
        val sineAmplitude = startPrice.toDouble() * 0.02
        val sinePeriodTicks = ticksPerCandle * 50.0

        var price = startPrice.toDouble()
        val out = ArrayList<Tick>(totalTicks)
        for (i in 0 until totalTicks) {
            val walk = (rng.nextDouble() - 0.5) * 2.0 * stepSize
            val trend = sineAmplitude * sin(2.0 * Math.PI * i / sinePeriodTicks)
            price += walk
            val tickPrice = price + trend
            val ts = startTimestamp + i * tickIntervalMs
            val vol = BigDecimal(rng.nextInt(1, 100))
            out.add(
                Tick(
                    symbol = symbol,
                    price = BigDecimal(tickPrice).setScale(2, RoundingMode.HALF_UP),
                    timestamp = ts,
                    volume = vol,
                ),
            )
        }
        return out
    }
}
