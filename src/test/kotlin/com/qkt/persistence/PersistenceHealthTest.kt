package com.qkt.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PersistenceHealthTest {
    @Test
    fun `file persistor reports a failed durable write`(
        @TempDir tmp: Path,
    ) {
        val blockedRoot = tmp.resolve("state-file")
        Files.writeString(blockedRoot, "not a directory")
        val persistor = FileStatePersistor(blockedRoot)

        persistor.savePnl("alpha", PersistedPnl(java.math.BigDecimal.ONE))

        val health = persistor.healthSnapshot()
        assertThat(health.enabled).isTrue()
        assertThat(health.failedWrites).isEqualTo(1L)
        assertThat(health.consecutiveFailures).isEqualTo(1L)
        assertThat(health.failureEpisodes).isEqualTo(1L)
        assertThat(health.totalWrites).isZero()
    }

    @Test
    fun `async persistor combines delegate and queue health`() {
        val delegate =
            object : StatePersistor by NoopStatePersistor() {
                override fun healthSnapshot() =
                    PersistenceHealth(
                        enabled = true,
                        totalWrites = 12L,
                        slowWrites = 2L,
                        failedWrites = 3L,
                        consecutiveFailures = 2L,
                        failureEpisodes = 1L,
                    )
            }

        AsyncStatePersistor(delegate).use { async ->
            val health = async.healthSnapshot()

            assertThat(health.enabled).isTrue()
            assertThat(health.totalWrites).isEqualTo(12L)
            assertThat(health.slowWrites).isEqualTo(2L)
            assertThat(health.failedWrites).isEqualTo(3L)
            assertThat(health.consecutiveFailures).isEqualTo(2L)
            assertThat(health.failureEpisodes).isEqualTo(1L)
            assertThat(health.queueSize).isZero()
            assertThat(health.callerRunsTotal).isZero()
        }
    }

    @Test
    fun `successful write clears degradation and a later failure starts a new episode`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)

        persistor.savePnl("bad\u0000strategy", PersistedPnl(java.math.BigDecimal.ONE))
        assertThat(persistor.healthSnapshot().consecutiveFailures).isEqualTo(1L)
        assertThat(persistor.healthSnapshot().failureEpisodes).isEqualTo(1L)

        persistor.savePnl("alpha", PersistedPnl(java.math.BigDecimal.ONE))
        assertThat(persistor.healthSnapshot().consecutiveFailures).isZero()

        persistor.savePnl("bad\u0000strategy", PersistedPnl(java.math.BigDecimal.ONE))
        assertThat(persistor.healthSnapshot().failedWrites).isEqualTo(2L)
        assertThat(persistor.healthSnapshot().consecutiveFailures).isEqualTo(1L)
        assertThat(persistor.healthSnapshot().failureEpisodes).isEqualTo(2L)
    }
}
