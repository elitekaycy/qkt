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
    fun `state defaults to enabled with ~ slash qkt slash state dir`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: tv\n")
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isTrue
        assertThat(c.stateDir).endsWith("/.qkt/state")
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
        assertThat(c.statePersistor()).isInstanceOf(NoopStatePersistor::class.java)
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
              dir: ${tmp.resolve("custom-state")}
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateEnabled).isTrue
        assertThat(c.stateDir).isEqualTo(tmp.resolve("custom-state").toString())
        assertThat(c.statePersistor()).isInstanceOf(FileStatePersistor::class.java)
    }

    @Test
    fun `state dir without ~ stays unchanged`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              dir: /var/lib/qkt/state
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateDir).isEqualTo("/var/lib/qkt/state")
    }
}
