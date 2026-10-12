package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyToRecursively
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt backtest` on the real `btc-usdc-26sep26` option fixture: what the operator is told. */
class OptionBacktestCommandTest {
    private fun backtest(
        dir: Path,
        from: String,
        fixture: String = "btc-usdc-26sep26",
        body: String = CALL_BODY,
    ): Pair<Int, String> {
        val source = Paths.get(requireNotNull(javaClass.getResource("/options/$fixture")).toURI())
        val data = dir.resolve("data")
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        source.copyToRecursively(data, followLinks = false, overwrite = false)
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY opt VERSION 1\nSYMBOLS\n$body",
        )
        val out = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(out))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategy.toString(),
                            "--from",
                            from,
                            "--to",
                            "2026-09-27",
                            "--data-root",
                            data.toString(),
                            "--no-fetch",
                            "--verbose",
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        return code to out.toString()
    }

    @Test
    fun `an option run reports chain coverage and the option venue's model, not tick holes or paper fills`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, "2026-09-25")

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("chain coverage DERIBIT:BTC_USDC_26SEP26_84000_C 2/2 days (trade chain)")
        assertThat(output).doesNotContain("tick coverage")
        assertThat(output).contains("Options:    ")
        assertThat(output).contains("fills on the option venue")
        assertThat(output).doesNotContain("paper broker fills at mid")
    }

    @Test
    fun `a missing chain day stops the run and names the fetch`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, "2026-09-24")

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("qkt fetch DERIBIT:BTC_USDC --chains --from 2026-09-24 --to 2026-09-24")
    }

    @Test
    fun `a chain analytics signal trades an option at the next quote's ask`(
        @TempDir dir: Path,
    ) {
        // atm_iv.1d first exceeds 33.5 at 09:00 on the 25th (33.5953, fixture PROVENANCE); the put's 10:00
        // trade mark 457.22577903 is 1h fresh, so its ask is 457.23 + 5% = 480.08, up to the 5 grid: 485.
        val body =
            "    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.1d EVERY 1h\n    p = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h\n" +
                "RULES\n    WHEN iv.close > 33.5\n    THEN BUY p SIZING 0.1\n"

        val (code, output) = backtest(dir, "2026-09-25", fixture = "btc-usdc-trade-25sep26", body = body)

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("chain coverage CHAIN:DERIBIT.BTC_USDC.atm_iv.1d 2/2 days (trade chain)")
        assertThat(output).contains("stream CHAIN:DERIBIT.BTC_USDC.atm_iv.1d:1h: 9 candles")
        assertThat(output).contains("side=BUY qty=0.10 price=485.00000000")
        assertThat(output).doesNotContain("paper broker fills at mid")
    }

    @Test
    fun `an analytics stream the chain never defines runs with rules that never fire`(
        @TempDir dir: Path,
    ) {
        val body =
            "    k = CHAIN:DERIBIT.BTC_USDC.skew_25d.7d EVERY 1h\n    p = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h\n" +
                "RULES\n    WHEN k.close > 1\n    THEN BUY p SIZING 0.1\n"

        val (code, output) = backtest(dir, "2026-09-25", fixture = "btc-usdc-trade-25sep26", body = body)

        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("Trades:           0")
    }

    @Test
    fun `feeding the whole root changes nothing for a declared contract and its trades`(
        @TempDir dir: Path,
    ) {
        val trading =
            "    p = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h\n" +
                "RULES\n    WHEN p.close > 0\n    THEN BUY p SIZING 0.1\n"

        fun result(body: String): List<String> {
            val (code, output) =
                backtest(
                    Files.createDirectories(dir.resolve("r${body.length}")),
                    "2026-09-25",
                    fixture = "btc-usdc-trade-25sep26",
                    body = body,
                )
            assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
            val report =
                output
                    .lines()
                    .dropWhile {
                        !it.startsWith(
                            "Trades:",
                        )
                    }.takeWhile { !it.startsWith("Runaway breaker:") }
            return report + output.lines().filter { it.contains("stream DERIBIT:") || it.contains("live candles:") }
        }

        val alone = result(trading)
        val fed = result("    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h\n$trading")

        assertThat(fed).isEqualTo(alone).anyMatch { it.startsWith("Trades:") && !it.endsWith(" 0") }
    }

    private companion object {
        const val CALL_BODY =
            "    c = DERIBIT:BTC_USDC_26SEP26_84000_C EVERY 1h\n" +
                "RULES\n    WHEN c.close > 0\n    THEN BUY c SIZING 0.1\n"
    }
}
