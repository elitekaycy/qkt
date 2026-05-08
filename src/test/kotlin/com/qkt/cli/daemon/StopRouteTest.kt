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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StopRouteTest {
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    private fun stubFactory(stateDir: StateDir): StrategyHandle.Factory =
        StrategyHandle.Factory { name, _ ->
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

    private fun newPlane(stateDir: StateDir): Pair<StrategyRegistry, ControlPlane> {
        val registry = StrategyRegistry(stubFactory(stateDir))
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        opened.add(plane)
        return registry to plane
    }

    @Test
    fun `POST stop name returns 200 with name state and trades`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"name\":\"foo\"")
        assertThat(body).contains("\"state\":\"stopped\"")
        assertThat(body).contains("\"trades\":0")
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `POST stop unknown returns 404`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (_, plane) = newPlane(stateDir)
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/missing")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(404)
    }

    @Test
    fun `POST stop accepts flatten and timeout query params`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo?flatten=true&timeout=2000")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(200)
    }

    @Test
    fun `POST stop with invalid timeout returns 400`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo?timeout=oops")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(400)
    }
}
