package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.daemon.portfolio.ChildHandle
import com.qkt.cli.daemon.portfolio.PortfolioSupervisor
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

abstract class StrategyRegistryPortfolioFixture : StrategyRegistryFixture() {
    protected fun portfolioRecord(
        stateDir: StateDir,
        events: MutableList<String>,
        name: String,
        childAlias: String,
        version: Int,
    ): PortfolioRecord {
        val child = childHandle(stateDir, events, name, childAlias, version)
        val childMeta = child.childMeta ?: error("missing child metadata")
        val wrapper =
            ChildHandle(
                parent = name,
                alias = childAlias,
                hold = false,
                handle = child,
                gateActive = childMeta.gateActive,
                operatorStop = childMeta.operatorStop,
            )
        val supervisor =
            PortfolioSupervisor(
                ast =
                    PortfolioAst(
                        name = name,
                        version = version,
                        streams = emptyList(),
                        imports = listOf(ImportClause("$childAlias.qkt", childAlias)),
                        rules = listOf(AlwaysRun(childAlias)),
                    ),
                children = listOf(wrapper),
                marketSource = null,
            ).also { it.start() }
        return PortfolioRecord(
            name = name,
            version = version,
            supervisor = supervisor,
            children = listOf(child),
            logFile = stateDir.logFile(name),
            startedAt = Instant.now(),
        )
    }

    protected fun childHandle(
        stateDir: StateDir,
        events: MutableList<String>,
        parent: String,
        alias: String,
        version: Int,
    ): StrategyHandle {
        val name = "$parent/$alias"
        val ring = EventRing(capacity = 16)
        val running = AtomicBoolean(true)
        val live =
            object : LiveSessionHandle {
                override val running: Boolean get() = running.get()
                override val droppedTicks: Long = 0L

                override fun stop() {
                    events.add("stop:$name:v$version")
                    running.set(false)
                }

                override fun awaitTermination(timeout: Duration): Boolean = true

                override fun recentTrades(): List<Trade> = emptyList()

                override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> =
                    emptyList()

                override fun halt(reason: String) {
                    events.add("halt:$name:v$version:$reason")
                }

                override fun halt(
                    reason: String,
                    scope: com.qkt.risk.HaltScope,
                ) {
                    events.add("halt:$name:v$version:$reason:${scope.name}")
                }

                override fun resume() {
                    events.add("resume:$name:v$version")
                }

                override fun flatten() = Unit
            }
        val server =
            ObservabilityServer(
                ring = ring,
                statusProvider = {
                    StatusSnapshot(
                        strategy = name,
                        version = version,
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
                running = { running.get() },
                onStop = { running.set(false) },
                bind = "127.0.0.1",
                port = 0,
            ).also { it.start() }
        opened.add(server)
        val meta =
            StrategyHandle.ChildMeta(
                parent = parent,
                alias = alias,
                hold = false,
                gateActive = AtomicBoolean(true),
                operatorStop = AtomicBoolean(false),
            )
        return StrategyHandle(
            name = name,
            ast =
                StrategyAst(
                    name = name,
                    version = version,
                    streams =
                        listOf(
                            StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
                        ),
                    constants = emptyList(),
                    lets = emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                ),
            live = live,
            observability = server,
            ring = ring,
            logFile = stateDir.logFile(name),
            startedAt = Instant.now(),
            childMeta = meta,
        )
    }
}
