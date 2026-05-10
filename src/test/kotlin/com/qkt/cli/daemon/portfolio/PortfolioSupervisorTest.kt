package com.qkt.cli.daemon.portfolio

import com.qkt.app.LiveSessionHandle
import com.qkt.app.OrderManager
import com.qkt.cli.daemon.StrategyHandle
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class PortfolioSupervisorTest {
    private val opened = mutableListOf<ObservabilityServer>()

    @AfterEach
    fun cleanup() {
        for (s in opened) runCatching { s.close() }
        opened.clear()
    }

    @Test
    fun `start and stop toggles running`() {
        val ast =
            PortfolioAst(
                name = "p",
                version = 1,
                streams = emptyList(),
                imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
                rules = listOf(AlwaysRun("a")),
            )
        val supervisor =
            PortfolioSupervisor(
                ast = ast,
                children = emptyList(),
                marketSource = null,
            )
        assertThat(supervisor.running).isFalse
        supervisor.start()
        assertThat(supervisor.running).isTrue
        supervisor.stop()
        assertThat(supervisor.running).isFalse
    }

    @Test
    fun `AlwaysRun activates child immediately on start`() {
        val a = stubChildHandle(parent = "p", alias = "a", hold = false)
        val ast =
            PortfolioAst(
                name = "p",
                version = 1,
                streams = emptyList(),
                imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
                rules = listOf(AlwaysRun("a")),
            )
        val supervisor = PortfolioSupervisor(ast, listOf(a), marketSource = null)
        supervisor.start()
        assertThat(a.gateActive.get()).isTrue
        supervisor.stop()
    }

    @Test
    fun `deactivate without HOLD calls flatten`() {
        val flattened = AtomicBoolean(false)
        val a = stubChildHandle(parent = "p", alias = "a", hold = false, flattenSpy = { flattened.set(true) })
        val ast =
            PortfolioAst(
                name = "p",
                version = 1,
                streams = emptyList(),
                imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
                rules = listOf(AlwaysRun("a")),
            )
        val supervisor = PortfolioSupervisor(ast, listOf(a), marketSource = null)
        supervisor.start()
        assertThat(a.gateActive.get()).isTrue

        supervisor.applyDesired(mapOf("a" to false))
        assertThat(a.gateActive.get()).isFalse
        assertThat(flattened.get()).isTrue
        supervisor.stop()
    }

    @Test
    fun `deactivate with HOLD does not call flatten`() {
        val flattened = AtomicBoolean(false)
        val a = stubChildHandle(parent = "p", alias = "a", hold = true, flattenSpy = { flattened.set(true) })
        val ast =
            PortfolioAst(
                name = "p",
                version = 1,
                streams = emptyList(),
                imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
                rules = listOf(AlwaysRun("a")),
            )
        val supervisor = PortfolioSupervisor(ast, listOf(a), marketSource = null)
        supervisor.start()
        supervisor.applyDesired(mapOf("a" to false))
        assertThat(a.gateActive.get()).isFalse
        assertThat(flattened.get()).isFalse
        supervisor.stop()
    }

    private fun stubChildHandle(
        parent: String,
        alias: String,
        hold: Boolean,
        flattenSpy: () -> Unit = {},
    ): ChildHandle {
        val running = AtomicBoolean(true)
        val live =
            object : LiveSessionHandle {
                override val running: Boolean get() = running.get()
                override val droppedTicks: Long = 0L

                override fun stop() {
                    running.set(false)
                }

                override fun awaitTermination(timeout: Duration): Boolean = true

                override fun recentTrades(): List<Trade> = emptyList()

                override fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo> = emptyList()

                override fun flatten() = flattenSpy()
            }
        val ring = EventRing(capacity = 16)
        val server =
            ObservabilityServer(
                ring = ring,
                statusProvider = {
                    StatusSnapshot(
                        strategy = "$parent/$alias",
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
                running = { running.get() },
                onStop = { running.set(false) },
                bind = "127.0.0.1",
                port = 0,
            ).also { it.start() }
        opened.add(server)
        val handle =
            StrategyHandle(
                name = "$parent/$alias",
                ast =
                    StrategyAst(
                        name = alias,
                        version = 1,
                        streams = listOf(StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")),
                        constants = emptyList(),
                        lets = emptyList(),
                        defaults = null,
                        rules = emptyList<WhenThen>(),
                    ),
                live = live,
                observability = server,
                ring = ring,
                logFile = java.nio.file.Files.createTempFile("child-", ".log"),
                startedAt = Instant.now(),
            )
        return ChildHandle(parent = parent, alias = alias, hold = hold, handle = handle)
    }
}
