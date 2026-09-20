package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.common.FixedClock
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.dsl.portfolio.capitalAllocations
import com.qkt.marketdata.Tick
import com.qkt.parity.PortfolioDeployerParityFixtures.awaitCondition
import com.qkt.parity.PortfolioDeployerParityFixtures.unitRegistry
import com.qkt.parity.PortfolioParityFeeds.HeldSource
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.BookRiskConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortfolioDeployerAllocationParityTest {
    @Test
    fun `real deployer matches backtest for CAPITAL WEIGHT OF BOOK and book risk allocation`(
        @TempDir tmp: Path,
    ) {
        val portfolioFile = writePortfolio(tmp)
        val firstTs = 1_700_000_000_000L
        val ticks =
            listOf("A", "B")
                .flatMap { symbol ->
                    (0..3).map { index ->
                        Tick("BACKTEST:$symbol", BigDecimal("100"), firstTs + index * 60_000L)
                    }
                }.sortedBy { it.timestamp }
        val registry = unitRegistry()
        val bookRisk = BookRiskConfig(allocation = Allocation())
        val deployedCompiled = PortfolioLoader.load(portfolioFile)
        val sources = mutableListOf<HeldSource>()
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { requested ->
                    HeldSource(ticks.filter { it.symbol in requested }).also(sources::add)
                },
                instrumentRegistry = registry,
                bookRiskConfig = bookRisk,
                maxDailyLoss = BigDecimal.ZERO,
                clock = FixedClock(firstTs),
                riskIntervalMs = 10_000L,
            )
        val record = deployer.deploy("parity_book", deployedCompiled)
        try {
            assertThat(sources).hasSize(record.children.size + 1)
            sources.last().release()
            awaitCondition {
                record.children.all { it.childMeta?.gateActive?.get() == true }
            }
            for ((child, source) in record.children.zip(sources.dropLast(1))) {
                source.release()
                awaitCondition { child.live.recentTrades().isNotEmpty() }
            }

            val replayCompiled = PortfolioLoader.load(portfolioFile)
            val allocations = capitalAllocations(replayCompiled.ast)
            val backtest =
                Backtest(
                    strategies = replayCompiled.children.map { it.strategyId to it.compiled },
                    ticks = ticks,
                    candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                    initialTimestamp = firstTs,
                    startingBalance = replayCompiled.ast.capital!!,
                    startingBalances =
                        replayCompiled.children.associate { child ->
                            child.strategyId to allocations.getValue(child.alias)
                        },
                    bookCapital = replayCompiled.ast.capital,
                    instruments = registry,
                    tradedSymbols = replayCompiled.children.flatMap { it.symbols }.distinct(),
                    bookRiskConfig = bookRisk,
                ).run()

            val expectedByChild = backtest.trades.groupBy { it.strategyId }
            assertThat(expectedByChild.keys)
                .containsExactlyInAnyOrderElementsOf(replayCompiled.children.map { it.strategyId })
            for (child in record.children) {
                val liveTrades = child.live.recentTrades()
                val expected = expectedByChild.getValue(child.ast.name.replace("child_", "parity_book:"))
                assertThat(liveTrades).hasSize(expected.size)
                for (index in expected.indices) {
                    assertThat(liveTrades[index].symbol).isEqualTo(expected[index].trade.symbol)
                    assertThat(liveTrades[index].side).isEqualTo(expected[index].trade.side)
                    assertThat(liveTrades[index].quantity).isEqualByComparingTo(expected[index].trade.quantity)
                    assertThat(liveTrades[index].price).isEqualByComparingTo(expected[index].trade.price)
                }
            }
        } finally {
            record.supervisor.stop()
            for (child in record.children) child.close()
        }
    }

    private fun writePortfolio(tmp: Path): Path {
        for (alias in listOf("a", "b")) {
            Files.writeString(
                tmp.resolve("$alias.qkt"),
                """
                STRATEGY child_$alias VERSION 1
                SYMBOLS
                    x = BACKTEST:${alias.uppercase()} EVERY 1m
                RULES
                    WHEN x.close > 0 AND POSITION.x = 0
                    THEN BUY x SIZING 1 PCT RISK OF BOOK BRACKET { STOP LOSS PCT 10, TAKE PROFIT AT 1000 }
                """.trimIndent(),
            )
        }
        return tmp.resolve("book.qkt").also { path ->
            Files.writeString(
                path,
                """
                PORTFOLIO parity_book VERSION 1 CAPITAL 10000
                SYMBOLS
                    mkt = BACKTEST:A EVERY 1m
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.6
                    RUN b WEIGHT 0.4
                """.trimIndent(),
            )
        }
    }
}
