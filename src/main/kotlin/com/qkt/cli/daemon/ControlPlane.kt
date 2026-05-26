package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors

class ControlPlane(
    private val registry: StrategyRegistry,
    bind: String = "127.0.0.1",
    port: Int = 0,
    private val startedAt: Instant = Instant.now(),
    private val shutdownHook: () -> Unit = {},
    private val stateDir: StateDir? = null,
    private val portfolioDeployer: PortfolioDeployer? = null,
    private val notifierMetrics: com.qkt.notify.NotifierMetrics? = null,
    /**
     * Kill switch for the built-in Prometheus `/metrics` endpoint. Default reads
     * `QKT_METRICS_PROMETHEUS`; "false" / "0" / "off" / "no" disables registration,
     * any other value (or unset) keeps it on. The canonical metrics surface is the
     * JSON endpoints (`/health`, `/status`, `/latency`, `/list`) — see
     * `docs/operations/metrics.md`. Operators using their own exporter sidecar will
     * typically turn this off to avoid carrying an unused endpoint.
     */
    private val prometheusMetricsEnabled: Boolean =
        System.getenv("QKT_METRICS_PROMETHEUS")?.lowercase() !in setOf("false", "0", "off", "no"),
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0)

    val boundPort: Int get() = server.address.port
    val boundHost: String = bind

    init {
        server.createContext(
            "/",
            ControlRoutes.dispatch(
                registry = registry,
                startedAt = startedAt,
                stateDir = stateDir,
                portfolioDeployer = portfolioDeployer,
                shutdown = { shutdownHook() },
                notifierMetrics = notifierMetrics,
                prometheusMetricsEnabled = prometheusMetricsEnabled,
            ),
        )
        server.executor = Executors.newFixedThreadPool(8)
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
