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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StatusProxyTest {
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    private fun stubFactory(stateDir: StateDir): StrategyHandle.Factory =
        StrategyHandle.Factory { name, _, _ ->
            val ring = EventRing(capacity = 8)
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
                        listOf(StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")),
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
    fun `GET status name proxies to per-strategy observability port`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(stubFactory(stateDir))
        val plane = ControlPlane(registry, port = 0, stateDir = stateDir)
        plane.start()
        opened.add(plane)
        registry.deploy("foo", tmp.resolve("foo.qkt"))

        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/status/foo").build())
                .execute()
        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"strategy\":\"foo\"")
    }

    @Test
    fun `GET status without name returns array of all strategies`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(stubFactory(stateDir))
        val plane = ControlPlane(registry, port = 0, stateDir = stateDir)
        plane.start()
        opened.add(plane)
        registry.deploy("alpha", tmp.resolve("a.qkt"))
        registry.deploy("beta", tmp.resolve("b.qkt"))

        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/status").build())
                .execute()
        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).startsWith("[")
        assertThat(body).contains("\"strategy\":\"alpha\"")
        assertThat(body).contains("\"strategy\":\"beta\"")
    }

    @Test
    fun `GET status unknown name returns 404`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(stubFactory(stateDir))
        val plane = ControlPlane(registry, port = 0, stateDir = stateDir)
        plane.start()
        opened.add(plane)

        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/status/missing").build())
                .execute()
        assertThat(resp.code).isEqualTo(404)
    }
}
