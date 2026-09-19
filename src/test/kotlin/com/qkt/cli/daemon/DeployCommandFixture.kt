package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.PromotionGateConfig
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir

abstract class DeployCommandFixture {
    protected val opened = mutableListOf<AutoCloseable>()

    protected data class TestPlane(
        val plane: ControlPlane,
        val registry: StrategyRegistry,
    )

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    protected fun stubFactory(stateDir: StateDir): StrategyHandle.Factory =
        StrategyHandle.Factory { name, file, _ ->
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
                sourceFile = file,
            )
        }

    protected fun newPlane(
        @TempDir tmp: Path? = null,
        stateDir: StateDir,
        promotionGates: PromotionGateConfig = PromotionGateConfig.DISABLED,
    ): ControlPlane = newPlaneWithRegistry(tmp, stateDir, promotionGates).plane

    protected fun newPlaneWithRegistry(
        @TempDir tmp: Path? = null,
        stateDir: StateDir,
        promotionGates: PromotionGateConfig = PromotionGateConfig.DISABLED,
    ): TestPlane {
        val registry = StrategyRegistry(stubFactory(stateDir))
        val routeStateDir = if (promotionGates == PromotionGateConfig.DISABLED) null else stateDir
        val plane = ControlPlane(registry, port = 0, stateDir = routeStateDir, promotionGates = promotionGates)
        plane.start()
        opened.add(plane)
        val instanceLock = stateDir.acquireDaemonLock()!!
        instanceLock.writeControlPort(plane.boundPort)
        opened.add(instanceLock)
        return TestPlane(plane, registry)
    }

    protected fun postDeploy(
        client: OkHttpClient,
        plane: ControlPlane,
        file: Path,
        name: String,
        query: String = "",
    ): okhttp3.Response {
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"$name"}"""
                .toRequestBody("application/json".toMediaType())
        val suffix = query.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
        return client
            .newCall(
                Request
                    .Builder()
                    .url("http://127.0.0.1:${plane.boundPort}/deploy$suffix")
                    .post(body)
                    .build(),
            ).execute()
    }

    protected fun postResync(
        client: OkHttpClient,
        plane: ControlPlane,
        file: Path,
        name: String,
        dryRun: Boolean = false,
        query: String = "",
    ): okhttp3.Response {
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"$name","dryRun":$dryRun}"""
                .toRequestBody("application/json".toMediaType())
        val suffix = query.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
        return client
            .newCall(
                Request
                    .Builder()
                    .url("http://127.0.0.1:${plane.boundPort}/resync$suffix")
                    .post(body)
                    .build(),
            ).execute()
    }

    protected fun waitForJournalAction(
        stateDir: StateDir,
        action: String,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (System.nanoTime() < deadline) {
            val text = journalText(stateDir)
            if (text.contains(""""action":"$action"""")) return
            Thread.sleep(20)
        }
        assertThat(journalText(stateDir)).contains(""""action":"$action"""")
    }

    protected fun journalText(stateDir: StateDir): String {
        val root = stateDir.stateRoot.resolve("journal")
        if (!Files.exists(root)) return ""
        val out = StringBuilder()
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .forEach { out.append(Files.readString(it)) }
        }
        return out.toString()
    }
}
