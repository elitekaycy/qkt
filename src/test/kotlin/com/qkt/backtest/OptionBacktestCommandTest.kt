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
    ): Pair<Int, String> {
        val source = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-26sep26")).toURI())
        val data = dir.resolve("data")
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        source.copyToRecursively(data, followLinks = false, overwrite = false)
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY opt VERSION 1\nSYMBOLS\n    c = DERIBIT:BTC_USDC_26SEP26_84000_C EVERY 1h\n" +
                "RULES\n    WHEN c.close > 0\n    THEN BUY c SIZING 0.1\n",
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
}
