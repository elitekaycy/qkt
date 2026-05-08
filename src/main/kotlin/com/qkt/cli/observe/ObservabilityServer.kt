package com.qkt.cli.observe

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

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

    init {
        server.createContext("/health", Routes.health(running))
        server.createContext("/status", Routes.status(statusProvider))
        server.createContext("/logs", Routes.logs(ring))
        server.createContext("/events", Routes.events(ring))
        server.createContext("/stop", Routes.stop(onStop))
        server.executor = Executors.newFixedThreadPool(4)
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
