package com.qkt.dsl.portfolio

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.dsl.portfolio.fixture.PortfolioFixtureGenerator
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class PortfolioVariantMatrixTest {
    companion object {
        @JvmStatic
        fun variants() = PortfolioFixtureGenerator.all().map { it.name }
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `every generated variant backtests successfully`(
        name: String,
        @TempDir tmp: Path,
    ) {
        val variant = PortfolioFixtureGenerator.byName(name)
        val portfolioPath = variant.materialize(tmp)

        val args =
            mutableListOf(
                "backtest",
                portfolioPath.toString(),
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-17",
                "--data-root",
                "data/sample",
                "--no-fetch",
                "--allow-incomplete",
                "--config",
                tmp.resolve("qkt.config.yaml").toString(),
                "--json",
            )
        if (variant.expected.capital == null) {
            args.add("--starting-balance")
            args.add("10000")
        }
        val out = ByteArrayOutputStream()
        val orig = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                BacktestCommand(Args(args.toTypedArray())).run()
            } finally {
                System.setOut(orig)
            }

        assertThat(code)
            .withFailMessage("variant '$name' backtest failed; output tail:\n%s", out.toString().takeLast(500))
            .isEqualTo(0)

        val output = out.toString()
        assertThat(output).contains("\"perStrategy\":{")
        val tradeMatch = Regex("\"trades\":(\\d+)").find(output)
        val tradeCount = tradeMatch?.groupValues?.get(1)?.toInt() ?: 0
        assertThat(tradeCount)
            .withFailMessage("variant '$name' placed fewer trades than expected")
            .isGreaterThanOrEqualTo(variant.expected.expectedMinTrades)
    }

    @Test
    fun `generator produces unique stable names`() {
        val names = PortfolioFixtureGenerator.all().map { it.name }
        assertThat(names).doesNotHaveDuplicates()
    }
}
