package com.qkt.cli

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndCliTest {
    private fun invoke(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = runMain(argv as Array<String>)
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `--version prints qkt version`() {
        val (code, stdout, _) = invoke("--version")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout.trim()).isEqualTo("qkt ${BuildInfo.VERSION}")
    }

    @Test
    fun `--help prints usage`() {
        val (code, stdout, _) = invoke("--help")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("USAGE")
        assertThat(stdout).contains("parse")
        assertThat(stdout).contains("backtest")
        assertThat(stdout).contains("run")
    }

    @Test
    fun `parse on valid strategy exits 0`() {
        val (code, stdout, _) = invoke("parse", "src/test/resources/cli/valid_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("ok")
    }

    @Test
    fun `parse on broken strategy exits 1`() {
        val (code, _, stderr) = invoke("parse", "src/test/resources/cli/broken_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("broken_strategy.qkt:")
    }

    @Test
    fun `backtest produces report on fixture data`() {
        val (code, stdout, stderr) =
            invoke(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
            )
        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("Trades:")
    }

    @Test
    fun `run with rejected --source bybit returns user error`() {
        val (code, _, stderr) =
            invoke(
                "run",
                "src/test/resources/cli/valid_strategy.qkt",
                "--source",
                "bybit",
            )
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("not yet enabled in 12a")
    }

    @Test
    fun `unknown subcommand returns arg error`() {
        val (code, _, stderr) = invoke("frobnicate")
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("unknown subcommand")
    }

    private class BoundedFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)
        private val gate = java.util.concurrent.CountDownLatch(1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i < ticks.size) return ticks[i]
            // Block until close (or timeout) so the run keeps the server up.
            gate.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            gate.countDown()
        }
    }

    private class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "Fake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = BoundedFeed(ticks)
    }

    @Test
    fun `run --port 0 exposes status and stops via stop endpoint`() {
        val ticks =
            (0 until 3).map {
                Tick(
                    symbol = "BTCUSDT",
                    price = BigDecimal("42000.0").add(BigDecimal(it * 10)),
                    timestamp = 1_705_276_800_000L + it * 60_000L,
                )
            }
        val capturedOut = ByteArrayOutputStream()
        val origOut = System.out
        System.setOut(PrintStream(capturedOut, true))
        val exitCode =
            java.util.concurrent.atomic
                .AtomicInteger(-1)
        val thread =
            Thread {
                val args = Args(arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--port", "0"))
                val cmd = RunCommand(args) { FakeSource(ticks) }
                exitCode.set(cmd.run())
            }
        thread.isDaemon = true
        thread.start()
        try {
            // Poll captured stdout for the QKT_PORT line.
            val deadline = System.currentTimeMillis() + 5_000L
            var port = -1
            while (System.currentTimeMillis() < deadline && port < 0) {
                Thread.sleep(50)
                val text = capturedOut.toString(Charsets.UTF_8)
                val line = text.lineSequence().firstOrNull { it.startsWith("QKT_PORT=") }
                if (line != null) port = line.removePrefix("QKT_PORT=").trim().toInt()
            }
            assertThat(port).withFailMessage("QKT_PORT line never appeared").isGreaterThan(0)

            val client = OkHttpClient()
            val statusResp =
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/status").build()).execute()
            assertThat(statusResp.code).isEqualTo(200)
            val body = statusResp.body!!.string()
            assertThat(body).contains("\"strategy\":\"example\"")
            assertThat(body).contains("\"version\":1")

            val stopResp =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url("http://127.0.0.1:$port/stop")
                            .post(ByteArray(0).toRequestBody(null))
                            .build(),
                    ).execute()
            assertThat(stopResp.code).isEqualTo(202)

            thread.join(5_000)
            assertThat(thread.isAlive).withFailMessage("run thread did not exit").isFalse()
            assertThat(exitCode.get()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(origOut)
            if (thread.isAlive) thread.interrupt()
        }
    }

    @Test
    fun `missing required flag surfaces arg error via Main`() {
        val (code, _, stderr) =
            invoke(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
            )
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("--from")
    }
}
