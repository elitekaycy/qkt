package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateFileCorruptionTest {
    private val excursion =
        PersistedExcursion(
            legId = "leg-1",
            side = Side.SELL,
            entryPrice = BigDecimal("4700"),
            mfe = BigDecimal("12"),
            mae = BigDecimal("3"),
            adverseExtremePrice = null,
        )

    private fun stateFile(
        root: Path,
        suffix: String,
    ): Path = Files.walk(root).use { s -> s.filter { it.name.endsWith(suffix) }.findFirst().get() }

    @Test
    fun `an unparseable excursion file restores as no marks`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveExcursion("alpha", "XAUUSD", excursion)
        stateFile(tmp, "excursion.json").writeText("{not json")

        assertThat(FileStatePersistor(tmp).loadExcursion("alpha", "XAUUSD")).isNull()
    }

    @Test
    fun `an excursion file from another schema version restores as no marks`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveExcursion("alpha", "XAUUSD", excursion)
        val file = stateFile(tmp, "excursion.json")
        assertThat(FileStatePersistor(tmp).loadExcursion("alpha", "XAUUSD")).isEqualTo(excursion)
        file.writeText(file.readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

        assertThat(FileStatePersistor(tmp).loadExcursion("alpha", "XAUUSD")).isNull()
    }

    @Test
    fun `a write slower than the threshold is counted as slow`(
        @TempDir tmp: Path,
    ) {
        val writer = StateFileWriter(tmp, slowWriteThresholdMs = -1L)
        writer.write("alpha", "a.json", "{}")

        assertThat(writer.slowWrites.get()).isEqualTo(1L)
        assertThat(writer.totalWrites.get()).isEqualTo(1L)
        assertThat(writer.read("alpha", "a.json")).isEqualTo("{}")
    }
}
