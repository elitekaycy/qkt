package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorSequencesTest {
    @Test
    fun `sequences round-trip through file persistor`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val state =
            mapOf(
                "sweep" to
                    PersistedSequenceState(
                        name = "sweep",
                        stage = 1,
                        snapshots =
                            listOf(
                                PersistedSequenceSnapshot("swept", BigDecimal("98.50"), 1_000L),
                            ),
                        lastValues = mapOf("swept" to true, "reclaimed" to false),
                        completePulse = false,
                    ),
            )

        persistor.saveSequences("alpha", state)
        val loaded = persistor.loadSequences("alpha")

        assertThat(loaded.keys).containsExactly("sweep")
        assertThat(loaded["sweep"]!!.stage).isEqualTo(1)
        assertThat(loaded["sweep"]!!.snapshots.single().price).isEqualByComparingTo("98.50")
        assertThat(loaded["sweep"]!!.lastValues).containsEntry("swept", true)
    }
}
