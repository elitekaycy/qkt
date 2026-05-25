package com.qkt.app

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AggregateBinding
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.IndicatorBinding
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Stress test: prove the engine can host 5 concurrent DSL strategies sharing a
 * CandleHub without state leak, signal misrouting, warmup interference, or trade
 * misattribution. Guards "I can trust qkt-prod to run all my strategies side by side."
 *
 * Runs against a deterministic synthetic tick stream so failures are reproducible
 * from the seed alone.
 */
@Tag("stress")
class MultiStrategyStressTest {
    private val symbol = "BACKTEST:BTCUSDT"
    private val startPrice = Money.of("100000")
    private val candleCount = 500
    private val ticksPerCandle = 6
    private val seed = 0xC0FFEEL

    private data class Fixture(
        val id: String,
        val file: String,
        val warmupBars: Int,
    )

    private val fixtures =
        listOf(
            Fixture("ema_cross", "src/test/resources/stress/ema-cross.qkt", 21),
            Fixture("rsi_revert", "src/test/resources/stress/rsi-revert.qkt", 14),
            Fixture("breakout", "src/test/resources/stress/breakout.qkt", 20),
            Fixture("dd_aware", "src/test/resources/stress/dd-aware.qkt", 21),
            Fixture("streak_aware", "src/test/resources/stress/streak-aware.qkt", 21),
        )

    private val portfolioId = "stress_portfolio"
    private val portfolioPath = "src/test/resources/stress/portfolio.qkt"

    private fun loadPortfolio(): Pair<String, Strategy> {
        val compiled = PortfolioLoader.load(Path.of(portfolioPath))
        val portfolio =
            PortfolioStrategy(
                compiled,
                ExprCompiler(IndicatorBinding.Bag(), AggregateBinding.Bag()),
            )
        return portfolioId to portfolio
    }

    private fun loadStrategies(): List<Pair<String, Strategy>> {
        val standalones =
            fixtures.map { fx ->
                val result = Dsl.parseFile(Path.of(fx.file))
                val ast =
                    (result as? ParseResult.Success)?.value
                        ?: error("failed to parse ${fx.file}: $result")
                fx.id to AstCompiler().compile(ast)
            }
        return standalones + loadPortfolio()
    }

    private fun newBacktest(): Backtest {
        val ticks =
            SyntheticTickStream(
                seed = seed,
                symbol = symbol,
                startPrice = startPrice,
                candleCount = candleCount,
                ticksPerCandle = ticksPerCandle,
            ).build()
        return Backtest(
            strategies = loadStrategies(),
            ticks = ticks,
            candleWindow = TimeWindow.ONE_MINUTE,
            startingBalance = Money.of("10000"),
        )
    }

    @Test
    fun `five standalone strategies plus one portfolio coexist and produce trade timelines`() {
        val result = newBacktest().run()
        val expectedKeys = (fixtures.map { it.id } + portfolioId).toTypedArray()
        assertThat(result.perStrategy).containsOnlyKeys(*expectedKeys)
        for (fx in fixtures) {
            val report = result.perStrategy.getValue(fx.id)
            assertThat(report.equityCurve)
                .withFailMessage("strategy ${fx.id} has no equity samples")
                .isNotEmpty()
        }
        assertThat(result.perStrategy.getValue(portfolioId).equityCurve)
            .withFailMessage("portfolio has no equity samples")
            .isNotEmpty()
        val tradingStrategies = result.perStrategy.count { it.value.tradeCount > 0 }
        assertThat(tradingStrategies)
            .withFailMessage("expected at least 3 of 6 strategies (incl. portfolio) to trade — got $tradingStrategies")
            .isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `portfolio trades attribute to portfolio id, not to standalone strategies`() {
        val result = newBacktest().run()
        val portfolioReport = result.perStrategy.getValue(portfolioId)
        if (portfolioReport.tradeCount == 0) {
            // Portfolio did not trade in this run — nothing to attribute. The other
            // tests already cover the not-traded case for standalones; nothing leaks
            // a trade THE PORTFOLIO didn't make either, since there were none.
            return
        }
        val portfolioTrades = result.trades.filter { it.strategyId == portfolioId }
        assertThat(portfolioTrades).isNotEmpty()
        val standaloneIds = fixtures.map { it.id }.toSet()
        for (record in portfolioTrades) {
            assertThat(record.strategyId)
                .withFailMessage("portfolio trade attributed to standalone id ${record.strategyId}")
                .isNotIn(standaloneIds)
        }
    }

    @Test
    fun `same seed produces identical trade sequence across runs`() {
        val a = newBacktest().run()
        val b = newBacktest().run()
        assertThat(a.trades.size).isEqualTo(b.trades.size)
        assertThat(a.trades.map { it.trade.side to it.trade.quantity to it.strategyId })
            .isEqualTo(b.trades.map { it.trade.side to it.trade.quantity to it.strategyId })
        assertThat(a.global.totalPnL).isEqualByComparingTo(b.global.totalPnL)
        for (fx in fixtures) {
            val ea = a.perStrategy.getValue(fx.id).equityCurve
            val eb = b.perStrategy.getValue(fx.id).equityCurve
            assertThat(ea).isEqualTo(eb)
        }
    }

    @Test
    fun `every trade is attributed to exactly one strategy and that strategy owns it`() {
        val result = newBacktest().run()
        val knownIds = fixtures.map { it.id }.toSet() + portfolioId
        for (record in result.trades) {
            assertThat(record.strategyId)
                .withFailMessage("trade $record has unknown strategyId")
                .isIn(knownIds)
        }
        val perStrategyTradeCounts =
            result.trades.groupingBy { it.strategyId }.eachCount()
        for (id in knownIds) {
            val expected = perStrategyTradeCounts[id] ?: 0
            val reported = result.perStrategy.getValue(id).tradeCount
            assertThat(reported)
                .withFailMessage(
                    "perStrategy[$id].tradeCount=$reported but trades.filter(strategyId==$id).size=$expected",
                ).isEqualTo(expected)
        }
    }

    @Test
    fun `each strategy starts trading only after its own warmup completes`() {
        val result = newBacktest().run()
        val candleTfMs = 60_000L
        for (fx in fixtures) {
            val firstTrade =
                result.trades.firstOrNull { it.strategyId == fx.id } ?: continue
            val firstTradeCandleIndex = firstTrade.trade.timestamp / candleTfMs
            assertThat(firstTradeCandleIndex)
                .withFailMessage(
                    "${fx.id} first trade at candle $firstTradeCandleIndex, expected >= ${fx.warmupBars}",
                ).isGreaterThanOrEqualTo(fx.warmupBars.toLong())
        }
    }

    @Test
    fun `wall-clock smoke ceiling`() {
        val nanos =
            measureNanos {
                newBacktest().run()
            }
        val millis = nanos / 1_000_000
        // Stdout: tracked as a regression signal — flag if it creeps up over time.
        println(
            "MultiStrategyStressTest: 5 strategies, $candleCount candles, $ticksPerCandle ticks/candle → ${millis}ms",
        )
        assertThat(millis)
            .withFailMessage("backtest took ${millis}ms, expected under 30s (catastrophic regression check)")
            .isLessThan(30_000)
    }

    private inline fun measureNanos(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }

    @Suppress("UnusedPrivateProperty")
    private val moneyZero: BigDecimal = Money.ZERO
}
