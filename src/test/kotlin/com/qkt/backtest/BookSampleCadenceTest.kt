package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.marketdata.Candle
import com.qkt.risk.book.BookSnapshot
import com.qkt.risk.book.BookStateSource
import com.qkt.risk.book.Exposure
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The book samplers take one sample per candle boundary, however many symbols close a bar there: a boundary where
 * EURUSD and BTCUSD both close is one book return, not one real return plus a zero that halves the variance.
 */
class BookSampleCadenceTest {
    private val hour = 3_600_000L
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    private fun close(
        symbol: String,
        endMs: Long,
    ) {
        clock.time = endMs
        val p = BigDecimal("100")
        bus.publish(CandleEvent(Candle(symbol, p, p, p, p, BigDecimal.ONE, endMs - hour, endMs)))
    }

    @Test
    fun `the book risk monitor samples each boundary once`() {
        val sampledAt = mutableListOf<Long>()
        val source =
            object : BookStateSource {
                override fun sample(timestampMs: Long): BookSnapshot {
                    sampledAt.add(timestampMs)
                    return BookSnapshot(
                        timestampMs,
                        BigDecimal("10000").add(BigDecimal(sampledAt.size)),
                        Exposure(BigDecimal.ZERO, BigDecimal.ZERO, emptyMap()),
                        mapOf("a" to BigDecimal.ZERO, "b" to BigDecimal.ZERO),
                    )
                }
            }
        BookRiskMonitor(
            SampleCadence.CANDLE_CLOSE,
            bus,
            source,
            strategyCount = 2,
            startingBalance = BigDecimal("10000"),
        )

        close("EURUSD", hour)
        close("BTCUSD", hour)
        close("BTCUSD", 2 * hour)
        close("EURUSD", 3 * hour)
        close("BTCUSD", 3 * hour)

        assertThat(sampledAt).containsExactly(hour, 2 * hour, 3 * hour)
    }

    @Test
    fun `the book return collector folds one return per boundary`() {
        val priceTracker = com.qkt.marketdata.MarketPriceTracker()
        val pnl =
            com.qkt.pnl.PnLCalculator(
                com.qkt.positions
                    .StrategyPositionTracker()
                    .account,
                priceTracker,
            )
        val strategyPnL = com.qkt.pnl.StrategyPnL(com.qkt.positions.StrategyPositionTracker(), priceTracker)
        val collector =
            BookReturnCollector(
                SampleCadence.CANDLE_CLOSE,
                bus,
                pnl,
                strategyPnL,
                listOf("a", "b"),
                BigDecimal("10000"),
            )

        close("EURUSD", hour)
        close("BTCUSD", hour)
        close("EURUSD", 2 * hour)
        close("BTCUSD", 2 * hour)
        close("BTCUSD", 3 * hour)

        assertThat(collector.returnCount).isEqualTo(2)
    }

    @Test
    fun `an older boundary closing late is not sampled again`() {
        val once = OncePerBoundary()
        assertThat(listOf(hour, 3 * hour, 2 * hour, 3 * hour, 4 * hour).filter(once::first)).containsExactly(
            hour,
            3 * hour,
            4 * hour,
        )
    }
}
