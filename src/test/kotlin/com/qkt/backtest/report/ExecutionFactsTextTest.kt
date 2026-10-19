package com.qkt.backtest.report

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionFacts
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SlippageSpec
import com.qkt.broker.FillQuotes
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The assumption and cost lines state what the run did, not the broker kind's defaults (#1437). */
class ExecutionFactsTextTest {
    private val report =
        PerformanceReport(
            realizedTotal = BigDecimal.ZERO,
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal.ZERO,
            tradeCount = 0,
            winRate = BigDecimal.ZERO,
            maxDrawdown = BigDecimal.ZERO,
            profitFactor = null,
            avgWin = BigDecimal.ZERO,
            avgLoss = BigDecimal.ZERO,
            largestWin = BigDecimal.ZERO,
            largestLoss = BigDecimal.ZERO,
            maxConsecutiveLosses = 0,
            sharpeRatio = null,
            calmarRatio = null,
            equityCurve = emptyList(),
        )
    private val mt5Ticks =
        ExecutionFacts(
            broker = BrokerKind.MT5_SIM,
            brokerSymbols = setOf("EXNESS:XAUUSD"),
            quotes = FillQuotes(recorded = 40),
            slippage = SlippageSpec.INSTRUMENT,
            instrumentSlippagePoints = mapOf("EXNESS:XAUUSD" to 0),
        )

    private fun lines(f: ExecutionFacts) = ExecutionFactsText.lines(f).map { (label, text) -> "$label: $text" }

    @Test
    fun `an mt5-sim tick run fills at the recorded bid and ask and pays that spread`() {
        assertThat(lines(mt5Ticks)).containsExactly("Fills: recorded bid/ask from ticks", "Slippage: none")
        assertThat(
            ExecutionFactsText.costs(mt5Ticks, report, "USD"),
        ).isEqualTo("spread from ticks; no commission or swap")
        val paid = report.copy(commissionPaid = BigDecimal("262.99"), swapPaid = BigDecimal("319.33"))
        assertThat(ExecutionFactsText.costs(mt5Ticks, paid, "USD"))
            .isEqualTo("spread from ticks, commission 262.99 USD, swap 319.33 USD")
    }

    @Test
    fun `mid-only data is said to get a synthetic spread`() {
        val f = mt5Ticks.copy(quotes = FillQuotes(recorded = 3, synthetic = 2))

        assertThat(lines(f).first())
            .isEqualTo(
                "Fills: recorded bid/ask from ticks; a synthetic 2-point spread around the middle price, " +
                    "where the data had no bid/ask",
            )
        assertThat(ExecutionFactsText.costs(f, report, "USD")).startsWith("spread from ticks, synthetic 2-point spread")
    }

    @Test
    fun `slippage on perpetuals is stated even on the paper broker`() {
        val f =
            ExecutionFacts(
                broker = BrokerKind.PAPER,
                slippage = SlippageSpec.FIXED_POINTS,
                slippagePoints = 5,
                futures = setOf("BINANCE_UM:ETHUSDT"),
            )

        assertThat(lines(f))
            .containsExactly(
                "Slippage: 5 points against each market and stop fill on futures",
                "Futures: exchange simulator: executable price, root fees per fill, rolls booked as roll costs",
            )
    }

    @Test
    fun `the paper broker says it ignores a configured slippage`() {
        val f =
            ExecutionFacts(
                broker = BrokerKind.PAPER,
                brokerSymbols = setOf("BACKTEST:XAUUSD"),
                slippage = SlippageSpec.FIXED_POINTS,
                slippagePoints = 5,
            )

        assertThat(lines(f))
            .containsExactly(
                "Fills: middle price, no spread",
                "Slippage: none (the paper broker does not slip fills; --broker mt5-sim does)",
            )
        assertThat(ExecutionFactsText.costs(f, report, "USD")).isEqualTo("none modeled (no spread, commission or swap)")
    }

    @Test
    fun `paper bars fill at order levels`() {
        val f = ExecutionFacts(BrokerKind.PAPER, brokerSymbols = setOf("A"), barLevelSymbols = setOf("A"))

        assertThat(lines(f).first()).isEqualTo("Fills: bar prices, at order levels, no spread")
    }

    @Test
    fun `chaos settings and bars without ticks each get a line`() {
        val f =
            mt5Ticks.copy(
                partialFillFraction = BigDecimal("0.50"),
                rejectEvery = 10,
                slippage = SlippageSpec.UNIFORM_RANDOM,
                slippagePoints = 20,
                barsWithoutTicks = mapOf("EXNESS:XAUUSD" to 12),
            )

        assertThat(lines(f))
            .contains(
                "Slippage: a random 0 to 20 points (seeded) against each market and stop fill",
                "Partial fills: each order fills in two slices, 50.0% first",
                "Rejections: every 10th order is rejected",
                "Bars without ticks: EXNESS:XAUUSD 12; these filled at the bar's own prices",
            )
    }

    @Test
    fun `a result without recorded facts states only the broker's model`() {
        assertThat(lines(ExecutionFacts.assumed(BrokerKind.MT5_SIM)))
            .containsExactly("Fills: bid/ask from the data, or a synthetic 2-point spread where it has none")
    }
}
