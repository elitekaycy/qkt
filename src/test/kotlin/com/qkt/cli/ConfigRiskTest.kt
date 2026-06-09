package com.qkt.cli

import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ConfigRiskTest {
    @Test
    fun `reads drawdown pct as fractions, bases, and per-strategy overrides`() {
        val cfg =
            Config(
                risk =
                    mapOf(
                        "max_drawdown_pct" to "8",
                        "max_daily_drawdown_pct" to "4",
                        "total_dd_basis" to "static",
                        "daily_dd_basis" to "balance",
                    ),
                perStrategyRisk = mapOf("ema" to PerStrategyRisk(maxDrawdownPct = BigDecimal("0.05"))),
            )
        assertThat(cfg.maxDrawdownPct).isEqualByComparingTo(BigDecimal("0.08"))
        assertThat(cfg.maxDailyDrawdownPct).isEqualByComparingTo(BigDecimal("0.04"))
        assertThat(cfg.totalDdBasis).isEqualTo(DrawdownBasis.STATIC)
        assertThat(cfg.dailyDdBasis).isEqualTo(DailyDrawdownBasis.BALANCE)
        assertThat(cfg.perStrategyRisk["ema"]!!.maxDrawdownPct).isEqualByComparingTo(BigDecimal("0.05"))
    }

    @Test
    fun `defaults to static total basis and balance daily basis when unset`() {
        val cfg = Config()
        assertThat(cfg.totalDdBasis).isEqualTo(DrawdownBasis.STATIC)
        assertThat(cfg.dailyDdBasis).isEqualTo(DailyDrawdownBasis.BALANCE)
        assertThat(cfg.maxDrawdownPct).isNull()
        assertThat(cfg.maxDailyDrawdownPct).isNull()
    }

    @Test
    fun `rejects an out-of-range pct and an unknown basis`() {
        assertThatThrownBy { Config(risk = mapOf("max_drawdown_pct" to "150")).maxDrawdownPct }
            .hasMessageContaining("(0, 100]")
        assertThatThrownBy { Config(risk = mapOf("total_dd_basis" to "bogus")).totalDdBasis }
            .hasMessageContaining("unknown total_dd_basis")
    }
}
