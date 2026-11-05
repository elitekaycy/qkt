package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.bus.EventBus
import com.qkt.cli.daemon.portfolio.ChildHandle
import com.qkt.cli.daemon.portfolio.PortfolioRiskAggregator
import com.qkt.cli.daemon.portfolio.PortfolioSupervisor
import com.qkt.cli.daemon.portfolio.SessionRiskTarget
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.FakePnL
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import com.qkt.risk.TestClock
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** A deployed portfolio book with one child, whose book halt and child halts behave like the live risk state. */
abstract class HaltedBookFixture : StrategyRegistryFixture() {
    /** A child session's halt state, escalating like [RiskState.halt]. */
    protected class HaltableLive : LiveSessionHandle {
        var reason: String? = null
        var scope: HaltScope? = null
        override val running: Boolean = true
        override val droppedTicks: Long = 0L

        override fun stop() = Unit

        override fun awaitTermination(timeout: Duration): Boolean = true

        override fun recentTrades(): List<Trade> = emptyList()

        override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> = emptyList()

        override fun flatten() = Unit

        override fun halt(reason: String) = halt(reason, HaltScope.PERSISTENT)

        override fun halt(
            reason: String,
            scope: HaltScope,
        ) {
            if (this.reason == null || (this.scope != HaltScope.PERSISTENT && scope == HaltScope.PERSISTENT)) {
                this.reason = reason
                this.scope = scope
            }
        }

        override fun resume() {
            reason = null
            scope = null
        }

        override fun isHalted(): Boolean = reason != null

        override fun haltReason(): String? = reason

        override fun haltScope(): HaltScope? = scope
    }

    protected val reason =
        "portfolio risk evaluation failed 3 consecutive times: " +
            "live engine did not produce a consistent snapshot within the timeout"
    protected val clock = TestClock(1_000L)
    protected val child = HaltableLive()
    protected val bookRisk =
        RiskState(
            FakePnL(BigDecimal.ZERO, BigDecimal.ZERO),
            StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
            clock,
            EventBus(clock, MonotonicSequenceGenerator()),
            BigDecimal("100000"),
            DailyDrawdownBasis.BALANCE,
        )
    private val aggregator = PortfolioRiskAggregator(listOf(SessionRiskTarget(child)), bookRisk, emptyList(), clock)

    /** Registers book `soak_k56` with child `soak_k56/coin`. */
    protected fun registryWithBook(stateDir: StateDir): StrategyRegistry {
        val registry = StrategyRegistry(fakeFactory(stateDir))
        val handle = childHandle(stateDir, "soak_k56", "coin")
        val meta = handle.childMeta ?: error("missing child metadata")
        val supervisor =
            PortfolioSupervisor(
                ast =
                    PortfolioAst(
                        name = "soak_k56",
                        version = 1,
                        streams = emptyList(),
                        imports = listOf(ImportClause("coin.qkt", "coin")),
                        rules = listOf(AlwaysRun("coin")),
                    ),
                children = listOf(ChildHandle("soak_k56", "coin", false, handle, meta.gateActive, meta.operatorStop)),
                marketSource = null,
                riskAggregator = aggregator,
            )
        registry.registerPortfolio(
            PortfolioRecord("soak_k56", 1, supervisor, listOf(handle), stateDir.logFile("soak_k56"), Instant.now()),
        )
        return registry
    }

    /** Latches the book's persistent halt as a restart restores it, and lets the book hold its child. */
    protected fun haltBook() {
        bookRisk.halt(reason, HaltScope.PERSISTENT)
        aggregator.evaluate()
    }

    private fun childHandle(
        stateDir: StateDir,
        parent: String,
        alias: String,
    ): StrategyHandle {
        val name = "$parent/$alias"
        val server =
            ObservabilityServer(
                ring = EventRing(capacity = 16),
                statusProvider = {
                    StatusSnapshot(
                        strategy = name,
                        version = 1,
                        uptimeMs = 0L,
                        startedAt = Instant.EPOCH.toString(),
                        equity = BigDecimal.ZERO,
                        balance = BigDecimal.ZERO,
                        realized = BigDecimal.ZERO,
                        unrealized = BigDecimal.ZERO,
                        positions = emptyList<PositionDto>(),
                        lastTrade = null,
                    )
                },
                running = { true },
                onStop = {},
                bind = "127.0.0.1",
                port = 0,
            ).also { it.start() }
        opened.add(server)
        val stream = StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")
        return StrategyHandle(
            name = name,
            ast =
                StrategyAst(
                    name,
                    1,
                    listOf(stream),
                    emptyList(),
                    emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                ),
            live = child,
            observability = server,
            ring = EventRing(capacity = 16),
            logFile = stateDir.logFile(name),
            startedAt = Instant.now(),
            childMeta =
                StrategyHandle.ChildMeta(parent, alias, false, AtomicBoolean(true), AtomicBoolean(false)),
        )
    }
}
