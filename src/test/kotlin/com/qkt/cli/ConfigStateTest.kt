package com.qkt.cli

import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.NoopStatePersistor
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigStateTest {
    @Test
    fun `state defaults to enabled`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: tv\n")
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isTrue
    }

    @Test
    fun `state enabled false produces NoopStatePersistor`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: false
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isFalse
        assertThat(c.statePersistor(tmp.resolve("state"))).isInstanceOf(NoopStatePersistor::class.java)
    }

    @Test
    fun `state enabled true produces FileStatePersistor`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: true
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isTrue
        assertThat(c.statePersistor(tmp.resolve("state"))).isInstanceOf(FileStatePersistor::class.java)
    }

    @Test
    fun `statePersistor writes strategy files under the given root`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: tv\n")
        val c = Config.load(cfg)
        val root = tmp.resolve("state")
        val persistor = c.statePersistor(root)

        persistor.saveBracketPairs("hedge-straddle", emptyList())

        assertThat(root.resolve("hedge-straddle").resolve("bracket-pairs.json")).exists()
    }
}
