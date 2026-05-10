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
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0)

    val boundPort: Int get() = server.address.port
    val boundHost: String = bind

    init {
        server.createContext(
            "/",
            ControlRoutes.dispatch(registry, startedAt, stateDir, portfolioDeployer) { shutdownHook() },
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
