package com.qkt.dsl.compile

import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedSequenceSnapshot
import com.qkt.persistence.PersistedSequenceState
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A [SequenceRuntime] bound to a persistor resumes each sequence's saved progress after a restart. */
class SequenceProgressRestoreTest {
    private val stages =
        listOf(
            CompiledSequenceStage("swept", null, CompiledExpr { Value.of(false) }),
            CompiledSequenceStage("reclaimed", null, CompiledExpr { Value.of(false) }),
            CompiledSequenceStage("go", null, CompiledExpr { Value.of(false) }),
        )

    private fun runtime() = SequenceRuntime(listOf(CompiledSequence("sweep", "gold", "BACKTEST:XAUUSD", stages, setOf("gold"))))

    @Test
    fun `persisted stage, snapshots and completion pulse are restored for a known sequence`() {
        val saved =
            PersistedSequenceState(
                name = "sweep",
                stage = 2,
                snapshots =
                    listOf(
                        PersistedSequenceSnapshot("swept", BigDecimal("98.5"), 1_000L),
                        PersistedSequenceSnapshot("reclaimed", BigDecimal("101"), 2_000L),
                    ),
                lastValues = mapOf("swept" to true),
                completePulse = true,
            )
        val persistor =
            object : StatePersistor by NoopStatePersistor() {
                override fun loadSequences(strategyId: String): Map<String, PersistedSequenceState> =
                    if (strategyId == "s1") mapOf("sweep" to saved, "gone" to saved.copy(name = "gone")) else emptyMap()
            }
        val runtime = runtime()

        runtime.bindPersistor("s1", persistor)

        assertThat(runtime.stage("sweep")).isEqualTo(2)
        assertThat(runtime.complete("sweep")).isTrue
        assertThat(runtime.stagePrice("sweep", "reclaimed")).isEqualByComparingTo("101")
        assertThat(runtime.stageTime("sweep", "swept")).isEqualTo(1_000L)
        assertThat(runtime.stage("gone")).isZero
    }
}
