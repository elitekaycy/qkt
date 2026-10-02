package com.qkt.marketdata

import com.qkt.common.Clock
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An option premium moves several times its underlying's move, so a gate told not to judge its prices
 * for outliers passes a jump a spot band would reject; a crossed book is still rejected.
 */
class MarketDataGateOptionTest {
    private val clock =
        object : Clock {
            var nowMs = 0L

            override fun now(): Long = nowMs
        }

    private fun tick(
        price: String,
        bid: String = price,
        ask: String = price,
    ) = Tick(
        "DERIBIT:BTC_USDC_30OCT26_80000_P",
        BigDecimal(price),
        clock.nowMs,
        bid = BigDecimal(bid),
        ask = BigDecimal(ask),
    )

    private fun warmed(gate: MarketDataGate): MarketDataGate {
        repeat(20) {
            clock.nowMs += 1_000L
            gate.observe(tick("1320"))
        }
        clock.nowMs += 1_000L
        return gate
    }

    @Test
    fun `a premium jump passes when the symbol is not judged for outliers`() {
        val spot = warmed(MarketDataGate(clock))
        assertThat(spot.observe(tick("1425"))).isEqualTo(MarketDataGate.Verdict.OUTLIER)

        val option = warmed(MarketDataGate(clock, judgesOutliers = { false }))
        assertThat(option.observe(tick("1425"))).isEqualTo(MarketDataGate.Verdict.OK)
    }

    @Test
    fun `a crossed book is rejected whether or not prices are judged`() {
        val option = warmed(MarketDataGate(clock, judgesOutliers = { false }))

        assertThat(option.observe(tick("1320", bid = "1330", ask = "1310"))).isEqualTo(MarketDataGate.Verdict.OUTLIER)
    }
}
