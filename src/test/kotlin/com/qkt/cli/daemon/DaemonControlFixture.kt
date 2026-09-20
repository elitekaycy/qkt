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
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.AfterEach

abstract class DaemonControlFixture {
    protected val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    protected class RecordingHandle(
        initialRunning: Boolean = true,
        private var halted: Boolean = false,
        var flattenResult: com.qkt.app.FlattenResult = com.qkt.app.FlattenResult(verifiedFlat = true),
    ) : LiveSessionHandle {
        val halts = mutableListOf<String>()
        var resumes = 0
        var verifiedFlattens = 0
        private val alive = AtomicBoolean(initialRunning)

        override val running: Boolean get() = alive.get()
        override val droppedTicks: Long = 0L

        override fun stop() {
            alive.set(false)
        }

        override fun awaitTermination(timeout: Duration): Boolean = true

        override fun recentTrades(): List<Trade> = emptyList()

        override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> = emptyList()

        override fun flatten() = Unit

        override fun flattenAndVerify(timeout: Duration): com.qkt.app.FlattenResult {
            verifiedFlattens++
            return flattenResult
        }

        override fun halt(reason: String) {
            halts.add(reason)
            halted = true
        }

        override fun resume() {
            resumes++
            halted = false
        }

        override fun isHalted(): Boolean = halted
    }

    protected fun stubAst(name: String): StrategyAst =
        StrategyAst(
            name = name,
            version = 1,
            streams = listOf(StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")),
            constants = emptyList(),
            lets = emptyList(),
            defaults = null,
            rules = emptyList<WhenThen>(),
        )

    protected fun registryWith(
        tmp: Path,
        names: List<String>,
    ): Pair<StrategyRegistry, Map<String, RecordingHandle>> {
        val handlesByName = names.associateWith { RecordingHandle() }
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { name, _, _ ->
                    val ring = EventRing(capacity = 16)
                    val live = handlesByName.getValue(name)
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
                            running = { live.running },
                            onStop = { live.stop() },
                            bind = "127.0.0.1",
                            port = 0,
                        ).also { it.start() }
                    opened.add(server)
                    StrategyHandle(
                        name = name,
                        ast = stubAst(name),
                        live = live,
                        observability = server,
                        ring = ring,
                        logFile = tmp.resolve("$name.log"),
                        startedAt = Instant.EPOCH,
                    )
                },
            )
        names.forEach { registry.deploy(it, tmp.resolve("$it.qkt")) }
        return registry to handlesByName
    }
}
