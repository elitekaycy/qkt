package com.qkt.golden

import com.qkt.backtest.TickResolvedParityFixtures
import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Design §3.8 and §5: a picked symbol set with a bracketless `FOR EACH` is sugar for the same rules written out per
 * symbol by hand, so the two strategies must produce byte-identical trade tapes over the same data.
 */
class PickedSetEquivalenceTest {
    @TempDir
    lateinit var tmp: Path

    private val picked =
        """
        STRATEGY golden_pair VERSION 1
        SYMBOLS
            pair = BACKTEST:{XAUUSD, USDJPY} EVERY 15m
        RULES
            FOR EACH m IN pair DO
              WHEN ema(m.close, 3) CROSSES ABOVE ema(m.close, 9)
              THEN BUY m SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
            FOR EACH m IN pair DO
              WHEN ema(m.close, 3) CROSSES BELOW ema(m.close, 9) AND POSITION.m > 0
              THEN CLOSE m
        """.trimIndent()

    private val byHand =
        """
        STRATEGY golden_pair VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 15m
            jpy  = BACKTEST:USDJPY EVERY 15m
        RULES
            WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
            THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
            WHEN ema(jpy.close, 3) CROSSES ABOVE ema(jpy.close, 9)
            THEN BUY jpy SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
            WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9) AND POSITION.gold > 0
            THEN CLOSE gold
            WHEN ema(jpy.close, 3) CROSSES BELOW ema(jpy.close, 9) AND POSITION.jpy > 0
            THEN CLOSE jpy
        """.trimIndent()

    @Test
    fun `a picked set backtests to the same trades as the rules written out per symbol`() {
        val dataRoot = tmp.resolve("data")
        TickResolvedParityFixtures.seedTicks(dataRoot, days = 3)
        GoldenRun.seedSine(dataRoot, "USDJPY", base = 150.0, amplitude = 0.6, decimals = 3)
        val (pickedReport, pickedTrades) = run(dataRoot, "picked", picked)
        val (handReport, handTrades) = run(dataRoot, "by-hand", byHand)
        assertThat(pickedTrades.lines().size).describedAs("the pair must trade").isGreaterThan(2)
        assertThat(pickedTrades.lines().filter { "XAUUSD" in it }).isNotEmpty()
        assertThat(pickedTrades.lines().filter { "USDJPY" in it }).isNotEmpty()
        assertThat(pickedTrades).isEqualTo(handTrades)
        // The report names streams by alias (`pair.XAUUSD` against `gold`), so only its figures must agree.
        assertThat(pickedReport.lines().filter { "\"totalPnL\"" in it || "\"trades\"" in it })
            .isEqualTo(handReport.lines().filter { "\"totalPnL\"" in it || "\"trades\"" in it })
    }

    private val ranked =
        """
        STRATEGY golden_rank VERSION 1
        SYMBOLS
            pair = BACKTEST:{XAUUSD, USDJPY} EVERY 15m
        RULES
            FOR EACH m IN pair DO
              WHEN RANK_OF(ema(m.close, 3) / ema(m.close, 9), pair) = 1 AND POSITION.m = 0
               AND COUNT_OF(m.close > 0, pair) = 2
              THEN BUY m SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
            FOR EACH m IN pair DO
              WHEN ema(m.close, 3) / ema(m.close, 9) < AVG_OF(ema(m.close, 3) / ema(m.close, 9), pair)
               AND POSITION.m > 0
              THEN CLOSE m
        """.trimIndent()

    private val rankedByHand =
        """
        STRATEGY golden_rank VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 15m
            jpy  = BACKTEST:USDJPY EVERY 15m
        LET g = ema(gold.close, 3) / ema(gold.close, 9)
        LET j = ema(jpy.close, 3) / ema(jpy.close, 9)
        RULES
            WHEN RANK_OF(g, j) = 1 AND POSITION.gold = 0 AND gold.close > 0 AND jpy.close > 0
            THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
            WHEN RANK_OF(j, g) = 1 AND POSITION.jpy = 0 AND gold.close > 0 AND jpy.close > 0
            THEN BUY jpy SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
            WHEN g < (g + j) / 2 AND POSITION.gold > 0
            THEN CLOSE gold
            WHEN j < (g + j) / 2 AND POSITION.jpy > 0
            THEN CLOSE jpy
        """.trimIndent()

    @Test
    fun `cross-member functions over a picked set trade like the same cross-section written by hand`() {
        val dataRoot = tmp.resolve("data")
        TickResolvedParityFixtures.seedTicks(dataRoot, days = 3)
        GoldenRun.seedSine(dataRoot, "USDJPY", base = 150.0, amplitude = 0.6, decimals = 3)
        val (_, setTrades) = run(dataRoot, "ranked", ranked)
        val (_, handTrades) = run(dataRoot, "ranked-by-hand", rankedByHand)
        assertThat(setTrades.lines().filter { "XAUUSD" in it }).isNotEmpty()
        assertThat(setTrades.lines().filter { "USDJPY" in it }).isNotEmpty()
        assertThat(setTrades).isEqualTo(handTrades)
    }

    private fun run(
        dataRoot: Path,
        name: String,
        source: String,
    ): Pair<String, String> {
        val dir = Files.createDirectories(tmp.resolve(name))
        val strategy = dir.resolve("$name.qkt").also { Files.writeString(it, source) }
        val reportDir = dir.resolve("report")
        val out = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategy.toString(),
                            "--from",
                            "2024-01-02",
                            "--to",
                            "2024-01-05",
                            "--data-root",
                            dataRoot.toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--json",
                            "--report-dir",
                            reportDir.toString(),
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(original)
            }
        check(code == ExitCodes.SUCCESS) { "$name exited $code: $out" }
        return GoldenRun.normalize(out.toString()) to Files.readString(reportDir.resolve("trades.csv"))
    }
}
