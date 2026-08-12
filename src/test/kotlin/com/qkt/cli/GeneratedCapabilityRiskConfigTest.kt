package com.qkt.cli

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.marketdata.Tick
import com.qkt.risk.HaltRules
import com.qkt.strategy.Strategy
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GeneratedCapabilityRiskConfigTest {
    @Test
    fun `generated capability strategies receive global strategy and book risk`(
        @TempDir tempDir: Path,
    ) {
        val movingAverages = setOf("EMA", "SMA", "WMA", "DEMA", "TEMA", "HMA")
        val strategies =
            (FuncRegistry.names() + movingAverages)
                .map { "generated_${it.lowercase()}" }
                .sorted()
        val yaml =
            buildString {
                appendLine("risk:")
                appendLine("  max_daily_loss: \"25\"")
                appendLine("  max_order_qty: \"0.01\"")
                appendLine("  max_order_notional: \"2500\"")
                appendLine("  measured_usage_hours: \"720\"")
                appendLine("  measured_usage_max_qty: \"0.01\"")
                appendLine("  per_strategy:")
                strategies.forEach { strategy ->
                    appendLine("    $strategy:")
                    appendLine("      max_daily_loss: \"1\"")
                    appendLine("      max_position_size: \"0.01\"")
                    appendLine("      max_open_positions: 1")
                    appendLine("      max_trades_per_day: 1")
                }
                appendLine("book_risk:")
                appendLine("  capital: \"100000\"")
                appendLine("  limits:")
                appendLine("    max_gross_exposure: \"0.05\"")
                appendLine("    max_net_exposure: \"0.05\"")
                appendLine("    max_symbol_concentration: \"1.0\"")
            }
        val path = tempDir.resolve("qkt.config.yaml")
        Files.writeString(path, yaml)

        val config = Config.load(path)

        assertThat(config.maxOrderQty).isEqualByComparingTo("0.01")
        assertThat(config.measuredUsageMaxQty).isEqualByComparingTo("0.01")
        assertThat(config.perStrategyRisk.keys).containsExactlyElementsOf(strategies)
        config.perStrategyRisk.values.forEach { risk ->
            assertThat(risk.maxPositionSize).isEqualByComparingTo("0.01")
            assertThat(risk.maxOpenPositions).isEqualTo(1)
            assertThat(risk.maxTradesPerDay).isEqualTo(1)
        }
        val book = config.bookRisk!!
        val capital = requireNotNull(book.capital)
        assertThat(capital).isEqualByComparingTo("100000")
        assertThat(book.limits!!.maxGrossExposure).isEqualByComparingTo("0.05")
        assertThat(book.limits!!.maxNetExposure).isEqualByComparingTo("0.05")

        val compiled =
            strategies.map { strategy ->
                val strategyPath = tempDir.resolve("$strategy.qkt")
                Files.writeString(strategyPath, strategySource(strategy))
                strategy to compile(strategyPath)
            }
        val result =
            Backtest(
                strategies = compiled,
                haltRules =
                    HaltRules.standard(
                        maxDailyLoss = config.maxDailyLoss,
                        startingBalance = capital,
                    ),
                ticks =
                    listOf(
                        Tick("BACKTEST:X", Money.of("10"), 0L),
                        Tick("BACKTEST:X", Money.of("10"), 60_000L),
                        Tick("BACKTEST:X", Money.of("10"), 120_000L),
                        Tick("BACKTEST:X", Money.of("10"), 180_000L),
                    ),
                candleWindow = TimeWindow.ONE_MINUTE,
                startingBalance = capital,
                startingBalances = strategies.associateWith { capital },
                strategyRiskLimits = config.perStrategyRisk.mapValues { (_, risk) -> risk.toLimits() },
                bookCapital = capital,
                tradedSymbols = listOf("BACKTEST:X"),
                bookRiskConfig = book,
                maxOrderQty = config.maxOrderQty,
                maxOrderNotional = config.maxOrderNotional,
                priceCollarFrac = config.priceCollarFrac,
            ).run()

        assertThat(result.rejections).isEmpty()
        assertThat(result.trades).hasSize(strategies.size)
        assertThat(result.trades.map { it.strategyId }).containsExactlyInAnyOrderElementsOf(strategies)
        assertThat(result.trades.map { it.trade.quantity }).allMatch { it.compareTo(Money.of("0.01")) == 0 }
        assertThat(result.bookRisk).isNotNull
        assertThat(result.bookRisk!!.series).isNotEmpty
    }

    private fun strategySource(name: String): String =
        """
        STRATEGY $name VERSION 1
        SYMBOLS
          s = BACKTEST:X EVERY 1m
        RULES
          WHEN s.close > 0 AND POSITION.s = 0
          THEN BUY s SIZING 0.01
        """.trimIndent()

    private fun compile(path: Path): Strategy =
        when (val parsed = Dsl.parseFile(path)) {
            is ParseResult.Success -> AstCompiler().compile(parsed.value)
            is ParseResult.Failure -> error(parsed.errors.joinToString("\n") { it.message })
        }
}
