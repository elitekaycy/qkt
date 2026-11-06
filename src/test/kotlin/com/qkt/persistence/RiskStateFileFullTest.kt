package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RiskStateFileFullTest {
    private val full =
        PersistedRiskState(
            epochDay = 20_400L,
            realizedToday = BigDecimal("-125.50"),
            perStrategyRealizedToday = mapOf("alpha" to BigDecimal("-100"), "beta" to BigDecimal("-25.50")),
            halted = true,
            haltReason = "daily loss",
            haltScope = "DAILY",
            haltEpochDay = 20_400L,
            strategyHalts = listOf(PersistedStrategyHalt("alpha", "max loss", "DAILY", 20_400L, haltedAtMs = 9L)),
            haltedAtMs = 1_700_000_000_000L,
            globalRealizedTotal = BigDecimal("830.25"),
            dailyDrawdownEpochDay = 20_400L,
            globalDailyDrawdownRef = BigDecimal("10500"),
            perStrategyDailyDrawdownRefs = mapOf("alpha" to BigDecimal("5200")),
            peakTotalEquity = BigDecimal("11000"),
            perStrategyPeakEquity = mapOf("alpha" to BigDecimal("5600")),
            pacerEntryFillsByStrategy = mapOf("alpha" to listOf(1L, 2L)),
            pacerLossStreakByStrategy = mapOf("alpha" to 3),
            pacerLastLossAtByStrategy = mapOf("alpha" to 7L),
            monthKey = 670L,
            realizedMonth = BigDecimal("-300"),
            perStrategyRealizedMonth = mapOf("alpha" to BigDecimal("-300")),
            daemonMixedEpochDay = 20_399L,
        )

    @Test
    fun `a risk state with every optional field set survives a restart`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveRiskState("daemon", full)

        assertThat(FileStatePersistor(tmp).loadRiskState("daemon")).isEqualTo(full)
    }

    @Test
    fun `a risk state file from another schema version is rejected`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveRiskState("daemon", full)
        val file = tmp.resolve("daemon/risk-state.json")
        file.writeText(file.readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

        assertThatThrownBy { FileStatePersistor(tmp).loadRiskState("daemon") }
            .hasMessageContaining("loadRiskState schema mismatch for daemon")
    }
}
