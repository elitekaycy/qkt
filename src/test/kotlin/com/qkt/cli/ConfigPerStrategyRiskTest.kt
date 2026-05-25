package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigPerStrategyRiskTest {
    private fun writeConfig(
        tmp: Path,
        yaml: String,
    ): Path {
        val p = tmp.resolve("qkt.config.yaml")
        Files.writeString(p, yaml.trimIndent())
        return p
    }

    @Test
    fun `absent per_strategy block parses to empty map`(
        @TempDir tmp: Path,
    ) {
        val cfg =
            Config.load(
                writeConfig(
                    tmp,
                    """
                    risk:
                      max_daily_loss: "1000"
                    """,
                ),
            )
        assertThat(cfg.perStrategyRisk).isEmpty()
        assertThat(cfg.maxDailyLoss).isEqualByComparingTo("1000")
    }

    @Test
    fun `per_strategy block with one strategy parses every field`(
        @TempDir tmp: Path,
    ) {
        val cfg =
            Config.load(
                writeConfig(
                    tmp,
                    """
                    risk:
                      max_daily_loss: "1000"
                      per_strategy:
                        ema_cross:
                          max_daily_loss: "500"
                          max_position_size: "1.0"
                          max_open_positions: 3
                    """,
                ),
            )
        val ema = cfg.perStrategyRisk["ema_cross"]
        assertThat(ema).isNotNull
        assertThat(ema!!.maxDailyLoss).isEqualByComparingTo("500")
        assertThat(ema.maxPositionSize).isEqualByComparingTo("1.0")
        assertThat(ema.maxOpenPositions).isEqualTo(3)
    }

    @Test
    fun `per_strategy supports partial overrides`(
        @TempDir tmp: Path,
    ) {
        val cfg =
            Config.load(
                writeConfig(
                    tmp,
                    """
                    risk:
                      per_strategy:
                        only_loss:
                          max_daily_loss: "200"
                    """,
                ),
            )
        val s = cfg.perStrategyRisk["only_loss"]
        assertThat(s?.maxDailyLoss).isEqualByComparingTo("200")
        assertThat(s?.maxPositionSize).isNull()
        assertThat(s?.maxOpenPositions).isNull()
    }

    @Test
    fun `per_strategy with multiple strategies preserves all entries`(
        @TempDir tmp: Path,
    ) {
        val cfg =
            Config.load(
                writeConfig(
                    tmp,
                    """
                    risk:
                      per_strategy:
                        a:
                          max_daily_loss: "100"
                        b:
                          max_position_size: "0.5"
                    """,
                ),
            )
        assertThat(cfg.perStrategyRisk.keys).containsExactlyInAnyOrder("a", "b")
        assertThat(cfg.perStrategyRisk["a"]?.maxDailyLoss).isEqualByComparingTo("100")
        assertThat(cfg.perStrategyRisk["b"]?.maxPositionSize).isEqualByComparingTo("0.5")
    }

    @Test
    fun `PerStrategyRisk rejects non-positive caps`() {
        assertThatThrownBy { PerStrategyRisk(maxDailyLoss = BigDecimal.ZERO) }
            .hasMessageContaining("maxDailyLoss")
        assertThatThrownBy { PerStrategyRisk(maxPositionSize = BigDecimal("-1")) }
            .hasMessageContaining("maxPositionSize")
        assertThatThrownBy { PerStrategyRisk(maxOpenPositions = 0) }
            .hasMessageContaining("maxOpenPositions")
    }

    @Test
    fun `PerStrategyRisk with all-null fields is allowed (no-op override)`() {
        val r = PerStrategyRisk()
        assertThat(r.maxDailyLoss).isNull()
        assertThat(r.maxPositionSize).isNull()
        assertThat(r.maxOpenPositions).isNull()
    }
}
