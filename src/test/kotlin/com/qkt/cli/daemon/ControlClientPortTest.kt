package com.qkt.cli.daemon

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ControlClientPortTest {
    @Test
    fun `metrics hits the explicit port, bypassing the control-port file`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/metrics") { ex ->
            val body = "qkt_daemon_uptime_seconds 42".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            // stateDir has no control.port file; the explicit port must be used instead.
            val client =
                ControlClient(
                    stateDir = StateDir.resolve(Files.createTempDirectory("sd").toString()),
                    explicitPort = server.address.port,
                )
            assertThat(client.metrics()).contains("qkt_daemon_uptime_seconds")
        } finally {
            server.stop(0)
        }
    }
}
