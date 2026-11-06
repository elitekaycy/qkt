package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PendingStacksFileFullTest {
    private fun tier(
        index: Int,
        fired: Boolean,
    ) = PersistedTier(
        index = index,
        mfeThreshold = BigDecimal("2.5"),
        withinMs = 60_000L,
        stackQuantity = BigDecimal("0.05"),
        slDistance = BigDecimal("3"),
        tpDistance = BigDecimal("6"),
        maeRecoverDistance = if (fired) BigDecimal("1.5") else null,
        armedAdverseExtreme = if (fired) BigDecimal("4695") else null,
        fired = fired,
        firedAt = if (fired) 1_234L else null,
        firedLegId = if (fired) "leg-2" else null,
        abandoned = !fired,
    )

    private val state =
        mapOf(
            "leg-1" to
                PersistedTierState(
                    "o-1",
                    listOf(tier(0, fired = true), tier(1, fired = false)),
                    1_000L,
                    90_000L,
                ),
            "leg-9" to PersistedTierState("o-9", listOf(tier(0, fired = false))),
        )

    @Test
    fun `tiers with and without their optional progress survive a restart`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).savePendingStacks("alpha", state)

        assertThat(FileStatePersistor(tmp).loadPendingStacks("alpha")).isEqualTo(state)
    }

    @Test
    fun `a pending stacks file from another schema version is rejected`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).savePendingStacks("alpha", state)
        val file = tmp.resolve("alpha/pending-stacks.json")
        file.writeText(file.readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

        assertThatThrownBy { FileStatePersistor(tmp).loadPendingStacks("alpha") }
            .hasMessageContaining("loadPendingStacks schema mismatch for alpha")
    }
}
