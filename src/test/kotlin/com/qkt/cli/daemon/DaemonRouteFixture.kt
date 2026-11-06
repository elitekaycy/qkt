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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach

/**
 * A control plane over a registry whose strategies are stubs: [live] returns the session a strategy name is
 * deployed with (an idle one unless a test puts its own in [sessions]), [meta] makes a name a portfolio child.
 */
abstract class DaemonRouteFixture : StrategyRegistryPortfolioFixture() {
    protected val sessions = ConcurrentHashMap<String, LiveSessionHandle>()
    protected val meta = ConcurrentHashMap<String, StrategyHandle.ChildMeta>()

    /** The next create of a name throws its exception (a deploy or resync that fails). */
    protected val failures = ConcurrentHashMap<String, Exception>()
    private val planes = mutableListOf<ControlPlane>()
    private val http = OkHttpClient()

    @AfterEach
    fun closePlanes() {
        planes.forEach { runCatching { it.close() } }
        planes.clear()
    }

    protected open class IdleSession : LiveSessionHandle {
        private val up = AtomicBoolean(true)
        override val running: Boolean get() = up.get()
        override val droppedTicks: Long = 0L

        override fun stop() = up.set(false)

        override fun awaitTermination(timeout: Duration): Boolean = true

        override fun recentTrades(): List<Trade> = emptyList()

        override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> = emptyList()

        override fun flatten() = Unit
    }

    protected fun childMeta(
        parent: String,
        alias: String,
        hold: Boolean = false,
    ) = StrategyHandle.ChildMeta(parent, alias, hold, AtomicBoolean(true), AtomicBoolean(false))

    protected fun registry(stateDir: StateDir): StrategyRegistry =
        StrategyRegistry(
            StrategyHandle.Factory { name, file, _ ->
                failures.remove(name)?.let { throw it }
                val ring = EventRing(capacity = 8)
                val live = sessions[name] ?: IdleSession().also { sessions[name] = it }
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
                    ast =
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
                        ),
                    live = live,
                    observability = server,
                    ring = ring,
                    logFile = stateDir.logFile(name),
                    startedAt = Instant.now(),
                    sourceFile = file,
                    childMeta = meta[name],
                )
            },
        )

    protected fun plane(
        registry: StrategyRegistry,
        stateDir: StateDir? = null,
        notifierMetrics: com.qkt.notify.NotifierMetrics? = null,
        portfolioDeployer: com.qkt.cli.daemon.portfolio.PortfolioDeployer? = null,
    ): ControlPlane =
        ControlPlane(
            registry,
            port = 0,
            stateDir = stateDir,
            portfolioDeployer = portfolioDeployer,
            notifierMetrics = notifierMetrics,
        ).also {
            it.start()
            planes += it
        }

    protected fun get(
        plane: ControlPlane,
        path: String,
    ): Pair<Int, String> =
        http
            .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}$path").build())
            .execute()
            .use { it.code to it.body!!.string() }

    protected fun post(
        plane: ControlPlane,
        path: String,
        body: String = "",
    ): Pair<Int, String> =
        http
            .newCall(
                Request
                    .Builder()
                    .url("http://127.0.0.1:${plane.boundPort}$path")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
            .use { it.code to it.body!!.string() }
}
