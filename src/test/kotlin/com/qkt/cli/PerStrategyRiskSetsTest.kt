package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.risk.SetCaps
import com.qkt.risk.SetRiskLimits
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PerStrategyRiskSetsTest {
    private fun load(
        tmp: Path,
        yaml: String,
    ): Config = Config.load(tmp.resolve("qkt.config.yaml").also { Files.writeString(it, yaml.trimIndent()) })

    @Test
    fun `sets caps parse per set alias beside the other per-strategy keys`(
        @TempDir tmp: Path,
    ) {
        val cfg =
            load(
                tmp,
                """
                risk:
                  per_strategy:
                    rotation:
                      max_open_positions: 9
                      cooldown_after_loss_ms: 5000
                      sets:
                        stocks: { max_open_positions: 5, max_position_size: "2000" }
                        fx:
                          max_open_positions: 2
                    plain: {}
                """,
            )
        val rotation = cfg.perStrategyRisk.getValue("rotation")
        assertThat(rotation.maxOpenPositions).isEqualTo(9)
        assertThat(rotation.cooldownAfterLossMs).isEqualTo(5000L)
        assertThat(rotation.sets).isEqualTo(
            mapOf(
                "stocks" to SetCaps(maxOpenPositions = 5, maxPositionSize = BigDecimal("2000")),
                "fx" to SetCaps(maxOpenPositions = 2),
            ),
        )
        assertThat(cfg.perStrategyRisk.getValue("plain").sets).isEmpty()
        assertThat(PerStrategyRisk.parseAll(null)).isEmpty()
        assertThat(PerStrategyRisk.parseAll(mapOf("max_daily_loss" to "1"))).isEmpty()
    }

    @Test
    fun `a set cap block must be a map of known keys`(
        @TempDir tmp: Path,
    ) {
        assertThatThrownBy { load(tmp, "risk:\n  per_strategy:\n    r:\n      sets:\n        stocks: 5") }
            .hasMessageContaining("risk.per_strategy sets.stocks must be a key-value block")
        assertThatThrownBy {
            load(
                tmp,
                "risk:\n  per_strategy:\n    r:\n      sets:\n        stocks: { max_members: 5 }",
            )
        }.hasMessageContaining("unknown risk.per_strategy sets.stocks key(s): max_members")
        assertThatThrownBy { load(tmp, "risk:\n  per_strategy:\n    r:\n      set_caps: {}") }
            .hasMessageContaining("unknown risk.per_strategy.r key(s): set_caps")
    }

    @Test
    fun `limits bind each set cap to the strategy's members and refuse an undeclared set`() {
        val risk = PerStrategyRisk(maxOpenPositions = 3, sets = mapOf("stocks" to SetCaps(maxOpenPositions = 2)))
        val limits = risk.toLimits(mapOf("stocks" to setOf("ALPACA:AAPL", "ALPACA:MSFT")))
        assertThat(limits.maxOpenPositions).isEqualTo(3)
        assertThat(limits.sets).isEqualTo(mapOf("stocks" to SetRiskLimits(setOf("ALPACA:AAPL", "ALPACA:MSFT"), 2)))
        assertThat(PerStrategyRisk(maxDailyLoss = BigDecimal.ONE).toLimits().sets).isEmpty()
        assertThatThrownBy { risk.toLimits() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("sets.stocks: the strategy declares no symbol set 'stocks'")
    }

    @Test
    fun `a backtest reads set members from the parsed strategy`() {
        val ast =
            (
                Dsl.parse(
                    """
                    STRATEGY rotation VERSION 1
                    SYMBOLS
                        stocks = ALPACA:{AAPL, MSFT} EVERY 1h
                    RULES
                        FOR EACH m IN stocks DO WHEN m.close > 0 THEN BUY m SIZING 1
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val caps =
            mapOf(
                "rotation" to PerStrategyRisk(sets = mapOf("stocks" to SetCaps(maxPositionSize = BigDecimal("7")))),
            )
        val limits = backtestStrategyRiskLimits(caps, ast)
        assertThat(
            limits
                .getValue("rotation")
                .sets
                .getValue("stocks")
                .members,
        ).containsExactlyInAnyOrder("ALPACA:AAPL", "ALPACA:MSFT")
        val wrong = mapOf("rotation" to PerStrategyRisk(sets = mapOf("fx" to SetCaps(maxOpenPositions = 1))))
        assertThatThrownBy { backtestStrategyRiskLimits(wrong, ast) }
            .hasMessage("risk.per_strategy.rotation.sets.fx: the strategy declares no symbol set 'fx'")
    }
}
