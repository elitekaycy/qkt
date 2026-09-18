package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.AfterEach

abstract class StrategyRegistryFixture {
    protected val opened = mutableListOf<ObservabilityServer>()

    @AfterEach
    fun cleanup() {
        for (s in opened) runCatching { s.close() }
        opened.clear()
    }

    protected fun fakeFactory(stateDir: StateDir): StrategyHandle.Factory =
        StrategyHandle.Factory { name, _, _ ->
            val ring = EventRing(capacity = 16)
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

                    override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> =
                        emptyList()

                    override fun flatten() = Unit
                }
            val server =
                ObservabilityServer(
                    ring = ring,
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
                    running = { running.get() },
                    onStop = { running.set(false) },
                    bind = "127.0.0.1",
                    port = 0,
                ).also { it.start() }
            opened.add(server)
            val ast =
                StrategyAst(
                    name = name,
                    version = 1,
                    streams =
                        listOf(
                            StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
                        ),
                    constants = emptyList(),
                    lets = emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                )
            StrategyHandle(
                name = name,
                ast = ast,
                live = live,
                observability = server,
                ring = ring,
                logFile = stateDir.logFile(name),
                startedAt = Instant.now(),
            )
        }

    protected fun recordingFactory(
        stateDir: StateDir,
        events: MutableList<String>,
    ): StrategyHandle.Factory =
        StrategyHandle.Factory { name, file, _ ->
            if (file.fileName.toString().contains("bad")) error("replacement failed")
            val ring = EventRing(capacity = 16)
            val running = AtomicBoolean(true)
            val live =
                object : LiveSessionHandle {
                    override val running: Boolean get() = running.get()
                    override val droppedTicks: Long = 0L

                    override fun requestStop() {
                        events.add("request:$name:${file.fileName}")
                        running.set(false)
                    }

                    override fun stop() {
                        events.add("stop:$name:${file.fileName}")
                        running.set(false)
                    }

                    override fun awaitTermination(timeout: Duration): Boolean {
                        events.add("await:$name:${file.fileName}")
                        return true
                    }

                    override fun recentTrades(): List<Trade> = emptyList()

                    override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> =
                        emptyList()

                    override fun halt(reason: String) {
                        events.add("halt:$name:${file.fileName}:$reason")
                    }

                    override fun halt(
                        reason: String,
                        scope: com.qkt.risk.HaltScope,
                    ) {
                        events.add("halt:$name:${file.fileName}:$reason:${scope.name}")
                    }

                    override fun resume() {
                        events.add("resume:$name:${file.fileName}")
                    }

                    override fun flatten() = Unit
                }
            val server =
                ObservabilityServer(
                    ring = ring,
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
                    running = { running.get() },
                    onStop = { running.set(false) },
                    bind = "127.0.0.1",
                    port = 0,
                ).also { it.start() }
            opened.add(server)
            val ast =
                StrategyAst(
                    name = name,
                    version = 1,
                    streams =
                        listOf(
                            StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
                        ),
                    constants = emptyList(),
                    lets = emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                )
            StrategyHandle(
                name = name,
                ast = ast,
                live = live,
                observability = server,
                ring = ring,
                logFile = stateDir.logFile(name),
                startedAt = Instant.now(),
                sourceFile = file,
            )
        }
}
