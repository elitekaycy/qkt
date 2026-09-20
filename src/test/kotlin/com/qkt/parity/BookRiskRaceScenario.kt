package com.qkt.parity

import com.qkt.cli.Config
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.common.FixedClock
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.parity.BookRiskRaceFeeds.ManualFeed
import com.qkt.parity.BookRiskRaceFeeds.ManualSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat

/**
 * Deploys the two-child race book against a hand-fed supervisor and reports how many entries the book cap let
 * through.
 */
internal object BookRiskRaceScenario {
    data class Outcome(
        val fillsA: Int,
        val fillsB: Int,
        val journals: String,
    ) {
        val fills: Int get() = fillsA + fillsB

        fun describe(): String =
            "fills a=$fillsA b=$fillsB (cap admits exactly one 200-notional entry of 300); " +
                "journal risk-rejected lines=${Regex("risk-rejected").findAll(journals).count()}"
    }

    fun runBook(
        tmp: Path,
        concurrent: Boolean,
    ): Outcome {
        val portfolioFile = writeBook(tmp)
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
                marketSourceProvider = { ManualSource(feeds[sourceCalls.getAndIncrement()]) },
                instrumentRegistry = unitRegistry(),
                bookRiskConfig = bookRisk,
                maxDailyLoss = BigDecimal.ZERO,
                marginFloorPct = BigDecimal.ZERO,
                clock = FixedClock(firstTs),
                // One sample for the whole test: every entry is checked against the same book state.
                riskIntervalMs = 60_000L,
                journalRoot = journalRoot,
            )
        val record = deployer.deploy("race_book", compiled)
        try {
            val (feedA, feedB, supervisorFeed) = feeds
            val liveA = record.children.first().live
            val liveB = record.children.last().live
            supervisorFeed.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs))
            supervisorFeed.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs + 60_001L))
            assertThat(supervisorFeed.awaitReadCalls(3)).isTrue()
            awaitCondition { record.children.all { it.childMeta?.gateActive?.get() == true } }

            if (concurrent) {
                feedA.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs))
                feedB.offer(Tick("BACKTEST:B", BigDecimal("100"), firstTs))
                feedA.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs + 60_001L))
                feedB.offer(Tick("BACKTEST:B", BigDecimal("100"), firstTs + 60_001L))
            } else {
                feedA.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs))
                feedA.offer(Tick("BACKTEST:A", BigDecimal("100"), firstTs + 60_001L))
                awaitCondition { liveA.recentTrades().isNotEmpty() }
                feedB.offer(Tick("BACKTEST:B", BigDecimal("100"), firstTs))
                feedB.offer(Tick("BACKTEST:B", BigDecimal("100"), firstTs + 60_001L))
            }

            // Wait until the SECOND child has decided: either it filled (the breach) or it was
            // refused and journalled. Then collect.
            val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
            while (System.nanoTime() < deadline) {
                val decided =
                    liveA.recentTrades().size + liveB.recentTrades().size >= 2 ||
                        journals(journalRoot).contains("risk-rejected")
                if (decided) break
                Thread.sleep(20L)
            }
            Thread.sleep(300L)
            return Outcome(liveA.recentTrades().size, liveB.recentTrades().size, journals(journalRoot))
        } finally {
            record.supervisor.stop()
            for (child in record.children) child.close()
        }
    }

    fun journals(root: Path): String {
        if (!Files.exists(root)) return ""
        return Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .map { runCatching { Files.readString(it) }.getOrDefault("") }
                .toList()
                .joinToString("\n")
        }
    }

    fun writeBook(tmp: Path): Path {
        for (alias in listOf("a", "b")) {
            Files.writeString(
                tmp.resolve("race-$alias.qkt"),
                """
                STRATEGY race_$alias VERSION 1
                SYMBOLS x = BACKTEST:${alias.uppercase()} EVERY 1m
                RULES
                    WHEN x.close > 0 AND POSITION.x = 0
                    THEN BUY x SIZING 2
                """.trimIndent(),
            )
        }
        return tmp.resolve("race-book.qkt").also { path ->
            Files.writeString(
                path,
                """
                PORTFOLIO race_book VERSION 1 CAPITAL 1000
                SYMBOLS mkt = BACKTEST:A EVERY 1m
                IMPORT 'race-a.qkt' AS a
                IMPORT 'race-b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.5
                    RUN b WEIGHT 0.5
                """.trimIndent(),
            )
        }
    }

    fun unitRegistry(): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = BigDecimal("1000"),
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10L)
        assertThat(condition()).isTrue()
    }
}
