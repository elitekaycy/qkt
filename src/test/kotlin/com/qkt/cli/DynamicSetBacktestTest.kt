package com.qkt.cli

import com.qkt.backtest.TickResolvedParityFixtures
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.sets.DynamicSetFixtures
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DynamicSetBacktestTest {
    @TempDir
    lateinit var tmp: Path

    private val instruments =
        """
        instruments:
          - { qktSymbol: BACKTEST:XAUUSD, contractSize: 100, volumeStep: 0.01, volumeMin: 0.01, pointSize: 0.001, digits: 3, tradeStopsLevelPoints: 0, category: Metals }
          - { qktSymbol: BACKTEST:EURUSD, contractSize: 100000, volumeStep: 0.01, volumeMin: 0.01, pointSize: 0.00001, digits: 5, tradeStopsLevelPoints: 0, category: Forex }
        """.trimIndent()

    private fun strategy(set: String) =
        """
        STRATEGY sets VERSION 1
        SYMBOLS
            m = $set EVERY 15m
        RULES
            FOR EACH s IN m DO
              WHEN ema(s.close, 3) CROSSES ABOVE ema(s.close, 9) THEN BUY s SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
        """.trimIndent()

    private class Outcome(
        val code: Int,
        val err: String,
        val trades: List<String>,
    )

    private fun backtest(set: String): Outcome {
        val dataRoot = tmp.resolve("data")
        if (!Files.exists(dataRoot)) {
            TickResolvedParityFixtures.seedTicks(dataRoot, days = 3)
            Files.writeString(dataRoot.resolve("instruments.yaml"), instruments)
        }
        val name = "run${set.hashCode()}"
        val file = tmp.resolve("$name.qkt").also { Files.writeString(it, strategy(set)) }
        val report = tmp.resolve(name)
        val argv =
            arrayOf(
                "backtest",
                file.toString(),
                "--data-root",
                dataRoot.toString(),
                "--no-fetch",
                "--allow-incomplete",
                "--json",
                "--from",
                "2024-01-02",
                "--to",
                "2024-01-05",
                "--report-dir",
                report.toString(),
            )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (origOut, origErr) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(err))
                BacktestCommand(Args(argv)).run()
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
            }
        val csv = report.resolve("trades.csv")
        // Timestamp through realized P&L; order and leg ids differ by design (a member mints its own).
        val trades =
            if (Files.exists(csv)) Files.readAllLines(csv).map { it.split(",").take(9).joinToString(",") } else listOf()
        return Outcome(code, err.toString() + out, trades)
    }

    @Test
    fun `a DYNAMIC set whose members never change trades exactly like the set resolved once at start`() {
        val once = backtest("BACKTEST:* WHERE category = Metals TOP 1 BY symbol")
        val dynamic = backtest("BACKTEST:* WHERE category = Metals TOP 1 BY symbol DYNAMIC")

        assertThat(dynamic.code).describedAs(dynamic.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(dynamic.err).contains("qkt: note: symbol set 'm': DYNAMIC over 1 candidate(s): XAUUSD")
        assertThat(once.trades.size).isGreaterThan(2)
        assertThat(dynamic.trades).containsExactlyElementsOf(once.trades)
    }

    @Test
    fun `a DYNAMIC set no close gives a member, or one inside a union, is refused`() {
        val empty = backtest("BACKTEST:* WHERE category = Crypto TOP 1 BY symbol DYNAMIC")
        assertThat(empty.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(empty.err)
            .contains("symbol set 'm': no BACKTEST instrument passes category = \"Crypto\" at any close of the run")

        val union =
            (
                Dsl.parse(
                    """
                    STRATEGY u VERSION 1
                    SYMBOLS
                        d = BACKTEST:* WHERE kind = event TOP 2 BY symbol DYNAMIC EVERY 1m
                        p = BACKTEST:{X} EVERY 1m
                        both = d + p
                    RULES
                        FOR EACH m IN both DO
                          WHEN m.close > 1 THEN BUY m SIZING 1
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        assertThatThrownBy {
            DynamicSetRuns.plan(union, SymbolSetResolution.FileCatalog(DynamicSetFixtures.rows), 0L, 0L, false) {}
        }.isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage("symbol set 'd' is DYNAMIC, so it cannot be part of a union")
        val fixed = DynamicSetFixtures.ast(DynamicSetFixtures.source.replace(" DYNAMIC", ""))
        assertThat(DynamicSetRuns.plan(fixed, SymbolSetResolution.FileCatalog(emptyList()), 0L, 0L, false)).isNull()
    }
}
