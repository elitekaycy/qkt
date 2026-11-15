package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An event contract's price is bounded in [0, payout] and a jump from 0.30 to 0.95 is the news, so the live gate does
 * not judge it for outliers; every other symbol is judged as before.
 */
class LiveMarketDataGateEventTest {
    private val cfd =
        InstrumentMeta(
            "EXNESS:XAUUSD",
            BigDecimal("100"),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            null,
            BigDecimal("0.01"),
            2,
            0,
        )
    private val registry = EventContracts.registry(EventContracts.meta(), cfd)
    private val clock = FixedClock(0L)
    private val gate = MarketDataGate(clock, judgesOutliers = { judgesPriceOutliers(registry, it) })

    private fun tick(
        symbol: String,
        price: String,
    ) = Tick(symbol, BigDecimal(price), clock.time)

    private fun warm(
        symbol: String,
        price: String,
    ) = repeat(20) {
        clock.time += 1_000L
        gate.observe(tick(symbol, price))
    }

    @Test
    fun `an event contract is not judged, a CFD still is`() {
        assertThat(judgesPriceOutliers(registry, EventContracts.SYMBOL)).isFalse()
        assertThat(judgesPriceOutliers(registry, "EXNESS:XAUUSD")).isTrue()
    }

    @Test
    fun `a 0 point 30 to 0 point 95 jump on an event passes while the same move on a CFD is rejected`() {
        warm(EventContracts.SYMBOL, "0.30")
        warm("EXNESS:XAUUSD", "4000")
        clock.time += 1_000L

        assertThat(gate.observe(tick(EventContracts.SYMBOL, "0.95"))).isEqualTo(MarketDataGate.Verdict.OK)
        assertThat(gate.observe(tick("EXNESS:XAUUSD", "12667"))).isEqualTo(MarketDataGate.Verdict.OUTLIER)
    }
}
