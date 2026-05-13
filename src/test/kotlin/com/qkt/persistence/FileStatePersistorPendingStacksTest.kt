package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorPendingStacksTest {
    private fun tier(
        idx: Int,
        threshold: String,
        fired: Boolean,
        firedAt: Long? = null,
        firedLegId: String? = null,
    ) = PersistedTier(
        index = idx,
        mfeThreshold = BigDecimal(threshold),
        withinMs = 1_800_000L,
        stackQuantity = BigDecimal("0.06"),
        slDistance = BigDecimal("200"),
        tpDistance = BigDecimal("2000"),
        fired = fired,
        firedAt = firedAt,
        firedLegId = firedLegId,
    )

    @Test
    fun `savePendingStacks then loadPendingStacks round-trips`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val state =
            mapOf(
                "leg-1" to
                    PersistedTierState(
                        primaryClientOrderId = "c-1",
                        tiers =
                            listOf(
                                tier(0, "10", fired = true, firedAt = 1500L, firedLegId = "leg-2"),
                                tier(1, "20", fired = false),
                                tier(2, "30", fired = false),
                            ),
                    ),
            )
        persistor.savePendingStacks("hedge", state)
        val loaded = persistor.loadPendingStacks("hedge")
        assertThat(loaded).hasSize(1)
        assertThat(loaded["leg-1"]!!.primaryClientOrderId).isEqualTo("c-1")
        assertThat(loaded["leg-1"]!!.tiers).hasSize(3)
        assertThat(loaded["leg-1"]!!.tiers[0].fired).isTrue
        assertThat(loaded["leg-1"]!!.tiers[0].firedAt).isEqualTo(1500L)
        assertThat(loaded["leg-1"]!!.tiers[0].firedLegId).isEqualTo("leg-2")
        assertThat(loaded["leg-1"]!!.tiers[1].fired).isFalse
    }

    @Test
    fun `multi-primary pendingStacks roundtrips`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val state =
            mapOf(
                "leg-1" to
                    PersistedTierState("c-1", listOf(tier(0, "10", fired = true, firedAt = 1L, firedLegId = "leg-2"))),
                "leg-3" to PersistedTierState("c-3", listOf(tier(0, "5", fired = false))),
            )
        persistor.savePendingStacks("hedge", state)
        val loaded = persistor.loadPendingStacks("hedge")
        assertThat(loaded.keys).containsExactlyInAnyOrder("leg-1", "leg-3")
    }

    @Test
    fun `loadPendingStacks returns empty when file missing`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPendingStacks("absent")).isEmpty()
    }

    @Test
    fun `loadPendingStacks returns empty on version mismatch`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("pending-stacks.json"),
            """{"version":99,"strategyId":"hedge","perPrimary":[]}""",
        )
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPendingStacks("hedge")).isEmpty()
    }
}
