package com.qkt.cli.daemon.limits

import com.qkt.cli.daemon.DaemonHardLimitGuard
import com.qkt.cli.daemon.GuardedSession
import com.qkt.cli.daemon.PortfolioRecord
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.common.FixedClock
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.persistence.FileStatePersistor
import com.qkt.risk.DaemonRiskScope
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

internal const val DAY_MS = 86_400_000L

/**
 * A daemon's risk wiring over real LiveSessions and the paper venue, fed by hand: what `qkt daemon` builds from
 * the config, without the control plane. [bar] closes one 1-minute candle of a symbol, advancing the shared clock.
 * Strategies buy at a close of 100, and sell at a close under 99 (`loser`) or of 150 (`holder`).
 */
internal class SharedLimitsRig(
    private val tmp: Path,
    val clock: FixedClock,
    private val maxDailyLoss: String = "8",
    hardDailyLoss: String? = null,
    private val maxDrawdownPct: BigDecimal? = null,
) {
    private val feeds = CopyOnWriteArrayList<Pair<List<String>, ManualFeed>>()
    private val persistor = FileStatePersistor(tmp.resolve("state-root"))
    val scope = DaemonRiskScope(clock, persistor, hardDailyLoss?.let(::BigDecimal)).also { it.restore() }
    private val provider: (List<String>) -> MarketSource = { symbols ->
        val feed = ManualFeed()
        feeds.add(symbols to feed)
        ManualSource(feed)
    }
    val registry =
        StrategyRegistry(
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = provider,
                instrumentRegistry = UnitRegistry,
                maxDailyLoss = BigDecimal(maxDailyLoss),
                daemonRisk = scope,
                persistor = persistor,
                marginFloorPct = BigDecimal.ZERO,
            ),
        )
    private val deployer =
        PortfolioDeployer(
            stateDir = StateDir.resolve(tmp.resolve("state").toString()),
            marketSourceProvider = provider,
            instrumentRegistry = UnitRegistry,
            maxDailyLoss = BigDecimal(maxDailyLoss),
            maxDrawdownPct = maxDrawdownPct,
            riskIntervalMs = 5L,
            marginFloorPct = BigDecimal.ZERO,
            clock = clock,
            persistor = persistor,
            daemonRisk = scope,
        )
    val guard = DaemonHardLimitGuard(scope, { registry.list().map { GuardedSession(it.name, it.live) } })

    init {
        scope.composition.members {
            registry.list().any { it.childMeta == null } to registry.listPortfolios().isNotEmpty()
        }
    }

    fun standalone(
        name: String,
        symbol: String,
        loser: Boolean = true,
    ): StrategyHandle = registry.deploy(name, strategyFile(name, symbol, loser))

    /** One book of `children` (alias to symbol, loser): `a` and `b` trade BACKTEST:<alias>. */
    fun book(
        name: String,
        vararg children: Triple<String, String, Boolean>,
    ): PortfolioRecord {
        val imports =
            children.joinToString("\n") { (alias, symbol, loser) ->
                val file = strategyFile("${name}_$alias", symbol, loser)
                "IMPORT '${file.fileName}' AS $alias"
            }
        val rules = children.joinToString("\n") { "    RUN ${it.first} WEIGHT 0.5" }
        val first = children.first().second
        val file = tmp.resolve("$name.qkt")
        Files.writeString(
            file,
            "PORTFOLIO $name VERSION 1 CAPITAL 100000\nSYMBOLS\n    x = $first EVERY 1m\n$imports\nRULES\n$rules\n",
        )
        return deployer.deploy(name, PortfolioLoader.load(file)).also(registry::registerPortfolio).also { record ->
            await("children active") { record.children.all { it.childMeta?.gateActive?.get() == true } }
        }
    }

    private fun strategyFile(
        name: String,
        symbol: String,
        loser: Boolean,
    ): Path {
        val exit = if (loser) "x.close < 99" else "x.close = 150"
        val file = tmp.resolve("$name.qkt")
        Files.writeString(
            file,
            "STRATEGY $name VERSION 1\nSYMBOLS\n    x = $symbol EVERY 1m\nRULES\n" +
                "    WHEN x.close = 100 THEN BUY x SIZING 1\n    WHEN $exit THEN SELL x SIZING 1\n",
        )
        return file
    }

    /** Closes one candle of [symbol] at [price]: a tick in this minute, then the tick that opens the next. */
    fun bar(
        symbol: String,
        price: String,
    ) {
        for (step in 0..1) {
            clock.time += 60_001L
            val tick = Tick(symbol, BigDecimal(price), clock.time)
            feeds.filter { symbol in it.first }.forEach { it.second.offer(tick) }
        }
        settle()
    }

    /** A fresh price for [symbol] at the current time, inside the open candle. */
    fun mark(
        symbol: String,
        price: String,
    ) {
        val tick = Tick(symbol, BigDecimal(price), clock.time)
        feeds.filter { symbol in it.first }.forEach { it.second.offer(tick) }
        settle()
    }

    /** Lets the engines take what they were offered. Enough to make a wrong trade show up, not a proof of none. */
    fun settle() = Thread.sleep(150L)

    /** Whichever session's trades show [count] fills. */
    fun awaitTrades(
        handle: StrategyHandle,
        count: Int,
    ) = await("${handle.name} reaches $count fills") { handle.live.recentTrades().size >= count }

    fun fills(handle: StrategyHandle): Int = handle.live.recentTrades().size

    fun legs(handle: StrategyHandle): Int = handle.live.limitReadingOrFail().openLegs

    fun close() {
        registry.stopAll(java.time.Duration.ofSeconds(5))
    }

    private object UnitRegistry : InstrumentRegistry {
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

    private class ManualSource(
        private val feed: TickFeed,
    ) : MarketSource {
        override val name = "manual"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed
    }

    private class ManualFeed : TickFeed {
        private val queue = LinkedBlockingQueue<Tick>()

        fun offer(tick: Tick) = queue.put(tick)

        override fun next(): Tick? = queue.take().takeUnless { it.symbol == CLOSE }

        override fun close() {
            queue.offer(Tick(CLOSE, BigDecimal.ONE, Long.MIN_VALUE))
        }
    }

    private companion object {
        const val CLOSE = "__CLOSE__"
    }
}

internal fun com.qkt.app.LiveSessionHandle.limitReadingOrFail() =
    limitReading() ?: error("this session reports no limit reading")

internal fun await(
    what: String,
    timeoutMs: Long = 5_000L,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "timed out waiting: $what" }
        Thread.sleep(10L)
    }
}
