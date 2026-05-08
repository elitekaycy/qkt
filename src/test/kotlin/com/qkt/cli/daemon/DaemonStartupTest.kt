package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonStartupTest {
    private class IdleFeed : TickFeed {
        private val gate = CountDownLatch(1)

        override fun next(): Tick? {
            gate.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            gate.countDown()
        }
    }

    private class IdleSource : MarketSource {
        override val name: String = "Idle"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = IdleFeed()
    }

    private fun waitForFile(
        path: Path,
        deadlineMs: Long = 5_000L,
    ) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (Files.exists(path)) return
            Thread.sleep(50)
        }
        throw AssertionError("file did not appear in $deadlineMs ms: $path")
    }

    @Test
    fun `daemon writes control port and serves health, then shuts down on registry stop`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val daemonThread =
            Thread {
                val args = Args(arrayOf("daemon", "--state-dir", tmp.toString()))
                DaemonCommand(args, sourceFactory = { IdleSource() }).run()
            }
        daemonThread.isDaemon = true
        daemonThread.start()
        try {
            waitForFile(stateDir.controlPortFile)
            val port = stateDir.readControlPort()!!
            assertThat(port).isGreaterThan(0)

            val client = OkHttpClient()
            val resp =
                client
                    .newCall(Request.Builder().url("http://127.0.0.1:$port/health").build())
                    .execute()
            assertThat(resp.code).isEqualTo(200)
            val body = resp.body!!.string()
            assertThat(body).contains("\"status\":\"ok\"")
            assertThat(body).contains("\"strategies\":0")
        } finally {
            daemonThread.interrupt()
            daemonThread.join(5_000)
        }
    }
}
