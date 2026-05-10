package com.qkt.cli.observe

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Per-strategy HTTP observability server.
 *
 * Each `LiveSession` spins up its own server on an ephemeral port (announced via
 * `QKT_PORT=…` on stdout). Routes: `/health`, `/status`, `/events`, `/stop`, `/logs`.
 * The server is fronted by the daemon control plane for `qkt status`/`qkt logs`.
 */
class ObservabilityServer(
    private val ring: EventRing,
    private val statusProvider: () -> StatusSnapshot,
    private val running: () -> Boolean,
    private val onStop: (flatten: Boolean) -> Unit,
    bind: String,
    port: Int,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0)

    val boundPort: Int get() = server.address.port
    val boundHost: String = bind

    private val executor: java.util.concurrent.ExecutorService =
        Executors.newFixedThreadPool(4) { r ->
            Thread(r, "qkt-observability-${server.address.port}").apply { isDaemon = true }
        }

    init {
        server.createContext("/health", Routes.health(running))
        server.createContext("/status", Routes.status(statusProvider))
        server.createContext("/logs", Routes.logs(ring))
        server.createContext("/events", Routes.events(ring))
        server.createContext("/stop", Routes.stop(onStop))
        server.executor = executor
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdown()
        if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }
}
