package com.qkt.cli.daemon.portfolio

import com.qkt.pnl.PnLProvider
import com.qkt.risk.FakePnL
import com.qkt.risk.TestClock
import com.qkt.risk.rules.MaxDailyLoss
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioRiskAggregatorSamplingTest : PortfolioRiskAggregatorFixture() {
    @Test
    fun `child fills feed the book daily-loss rule even before the aggregator binds`() {
        val clock = TestClock(0L)
        val state = bookRiskState(FakePnL(BigDecimal("-1100"), BigDecimal.ZERO), clock)
        val child = FakeChild()
        val aggregator =
            PortfolioRiskAggregator(
                listOf(child),
                state,
                listOf(MaxDailyLoss(BigDecimal("1000"))),
                clock,
            )
        val fills = PortfolioRiskFillBuffer()
        fills.record("alpha", BigDecimal("-600"))
        fills.record("beta", BigDecimal("-500"))

        fills.bind(aggregator)
        aggregator.evaluate()

        assertThat(state.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("-1100")
        assertThat(child.flattened).isEqualTo(1)
        assertThat(child.halted).contains("daily loss")
    }

    @Test
    fun `book sampling does not block concurrent child fill accounting`() {
        val clock = TestClock(0L)
        val state = bookRiskState(FakePnL(BigDecimal.ZERO, BigDecimal.ZERO), clock)
        val fillRecorded = CountDownLatch(1)
        val fillExecutor = Executors.newSingleThreadExecutor()
        var fillSubmitted = false
        lateinit var aggregator: PortfolioRiskAggregator
        aggregator =
            PortfolioRiskAggregator(
                children = emptyList(),
                bookRiskState = state,
                haltRules = emptyList(),
                clock = clock,
                onSample = {
                    if (!fillSubmitted) {
                        fillSubmitted = true
                        fillExecutor.execute {
                            aggregator.recordRealized("alpha", BigDecimal("12.50"))
                            fillRecorded.countDown()
                        }
                        assertThat(fillRecorded.await(1, TimeUnit.SECONDS))
                            .describedAs("the child engine fill callback can enter while book sampling waits")
                            .isTrue()
                    }
                },
            )

        try {
            aggregator.evaluate()
            aggregator.evaluate()
        } finally {
            fillExecutor.shutdownNow()
        }

        assertThat(state.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("12.50")
    }

    @Test
    fun `candle-aligned controller sampling is independent of the risk heartbeat`() {
        val clock = TestClock(0L)
        val state = bookRiskState(FakePnL(BigDecimal.ZERO, BigDecimal.ZERO), clock)
        val timestamps = mutableListOf<Long>()
        val aggregator =
            PortfolioRiskAggregator(
                children = emptyList(),
                bookRiskState = state,
                haltRules = emptyList(),
                clock = clock,
                onSample = timestamps::add,
                sampleOnEvaluate = false,
            )

        aggregator.evaluate()
        aggregator.sample(60_000L)
        aggregator.evaluate()

        assertThat(timestamps).containsExactly(60_000L)
    }

    @Test
    fun `child fill callbacks defer cross-engine pnl reads to the risk heartbeat`() {
        val clock = TestClock(0L)
        var pnlReads = 0
        val pnl =
            object : PnLProvider {
                override fun realizedTotal(): BigDecimal {
                    pnlReads++
                    return BigDecimal.ZERO
                }

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun totalPnL(): BigDecimal = BigDecimal.ZERO
            }
        val state = bookRiskState(pnl, clock)
        val aggregator =
            PortfolioRiskAggregator(
                children = emptyList(),
                bookRiskState = state,
                haltRules = emptyList(),
                clock = clock,
            )

        aggregator.recordRealized("alpha", BigDecimal("12.50"))

        assertThat(pnlReads).isZero()
        assertThat(state.dailyPnLTracker.globalRealizedToday()).isZero()

        aggregator.evaluate()

        assertThat(pnlReads).isPositive()
        assertThat(state.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("12.50")
    }
}
