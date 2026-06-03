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
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ControlRoutesHaltTest {
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    private class RecordingHandle : LiveSessionHandle {
        var halts = 0
        var resumes = 0
        private val alive = AtomicBoolean(true)

        override val running: Boolean get() = alive.get()
        override val droppedTicks: Long = 0L

        override fun stop() {
            alive.set(false)
        }

        override fun awaitTermination(timeout: Duration): Boolean = true

        override fun recentTrades(): List<Trade> = emptyList()

        override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> = emptyList()

        override fun flatten() = Unit

        override fun halt(reason: String) {
            halts++
        }

        override fun resume() {
            resumes++
        }
    }

    private fun post(
        port: Int,
        path: String,
    ): HttpResponse<String> {
        val req =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `POST halt halts all, POST halt name halts one`(
        @TempDir tmp: Path,
    ) {
        val handles = listOf("alpha", "beta").associateWith { RecordingHandle() }
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { name, _, _ ->
                    val ring = EventRing(capacity = 8)
                    val live = handles.getValue(name)
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
                    val ast =
                        StrategyAst(
                            name = name,
                            version = 1,
                            streams =
                                listOf(
                                    StreamDecl(
                                        alias = "s",
                                        broker = "BACKTEST",
                                        symbol = "BTCUSDT",
                                        timeframe = "1m",
                                    ),
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
                        logFile = tmp.resolve("$name.log"),
                        startedAt = Instant.EPOCH,
                    )
                },
            )
        handles.keys.forEach { registry.deploy(it, tmp.resolve("$it.qkt")) }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            ControlRoutes.dispatch(registry, Instant.EPOCH, stateDir = null, shutdown = {}),
        )
        server.start()
        opened.add(AutoCloseable { server.stop(0) })

        val port = server.address.port
        assertThat(post(port, "/halt").statusCode()).isEqualTo(200)
        assertThat(handles.getValue("alpha").halts).isEqualTo(1)
        assertThat(handles.getValue("beta").halts).isEqualTo(1)

        assertThat(post(port, "/resume/alpha").statusCode()).isEqualTo(200)
        assertThat(handles.getValue("alpha").resumes).isEqualTo(1)
        assertThat(handles.getValue("beta").resumes).isEqualTo(0)

        assertThat(post(port, "/halt/ghost").statusCode()).isEqualTo(404)
    }
}
