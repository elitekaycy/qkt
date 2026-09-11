package com.qkt.parity

import com.qkt.cli.Config
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.common.FixedClock
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A book-level exposure cap must hold across children that enter inside ONE book-risk sample.
 *
 * Found live on 2026-09-10 against the local Exness demo gateway: a three-child book under a
 * max_gross_exposure cap of 1,998.53 opened all three 0.01-lot entries (about 3,239 of notional)
 * with no refusal. The children submitted within 197 ms -- two of them 1 ms apart -- and every
 * check ran before any sibling's fill had been folded into book state.
 *
 * The mechanism: [com.qkt.risk.rules.BookExposureLimit] checks each order against
 * `BookRiskController.state()`, and the controller's gross exposure changes only when
 * PortfolioSupervisor samples filled positions, once per `riskIntervalMs` (1,000 ms by default).
 * Nothing reserves an approved-but-unsampled order, so every entry inside one sample window sees the
 * same stale exposure. The backtest samples on its single-threaded bus as fills land, which is why
 * it refused the same book correctly -- a backtest/live divergence in a risk control.
 *
 * Two children each order 2 units at 100 (notional 200) against a cap of 0.3 x 1,000 = 300: one
 * entry fits, two do not. A 60 s risk interval guarantees both entries land inside one sample.
 */
class PortfolioBookRiskRaceTest {
    @Test
    fun `book gross cap holds when both children enter at the same moment`(
        @TempDir tmp: Path,
    ) {
        val result = runBook(tmp, concurrent = true)
        assertThat(result.fills).describedAs(result.describe()).isEqualTo(1)
        assertThat(result.journals).describedAs(result.describe()).contains("book gross exposure")
    }

    @Test
    fun `book gross cap holds for sequential entries between two risk samples`(
        @TempDir tmp: Path,
    ) {
        val result = runBook(tmp, concurrent = false)
        assertThat(result.fills).describedAs(result.describe()).isEqualTo(1)
        assertThat(result.journals).describedAs(result.describe()).contains("book gross exposure")
    }

    private data class Outcome(
        val fillsA: Int,
        val fillsB: Int,
        val journals: String,
    ) {
        val fills: Int get() = fillsA + fillsB

        fun describe(): String =
            "fills a=$fillsA b=$fillsB (cap admits exactly one 200-notional entry of 300); " +
                "journal risk-rejected lines=${Regex("risk-rejected").findAll(journals).count()}"
    }

    private fun runBook(
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

    private fun journals(root: Path): String {
        if (!Files.exists(root)) return ""
        return Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .map { runCatching { Files.readString(it) }.getOrDefault("") }
                .toList()
                .joinToString("\n")
        }
    }

    private fun writeBook(tmp: Path): Path {
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

    private fun unitRegistry(): InstrumentRegistry =
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

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10L)
        assertThat(condition()).isTrue()
    }

    private class ManualSource(
        private val feed: ManualFeed,
    ) : MarketSource {
        override val name = "portfolio-book-risk-race"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed
    }

    private class ManualFeed : TickFeed {
        private val queue = LinkedBlockingQueue<Tick>()
        private val readCalls = AtomicInteger()

        fun offer(tick: Tick) {
            queue.put(tick)
        }

        fun awaitReadCalls(
            expected: Int,
            timeoutMs: Long = 2_000L,
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (readCalls.get() < expected && System.currentTimeMillis() < deadline) Thread.sleep(5L)
            return readCalls.get() >= expected
        }

        override fun next(): Tick? {
            readCalls.incrementAndGet()
            val tick = queue.take()
            return tick.takeUnless { it.symbol == CLOSE_SYMBOL }
        }

        override fun close() {
            queue.offer(Tick(CLOSE_SYMBOL, BigDecimal.ONE, Long.MIN_VALUE))
        }

        private companion object {
            const val CLOSE_SYMBOL = "__CLOSE__"
        }
    }
}
