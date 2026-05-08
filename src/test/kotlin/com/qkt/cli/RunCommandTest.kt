package com.qkt.cli

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunCommandTest {
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

    private fun runCommand(
        argv: Array<String>,
        ticks: List<Tick>,
    ): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val cmd = RunCommand(Args(argv)) { FakeLiveSource(ticks) }
            val code = cmd.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `run with bounded feed exits cleanly and logs trades`() {
        val ticks =
            (0 until 5).map {
                Tick(
                    symbol = "BTCUSDT",
                    price = BigDecimal("42000.0").add(BigDecimal(it * 10)),
                    timestamp = 1_705_276_800_000L + it * 60_000L,
                )
            }
        val (code, stdout, _) =
            runCommand(
                arrayOf("run", "src/test/resources/cli/valid_strategy.qkt"),
                ticks,
            )
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("paper-trading")
        assertThat(stdout).contains("subscribed:")
        assertThat(stdout).contains("feed closed")
    }

    @Test
    fun `rejects --source bybit with explicit message`() {
        val (code, _, stderr) =
            runCommand(
                arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--source", "bybit"),
                emptyList(),
            )
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("--source bybit")
        assertThat(stderr).contains("not yet enabled in 12a")
    }

    @Test
    fun `rejects --source alpaca`() {
        val (code, _, stderr) =
            runCommand(
                arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--source", "alpaca"),
                emptyList(),
            )
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("--source alpaca")
    }

    @Test
    fun `missing strategy file exits with user error`() {
        val (code, _, stderr) =
            runCommand(
                arrayOf("run", "does_not_exist.qkt"),
                emptyList(),
            )
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("file not found")
    }
}
