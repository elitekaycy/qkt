package com.qkt.cli

import com.qkt.accounting.FxMissingPolicy
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.TakeProfitFill
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pins the backtest flag help to what each flag's parser accepts, so the two cannot drift (#1720). */
class BacktestFlagHelpTest {
    private fun help(flag: String): String = BacktestFlagHelp.descriptions.getValue(flag)

    @Test
    fun `tp-fill help names exactly the values the parser accepts and marks the default`() {
        val line = help("tp-fill")
        val values =
            line
                .substringAfter(": ")
                .split(" or ")
                .map { it.removeSuffix(" (default)") }
        assertThat(values.map(TakeProfitFill::fromConfig)).containsExactlyElementsOf(TakeProfitFill.entries)
        assertThat(line).contains("${ExecutionSimulationConfig().takeProfitFill.id} (default)")
        assertThat(line).isEqualTo("take-profit fill model for a gap-crossed target: print (default) or level")
    }

    @Test
    fun `tp-fill parser error lists the same values`() {
        val error = runCatching { TakeProfitFill.fromConfig("mid") }.exceptionOrNull()
        assertThat(error).hasMessage("unknown take-profit fill mode 'mid' (valid: print, level)")
    }

    @Test
    fun `seed help states the fixed default seed, not random`() {
        assertThat(help("seed")).contains("default 42").doesNotContain("random)")
    }

    @Test
    fun `position-mode help names the parser values and its default`() {
        val line = help("position-mode")
        assertThat(line).contains("netting", "hedging", "(default hedging)")
        val modes = PositionAccountingMode.entries.map { it.name.lowercase() }
        assertThat(modes).containsExactlyInAnyOrder("netting", "hedging")
    }

    @Test
    fun `partial-fill help example is a value the parser takes`() {
        val example = help("partial-fill").substringAfter("--partial-fill ").substringBefore(" ")
        assertThat(BigDecimal(example)).isEqualTo(BigDecimal("0.5"))
    }

    @Test
    fun `fx-missing-policy help names values the parser accepts`() {
        assertThat(help("fx-missing-policy")).startsWith("warn or fail")
        assertThat(FxMissingPolicy.fromConfig("warn")).isEqualTo(FxMissingPolicy.WARN)
        assertThat(FxMissingPolicy.fromConfig(null)).isEqualTo(FxMissingPolicy.FAIL)
    }

    @Test
    fun `rank help lists every rank metric`() {
        val listed = help("rank").substringAfter(": ").replace(" or ", ", ").split(", ")
        assertThat(listed).containsExactlyElementsOf(RankMetric.entries.map { it.flag })
    }
}
