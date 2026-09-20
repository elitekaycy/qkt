package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.cli.Config
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.common.FixedClock
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.parity.PortfolioDeployerParityFixtures.awaitCondition
import com.qkt.parity.PortfolioDeployerParityFixtures.awaitJournal
import com.qkt.parity.PortfolioDeployerParityFixtures.unitRegistry
import com.qkt.parity.PortfolioParityFeeds.ManualFeed
import com.qkt.parity.PortfolioParityFeeds.ManualSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortfolioDeployerBookExposureParityTest {
    @Test
    fun `real deployer enforces loaded aggregate book exposure like backtest`(
        @TempDir tmp: Path,
    ) {
        val portfolioFile = writeFixedPortfolio(tmp)
        val configPath = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            configPath,
            """
            book_risk:
              capital: "1000"
              limits:
                max_gross_exposure: "0.3"
            """.trimIndent(),
        )
        val bookRisk = Config.load(configPath).bookRisk!!
        val compiled = PortfolioLoader.load(portfolioFile)
        val feeds = List(3) { ManualFeed() }
        val sourceCalls = AtomicInteger()
        val journalRoot = tmp.resolve("journals")
        val firstTs = 1_700_000_000_000L
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = {
                    ManualSource(feeds[sourceCalls.getAndIncrement()])
                },
                instrumentRegistry = unitRegistry(),
                bookRiskConfig = bookRisk,
                maxDailyLoss = BigDecimal.ZERO,
                marginFloorPct = BigDecimal.ZERO,
                clock = FixedClock(firstTs),
                riskIntervalMs = 10L,
                journalRoot = journalRoot,
            )
        val record = deployer.deploy("aggregate_book", compiled)
        try {
            val (firstFeed, secondFeed, supervisorFeed) = feeds
            val firstLive =
                record.children
                    .first()
                    .live
            val secondLive =
                record.children
                    .last()
                    .live
            supervisorFeed.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs))
            supervisorFeed.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs + 60_001L))
            assertThat(supervisorFeed.awaitReadCalls(3)).isTrue()
            awaitCondition { record.children.all { it.childMeta?.gateActive?.get() == true } }

            firstFeed.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs))
            firstFeed.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs + 60_001L))
            awaitCondition { firstLive.recentTrades().size == 1 }

            supervisorFeed.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs + 120_002L))
            assertThat(supervisorFeed.awaitReadCalls(4)).isTrue()

            secondFeed.offer(Tick("BACKTEST:B", BigDecimal("100"), firstTs + 180_003L))
            secondFeed.offer(Tick("BACKTEST:B", BigDecimal("100"), firstTs + 240_004L))
            val journal = awaitJournal(journalRoot.resolve("aggregate_book:b"))
            val replayCompiled = PortfolioLoader.load(portfolioFile)

            val backtest =
                Backtest(
                    strategies = replayCompiled.children.map { it.strategyId to it.compiled },
                    ticks =
                        listOf(
                            Tick("BACKTEST:A", BigDecimal("100"), firstTs),
                            Tick("BACKTEST:A", BigDecimal("100"), firstTs + 60_001L),
                            Tick("BACKTEST:A", BigDecimal("100"), firstTs + 120_002L),
                            Tick("BACKTEST:B", BigDecimal("100"), firstTs + 180_003L),
                            Tick("BACKTEST:B", BigDecimal("100"), firstTs + 240_004L),
                        ),
                    candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                    initialTimestamp = firstTs,
                    startingBalance = BigDecimal("1000"),
                    bookCapital = BigDecimal("1000"),
                    instruments = unitRegistry(),
                    tradedSymbols = listOf("BACKTEST:A", "BACKTEST:B"),
                    bookRiskConfig = bookRisk,
                ).run()

            assertThat(firstLive.recentTrades()).hasSize(1)
            assertThat(secondLive.recentTrades()).isEmpty()
            assertThat(backtest.trades.map { it.strategyId }).containsExactly("aggregate_book:a")
            assertThat(backtest.rejections).hasSize(1)
            val rejectionReason =
                backtest.rejections
                    .single()
                    .reason
            assertThat(rejectionReason).contains("book gross exposure", "0.3x capital")
            assertThat(journal)
                .contains("\"kind\":\"risk-rejected\"")
                .contains(rejectionReason)
        } finally {
            record.supervisor.stop()
            for (child in record.children) child.close()
        }
    }

    private fun writeFixedPortfolio(tmp: Path): Path {
        for (alias in listOf("a", "b")) {
            Files.writeString(
                tmp.resolve("fixed-$alias.qkt"),
                """
                STRATEGY fixed_$alias VERSION 1
                SYMBOLS x = BACKTEST:${alias.uppercase()} EVERY 1m
                RULES
                    WHEN x.close > 0 AND POSITION.x = 0
                    THEN BUY x SIZING 2
                """.trimIndent(),
            )
        }
        return tmp.resolve("aggregate-book.qkt").also { path ->
            Files.writeString(
                path,
                """
                PORTFOLIO aggregate_book VERSION 1 CAPITAL 1000
                SYMBOLS mkt = BACKTEST:A EVERY 1m
                IMPORT 'fixed-a.qkt' AS a
                IMPORT 'fixed-b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.5
                    RUN b WEIGHT 0.5
                """.trimIndent(),
            )
        }
    }
}
