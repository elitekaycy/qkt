package com.qkt.cli

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunCommandObservabilityTest {
    private class BoundedTickFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            return if (i < ticks.size) ticks[i] else null
        }
    }

    private class FakeLiveSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "Fake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = BoundedTickFeed(ticks)
    }

    private fun runCommand(argv: Array<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val cmd = RunCommand(Args(argv)) { FakeLiveSource(emptyList()) }
            val code = cmd.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `run with --port 0 emits QKT_PORT line`() {
        val (code, stdout, _) =
            runCommand(arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--port", "0"))
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("QKT_PORT=")
        assertThat(stdout).contains("[INFO] observability: http://127.0.0.1:")
    }

    @Test
    fun `privileged port without override returns arg error`() {
        val (code, _, stderr) =
            runCommand(arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--port", "80"))
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("port 80 is privileged")
        assertThat(stderr).contains("--allow-privileged-port")
    }

    @Test
    fun `--no-observe suppresses port discovery output`() {
        val (code, stdout, _) =
            runCommand(arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--no-observe"))
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).doesNotContain("QKT_PORT=")
        assertThat(stdout).doesNotContain("observability:")
    }

    @Test
    fun `--port-file writes bound port to disk`() {
        val tmp = Files.createTempFile("qkt-port", ".txt")
        Files.deleteIfExists(tmp)
        try {
            val (code, _, _) =
                runCommand(
                    arrayOf(
                        "run",
                        "src/test/resources/cli/valid_strategy.qkt",
                        "--port",
                        "0",
                        "--port-file",
                        tmp.toString(),
                    ),
                )
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            assertThat(Files.exists(tmp)).isTrue()
            val written = Files.readString(tmp).trim()
            assertThat(written.toIntOrNull()).isNotNull()
            assertThat(written.toInt()).isGreaterThan(0)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `--bind 0_0_0_0 emits warning to stderr`() {
        val (code, _, stderr) =
            runCommand(
                arrayOf(
                    "run",
                    "src/test/resources/cli/valid_strategy.qkt",
                    "--port",
                    "0",
                    "--bind",
                    "0.0.0.0",
                ),
            )
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stderr).contains("[WARN]")
        assertThat(stderr).contains("NO authentication")
    }
}
