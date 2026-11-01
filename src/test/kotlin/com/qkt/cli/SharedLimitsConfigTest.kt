package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `risk.hard_daily_loss` at config load, what the config states, and the default-limit warning (#1565). */
class SharedLimitsConfigTest {
    private fun load(
        tmp: Path,
        yaml: String,
    ): Config {
        val file = tmp.resolve("qkt.config.yaml")
        Files.writeString(file, yaml.trimIndent())
        return Config.load(file)
    }

    private val facts = { cfg: Config, mixed: Boolean ->
        EffectiveConfigFacts(cfg, connectorTypes = emptySet(), daemonMixed = { mixed })
    }

    @Test
    fun `an absent hard limit is off and a set one is read`(
        @TempDir tmp: Path,
    ) {
        assertThat(load(tmp, "source: tv").hardDailyLoss).isNull()
        val c = load(tmp, "risk:\n  max_daily_loss: 8\n  hard_daily_loss: 12.5")
        assertThat(c.hardDailyLoss).isEqualByComparingTo(BigDecimal("12.5"))
    }

    @Test
    fun `a hard limit at max_daily_loss is allowed and an unset max is not compared`(
        @TempDir tmp: Path,
    ) {
        load(tmp, "risk:\n  max_daily_loss: 8\n  hard_daily_loss: 8")
        assertThat(load(tmp, "risk:\n  hard_daily_loss: 500").hardDailyLoss).isEqualByComparingTo("500")
    }

    @Test
    fun `a zero, negative, unreadable or too small hard limit fails the load`(
        @TempDir tmp: Path,
    ) {
        for (bad in listOf("0", "-1", "lots")) {
            assertThatThrownBy { load(tmp, "risk:\n  hard_daily_loss: $bad") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("risk.hard_daily_loss")
        }
        assertThatThrownBy { load(tmp, "risk:\n  max_daily_loss: 8\n  hard_daily_loss: 7") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("max_daily_loss")
    }

    @Test
    fun `a live run on the built-in daily loss default is warned, naming the fix`(
        @TempDir tmp: Path,
    ) {
        val warning = defaultDailyLossWarning(load(tmp, "source: tv"), tradesThroughVenue = true)
        assertThat(warning).contains("1000").contains("risk.max_daily_loss")
    }

    @Test
    fun `a configured limit or a run with no venue is not warned`(
        @TempDir tmp: Path,
    ) {
        assertThat(defaultDailyLossWarning(load(tmp, "risk:\n  max_daily_loss: 8"), true)).isNull()
        assertThat(defaultDailyLossWarning(load(tmp, "risk:\n  max_daily_loss: 0"), true)).isNull()
        assertThat(defaultDailyLossWarning(load(tmp, "source: tv"), false)).isNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun risk(facts: Map<String, Any?>) = facts["risk"] as Map<String, Any?>

    @Test
    fun `effective config states the hard limit and what max_daily_loss applies to`(
        @TempDir tmp: Path,
    ) {
        val cfg = load(tmp, "risk:\n  max_daily_loss: 8\n  hard_daily_loss: 12")
        val solo = risk(facts(cfg, false).standalone(emptyList(), null))
        assertThat(solo["hardDailyLoss"]).isEqualTo(mapOf("value" to BigDecimal("12"), "source" to "configured"))
        assertThat(solo["maxDailyLossAppliesTo"]).isEqualTo("daemon")

        fun child(mixed: Boolean) =
            risk(facts(cfg, mixed).portfolioChild(emptyList(), null, "bk", BigDecimal("1000"), null, null, false))
        assertThat(child(false)["maxDailyLossAppliesTo"]).isEqualTo("book")
        assertThat(child(true)["maxDailyLossAppliesTo"]).isEqualTo("daemon")

        val off = risk(facts(load(tmp, "source: tv"), false).standalone(emptyList(), null))
        assertThat(off["hardDailyLoss"]).isEqualTo(mapOf("value" to null, "source" to "default"))
    }
}
