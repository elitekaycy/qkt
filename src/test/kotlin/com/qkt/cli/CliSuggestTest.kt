package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CliSuggestTest {
    @Test
    fun `near misses resolve to the right name`() {
        assertThat(CliSuggest.suggest("backtes", listOf("backtest", "backtester"))).isEqualTo("backtest")
        assertThat(CliSuggest.suggest("frm", listOf("from", "to", "bars"))).isEqualTo("from")
        assertThat(CliSuggest.suggest("preflight", listOf("preflight", "flight"))).isEqualTo("preflight")
    }

    @Test
    fun `wild guesses resolve to nothing`() {
        assertThat(CliSuggest.suggest("frobnicate", listOf("backtest", "sweep", "daemon"))).isNull()
        assertThat(CliSuggest.suggest("zzz", listOf("from", "to"))).isNull()
    }

    @Test
    fun `ties break alphabetically for stable output`() {
        assertThat(CliSuggest.suggest("bat", listOf("bot", "bar", "run"))).isEqualTo("bar")
    }

    @Test
    fun `command and flag helpers scope their candidates`() {
        assertThat(CliHelp.suggestCommand("backtes")).isEqualTo("backtest")
        assertThat(CliHelp.suggestCommand("frobnicate")).isNull()
        assertThat(CliHelp.suggestFlag("backtest", "--frm")).isEqualTo("--from")
        assertThat(CliHelp.suggestFlag("backtest", "--zzz")).isNull()
    }

    @Test
    fun `command help carries usage and described flags`() {
        val help = CliHelp.forCommand("backtest")
        assertThat(help).contains("Usage: qkt backtest <strategy.qkt> --from DATE --to DATE [options]")
        assertThat(help).contains("--from")
        assertThat(help).contains("window start")
    }
}
