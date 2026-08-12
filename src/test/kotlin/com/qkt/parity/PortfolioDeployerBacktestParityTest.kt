package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.cli.Config
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.common.FixedClock
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.dsl.portfolio.capitalAllocations
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.BookRiskConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortfolioDeployerBacktestParityTest {
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

    private class HeldSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        private val released = CountDownLatch(1)

        override val name = "portfolio-parity"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = HeldFeed(ticks, released)

        fun release() {
            released.countDown()
        }
    }

    private class HeldFeed(
        private val ticks: List<Tick>,
        private val released: CountDownLatch,
    ) : TickFeed {
        private val index = AtomicInteger()
        private val closed = CountDownLatch(1)

        override fun next(): Tick? {
            while (released.count > 0L && closed.count > 0L) {
                released.await(10L, TimeUnit.MILLISECONDS)
            }
            if (closed.count == 0L) return null
            val next = index.getAndIncrement()
            if (next < ticks.size) return ticks[next]
            closed.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class ManualSource(
        private val feed: ManualFeed,
    ) : MarketSource {
        override val name = "portfolio-aggregate-parity"
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
            while (readCalls.get() < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L)
            }
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

    private fun awaitJournal(strategyDir: Path): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            val journal =
                if (Files.isDirectory(strategyDir)) {
                    Files.list(strategyDir).use { files -> files.findFirst().orElse(null) }
                } else {
                    null
                }
            if (journal != null) {
                val body = Files.readString(journal)
                if (body.contains("\"kind\":\"risk-rejected\"")) return body
            }
            Thread.sleep(10L)
        }
        error("risk rejection journal was not written for $strategyDir")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertThat(condition()).isTrue()
    }
}
