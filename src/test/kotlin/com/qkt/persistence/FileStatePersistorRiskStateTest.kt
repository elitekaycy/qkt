package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorRiskStateTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `risk reference anchors round trip through json`() {
        val persistor = FileStatePersistor(tempDir)
        val state =
            PersistedRiskState(
                epochDay = 100L,
                realizedToday = BigDecimal("-300"),
                perStrategyRealizedToday = mapOf("s1" to BigDecimal("-300")),
                halted = false,
                haltReason = null,
                haltScope = "PERSISTENT",
                haltEpochDay = 0L,
                strategyHalts = emptyList(),
                globalRealizedTotal = BigDecimal("-1300"),
                dailyDrawdownEpochDay = 100L,
                globalDailyDrawdownRef = BigDecimal("10000"),
                perStrategyDailyDrawdownRefs = mapOf("s1" to BigDecimal("5000")),
                peakTotalEquity = BigDecimal("11250"),
                perStrategyPeakEquity = mapOf("s1" to BigDecimal("5400")),
                pacerEntryFillsByStrategy = mapOf("s1" to listOf(1L, 2L)),
                pacerLossStreakByStrategy = mapOf("s1" to 2),
                pacerLastLossAtByStrategy = mapOf("s1" to 2L),
            )

        persistor.saveRiskState("s1", state)

        assertThat(persistor.loadRiskState("s1")).isEqualTo(state)
    }

    @Test
    fun `legacy risk json loads with empty anchor defaults`() {
        val strategyDir = tempDir.resolve("s1")
        Files.createDirectories(strategyDir)
        Files.writeString(
            strategyDir.resolve("risk-state.json"),
            """{"version":1,"strategyId":"s1","epochDay":100,"realizedToday":"-300",""" +
                """"perStrategyRealizedToday":{},"halted":false,"haltReason":null,""" +
                """"haltScope":"PERSISTENT","haltEpochDay":0,"strategyHalts":[]}""",
        )

        val loaded = FileStatePersistor(tempDir).loadRiskState("s1")!!

        assertThat(loaded.globalRealizedTotal).isNull()
        assertThat(loaded.perStrategyDailyDrawdownRefs).isEmpty()
        assertThat(loaded.pacerEntryFillsByStrategy).isEmpty()
    }

    @Test
    fun `missing risk state is a fresh start`() {
        assertThat(FileStatePersistor(tempDir).loadRiskState("s1")).isNull()
    }

    @Test
    fun `malformed risk state refuses startup`() {
        writeRiskState("{not-json")

        assertThatThrownBy { FileStatePersistor(tempDir).loadRiskState("s1") }
            .isInstanceOf(kotlinx.serialization.SerializationException::class.java)
    }

    @Test
    fun `unsupported risk state schema refuses startup`() {
        writeRiskState(
            """{"version":2,"strategyId":"s1","epochDay":100,"realizedToday":"-300",""" +
                """"perStrategyRealizedToday":{},"halted":true,"haltReason":"daily loss",""" +
                """"haltScope":"DAILY","haltEpochDay":100,"strategyHalts":[]}""",
        )

        assertThatIllegalArgumentException()
            .isThrownBy { FileStatePersistor(tempDir).loadRiskState("s1") }
            .withMessage("loadRiskState schema mismatch for s1: 2 != 1")
    }

    private fun writeRiskState(contents: String) {
        val strategyDir = tempDir.resolve("s1")
        Files.createDirectories(strategyDir)
        Files.writeString(strategyDir.resolve("risk-state.json"), contents)
    }
}
