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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ListRouteStreamBrokersTest : ListRouteFixture() {
    @Test
    fun `list includes streamBrokers map for each deployed strategy`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val factory =
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

                        override fun streamBrokers(): Map<String, String> = mapOf("gold" to "EXNESS", "btc" to "BYBIT")
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
        val registry = StrategyRegistry(factory)
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        opened.add(plane)
        registry.deploy("multi", tmp.resolve("multi.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
                .execute()
        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"streamBrokers\":{")
        assertThat(body).contains("\"gold\":\"EXNESS\"")
        assertThat(body).contains("\"btc\":\"BYBIT\"")
    }
}
