package com.qkt.marketdata

import com.qkt.common.Clock
import kotlin.random.Random

class MockTickFeed(
    private val symbol: String,
    private val startPrice: Double,
    private val count: Int,
    private val clock: Clock,
    private val tickIntervalMs: Long = 1_000L,
    private val random: Random = Random(seed = 42L),
) : TickFeed {
    init {
        require(count >= 0) { "count must be >= 0: $count" }
        require(startPrice > 0.0) { "startPrice must be > 0: $startPrice" }
        require(tickIntervalMs > 0L) { "tickIntervalMs must be > 0: $tickIntervalMs" }
    }

    private val startTime = clock.now()
    private var emitted = 0
    private var price = startPrice

    override fun next(): Tick? {
        if (emitted >= count) return null
        price *= (1.0 + (random.nextDouble() - 0.5) * 0.01)
        val ts = startTime + emitted * tickIntervalMs
        emitted++
        return Tick(symbol, price, ts)
    }
}
