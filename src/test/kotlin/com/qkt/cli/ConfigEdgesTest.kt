package com.qkt.cli

import com.qkt.risk.rules.PreTradeControls
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigEdgesTest {
    @TempDir
    lateinit var tmp: Path

    private fun load(yaml: String): Config =
        Config.load(tmp.resolve("qkt.config.yaml").also { Files.writeString(it, yaml.trimIndent()) })

    @Test
    fun `journal compression and the disk floor read their state keys and refuse negatives`() {
        val cfg = load("state:\n  journal_compress_after_days: 3\n  disk_free_alert_gb: 0\n")
        assertThat(cfg.journalCompressAfterDays).isEqualTo(3)
        assertThat(cfg.diskFreeAlertGb).isZero()

        val bad = load("state:\n  journal_compress_after_days: -1\n  disk_free_alert_gb: lots\n")
        assertThatThrownBy { bad.journalCompressAfterDays }
            .hasMessage("state.journal_compress_after_days must be a non-negative integer; got '-1'")
        assertThatThrownBy { bad.diskFreeAlertGb }
            .hasMessage("state.disk_free_alert_gb must be a non-negative integer; got 'lots'")
    }

    @Test
    fun `the pre-trade defaults are the risk rules' own`() {
        assertThat(Config.DEFAULT_MAX_ORDER_QTY).isEqualByComparingTo(PreTradeControls.DEFAULT_MAX_ORDER_QTY)
        assertThat(Config.DEFAULT_MAX_ORDER_NOTIONAL).isEqualByComparingTo(PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL)
        assertThat(Config.DEFAULT_PRICE_COLLAR_FRAC).isEqualByComparingTo(PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC)
    }

    @Test
    fun `a file that is not a mapping loads the built-in defaults`() {
        val cfg = load("- just\n- a list\n")

        assertThat(cfg.source).isEqualTo("tv")
        assertThat(cfg.dataRoot).isEqualTo("./data")
        assertThat(cfg.brokers).isEmpty()
    }

    @Test
    fun `a per-strategy risk entry that is not a block is refused`() {
        assertThatThrownBy { load("risk:\n  per_strategy:\n    alpha: 5\n") }
            .hasMessage("risk.per_strategy.alpha must be a key-value block")
    }

    @Test
    fun `an unknown loss streak halt scope is refused`() {
        assertThatThrownBy { load("risk:\n  per_strategy:\n    alpha:\n      loss_streak_halt_scope: weekly\n") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown loss_streak_halt_scope 'weekly' (valid: daily, persistent)")
    }

    @Test
    fun `broker aliases restrictions and instrument overrides keep blank values as empty strings`() {
        val cfg =
            load(
                """
                brokers:
                  exness:
                    type: mt5
                    gateway_url: http://gw
                    aliases:
                      GOLD: XAUUSD
                      EMPTY:
                    capability_restrictions:
                      - no_hedging
                      -
                    instrument_overrides:
                      XAUUSD:
                        digits: 2
                        point_size:
                      EURUSD: not-a-block
                  plain:
                    type: mt5
                """,
            )

        assertThat(cfg.brokerAliases).isEqualTo(mapOf("exness" to mapOf("GOLD" to "XAUUSD", "EMPTY" to "")))
        assertThat(cfg.brokerCapabilityRestrictions).isEqualTo(mapOf("exness" to listOf("no_hedging", "")))
        assertThat(cfg.brokerInstrumentOverrides)
            .isEqualTo(
                mapOf(
                    "exness" to mapOf("XAUUSD" to mapOf("digits" to "2", "point_size" to ""), "EURUSD" to emptyMap()),
                ),
            )
    }

    @Test
    fun `a de-risk ladder with no complete rung is no de-risk at all`() {
        val cfg =
            load(
                """
                book_risk:
                  capital: 10000
                  de_risk:
                    ladder:
                      - drawdown: 0.05
                      - factor: 0.5
                      - not-a-rung
                """,
            )

        assertThat(cfg.bookRisk?.capital).isEqualByComparingTo("10000")
        assertThat(cfg.bookRisk?.deRisk).isNull()
    }
}
