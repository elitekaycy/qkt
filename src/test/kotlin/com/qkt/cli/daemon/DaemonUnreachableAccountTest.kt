package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * #1642: a daemon whose second account's gateway does not resolve at boot used to exit with
 * "broker account preflight failed" and restart into the same failure, managing nothing. It now starts, serves the
 * reachable account, and reports the other one as not verified on `/health`.
 */
class DaemonUnreachableAccountTest {
    private class UnusedSource : MarketSource {
        override val name: String = "unused"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next(): Tick? = null

                override fun close() {}
            }
    }

    @Test
    fun `one unreachable account at boot leaves the daemon up and degraded, naming the account`(
        @TempDir tmp: Path,
    ) {
        val accountCalls = AtomicInteger()
        val gateway = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        gateway.createContext("/account") { exchange ->
            accountCalls.incrementAndGet()
            val body =
                """{"login":435898347,"server":"Exness-MT5Trial9","trade_mode":0,"balance":10000,""" +
                    """"equity":10000,"currency":"USD","leverage":100,"margin_mode":2}"""
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        gateway.start()
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: false
            brokers:
              exness_a:
                type: mt5
                extends: exness
                gateway_url: http://127.0.0.1:${gateway.address.port}
                magic: 1
              alpaca:
                type: mt5
                extends: exness
                gateway_url: http://gw-alpaca.invalid:8080
                magic: 2
            """.trimIndent(),
        )
        val stateRoot = tmp.resolve("state")
        val stateDir = StateDir.resolve(stateRoot.toString())
        val args = arrayOf("daemon", "--config", cfg.toString(), "--state-dir", stateRoot.toString())
        val exit = AtomicInteger(-1)
        val daemon = Thread { exit.set(DaemonCommand(Args(args), sourceFactory = { UnusedSource() }).run()) }
        daemon.isDaemon = true
        daemon.start()
        try {
            val end = System.currentTimeMillis() + 60_000L
            while (stateDir.readControlPort() == null && daemon.isAlive && System.currentTimeMillis() < end) {
                Thread.sleep(50)
            }
            assertThat(exit.get()).describedAs("daemon exited at boot").isEqualTo(-1)
            val port = requireNotNull(stateDir.readControlPort())
            val body =
                OkHttpClient()
                    .newCall(Request.Builder().url("http://127.0.0.1:$port/health").build())
                    .execute()
                    .use { it.body!!.string() }

            assertThat(body).contains("\"status\":\"degraded\"")
            assertThat(body).contains("\"unverifiedAccounts\":[{\"account\":\"alpaca\"")
            assertThat(body).doesNotContain("\"account\":\"exness_a\"")
            assertThat(accountCalls.get()).isGreaterThanOrEqualTo(1)
        } finally {
            daemon.interrupt()
            daemon.join(10_000)
            gateway.stop(0)
        }
    }
}
