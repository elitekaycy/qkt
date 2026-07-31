package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.PromotionApproval
import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionRecord
import com.qkt.cli.PromotionState
import com.qkt.cli.PromotionStore
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.nio.file.Files
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

class ListRouteTest {
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    private fun stubFactory(
        stateDir: StateDir,
        childMeta: (String) -> StrategyHandle.ChildMeta? = { null },
    ): StrategyHandle.Factory =
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
                childMeta = childMeta(name),
            )
        }

    @Test
    fun `list returns array with both deployed strategies and disjoint ports`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(stubFactory(stateDir))
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        opened.add(plane)
        registry.deploy("alpha", tmp.resolve("a.qkt"))
        registry.deploy("beta", tmp.resolve("b.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
                .execute()
        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"name\":\"alpha\"")
        assertThat(body).contains("\"name\":\"beta\"")
        // Distinct ports — extract numeric port values and confirm they differ.
        val portRegex = Regex("\"port\":(\\d+)")
        val ports = portRegex.findAll(body).map { it.groupValues[1].toInt() }.toList()
        assertThat(ports).hasSize(2)
        assertThat(ports.toSet()).hasSize(2)
    }

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

    @Test
    fun `list returns empty array when no strategies are deployed`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry =
            StrategyRegistry(StrategyHandle.Factory { _, _, _ -> error("no deploys expected") })
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        opened.add(plane)
        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
                .execute()
        assertThat(resp.code).isEqualTo(200)
        assertThat(resp.body!!.string().trim()).isEqualTo("[]")
    }

    @Test
    fun `portfolio child inherits parent promotion eligibility`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val parent = "book"
        val registry =
            StrategyRegistry(
                stubFactory(stateDir) { name ->
                    if (name != "$parent/child") return@stubFactory null
                    StrategyHandle.ChildMeta(
                        parent = parent,
                        alias = "child",
                        hold = false,
                        gateActive = AtomicBoolean(true),
                        operatorStop = AtomicBoolean(false),
                    )
                },
            )
        val now = Instant.parse("2026-07-31T00:00:00Z")
        PromotionStore(stateDir.stateRoot.resolve("promotion"))
            .append(
                PromotionRecord.create(
                    strategy = parent,
                    strategyHash = "parent-hash",
                    state = PromotionState.PRODUCTION,
                    rationale = "approved portfolio",
                    now = now,
                    approvals =
                        listOf(
                            PromotionApproval(
                                state = PromotionState.PRODUCTION,
                                actor = "operator",
                                reason = "approved portfolio",
                                approvedAt = now.toString(),
                            ),
                        ),
                ),
            )
        val plane =
            ControlPlane(
                registry = registry,
                port = 0,
                stateDir = stateDir,
                promotionGates = PromotionGateConfig(enforce = true),
            )
        plane.start()
        opened.add(plane)
        registry.deploy("$parent/child", tmp.resolve("child.qkt").also { Files.writeString(it, "child") })

        val response =
            OkHttpClient()
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
                .execute()
        assertThat(response.code).isEqualTo(200)
        val body = response.body!!.string()
        assertThat(body).contains("\"name\":\"$parent/child\"")
        assertThat(body).contains("\"promotionEligible\":true")
        assertThat(body).contains("\"promotionMissingGates\":[]")
    }
}
