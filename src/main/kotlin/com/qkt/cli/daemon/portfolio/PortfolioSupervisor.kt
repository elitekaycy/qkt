package com.qkt.cli.daemon.portfolio

import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.Value
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.pnl.StrategyPnLView
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.risk.NoOpRiskView
import com.qkt.strategy.Mode
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class PortfolioSupervisor(
    val ast: PortfolioAst,
    val children: List<ChildHandle>,
    val marketSource: MarketSource?,
) {
    private val log = LoggerFactory.getLogger(PortfolioSupervisor::class.java)
    private val runFlag = AtomicBoolean(false)
    private var thread: Thread? = null

    val running: Boolean get() = runFlag.get()

    fun start() {
        if (!runFlag.compareAndSet(false, true)) return
        applyAlwaysRunRules()
        if (marketSource == null) return
        thread =
            Thread({
                org.slf4j.MDC.put("strategy", ast.name)
                try {
                    tickLoop()
                } finally {
                    org.slf4j.MDC.remove("strategy")
                }
            }, "qkt-portfolio-supervisor-${ast.name}").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        if (!runFlag.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(5000)
        thread = null
    }

    internal fun applyDesired(desired: Map<String, Boolean>) {
        for (child in children) {
            val want = desired[child.alias] ?: false
            val have = child.gateActive.get()
            if (want == have) continue
            if (want) {
                child.gateActive.set(true)
                log.info("${child.alias} activated")
            } else {
                child.gateActive.set(false)
                log.info("${child.alias} deactivated, hold=${child.hold}")
                if (!child.hold) child.flatten()
            }
        }
    }

    private fun applyAlwaysRunRules() {
        val desired = mutableMapOf<String, Boolean>()
        for (alias in children.map { it.alias }) desired[alias] = false
        for (rule in ast.rules) {
            if (rule is AlwaysRun) desired[rule.alias] = true
        }
        applyDesired(desired)
    }

    private val streamMap: Map<String, HubKey> =
        ast.streams.associate { stream ->
            stream.alias to HubKey(stream.broker, stream.symbol, stream.timeframe)
        }

    private val whenRules: List<Pair<WhenRun, com.qkt.dsl.compile.CompiledExpr>> =
        ast.rules.filterIsInstance<WhenRun>().map { rule ->
            rule to ExprCompiler().compile(rule.cond)
        }

    private fun supervisorStrategyContext(): StrategyContext =
        StrategyContext(
            strategyId = ast.name,
            mode = Mode.LIVE,
            clock = SystemClock(),
            calendar = TradingCalendar.crypto(),
            source = EmptySource,
            positions = EmptyPositions,
            pnl = EmptyPnL,
            risk = NoOpRiskView(),
        )

    internal fun onCandle(candle: Candle) {
        val desired = mutableMapOf<String, Boolean>()
        for (alias in children.map { it.alias }) desired[alias] = false
        for (rule in ast.rules) {
            if (rule is AlwaysRun) desired[rule.alias] = true
        }
        if (whenRules.isNotEmpty()) {
            val ctx =
                EvalContext(
                    candle = candle,
                    streams = streamMap,
                    lets = emptyMap(),
                    strategyContext = supervisorStrategyContext(),
                )
            for ((rule, compiled) in whenRules) {
                val result = compiled.evaluate(ctx)
                if (result is Value.Bool && result.v) desired[rule.alias] = true
            }
        }
        applyDesired(desired)
    }

    private fun tickLoop() {
        val source = marketSource ?: return
        val symbols = ast.streams.map { it.symbol }.distinct()
        if (symbols.isEmpty()) return

        val window = TimeWindow.parse(ast.streams.first().timeframe)
        val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
        CandleAggregator(bus, window)
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }

        val feed = source.liveTicks(symbols)
        try {
            while (runFlag.get()) {
                val tick = feed.next() ?: break
                bus.publish(TickEvent(tick))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { feed.close() }
        }
    }

    private object EmptySource : MarketSource {
        override val name: String = "Supervisor"
        override val capabilities: Set<MarketSourceCapability> = emptySet()

        override fun supports(symbol: String): Boolean = false
    }

    private object EmptyPositions : StrategyPositionView {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions(): Map<String, Position> = emptyMap()
    }

    private object EmptyPnL : StrategyPnLView {
        override fun realized(): BigDecimal = BigDecimal.ZERO

        override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

        override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

        override fun total(): BigDecimal = BigDecimal.ZERO

        override fun equity(): BigDecimal = BigDecimal.ZERO

        override fun balance(): BigDecimal = BigDecimal.ZERO
    }
}
