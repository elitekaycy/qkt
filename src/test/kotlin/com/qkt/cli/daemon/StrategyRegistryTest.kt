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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyRegistryTest {
    private val opened = mutableListOf<ObservabilityServer>()

    @AfterEach
    fun cleanup() {
        for (s in opened) runCatching { s.close() }
        opened.clear()
    }

    private fun fakeFactory(stateDir: StateDir): StrategyHandle.Factory =
        StrategyHandle.Factory { name, _ ->
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

    @Test
    fun `deploy adds a handle and list returns it`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        val handle = registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        assertThat(handle.name).isEqualTo("alpha")
        assertThat(registry.list()).hasSize(1)
        assertThat(registry.get("alpha")).isSameAs(handle)
    }

    @Test
    fun `deploy rejects duplicate names`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        assertThatThrownBy { registry.deploy("alpha", tmp.resolve("alpha.qkt")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already deployed")
    }

    @Test
    fun `deploy rejects invalid names`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        assertThatThrownBy { registry.deploy("bad name!", tmp.resolve("x.qkt")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid strategy name")
    }

    @Test
    fun `stop removes the handle and closes it`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        val handle = registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        assertThat(registry.stop("alpha")).isTrue
        assertThat(registry.list()).isEmpty()
        assertThat(handle.isRunning()).isFalse
    }

    @Test
    fun `stop returns false for unknown name`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        assertThat(registry.stop("nope")).isFalse
    }

    @Test
    fun `stopAll drains everything`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        registry.deploy("beta", tmp.resolve("beta.qkt"))
        registry.stopAll()
        assertThat(registry.list()).isEmpty()
    }
}
