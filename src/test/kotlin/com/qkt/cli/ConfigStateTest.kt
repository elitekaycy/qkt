package com.qkt.cli

import com.qkt.persistence.AsyncStatePersistor
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
    fun `state enabled defaults to the synchronous persistor`(
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
        assertThat(c.stateAsync).isFalse
        assertThat(c.statePersistor(tmp.resolve("state"))).isInstanceOf(FileStatePersistor::class.java)
    }

    @Test
    fun `state async true opts into AsyncStatePersistor`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: true
              async: true
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateAsync).isTrue
        assertThat(c.statePersistor(tmp.resolve("state"))).isInstanceOf(AsyncStatePersistor::class.java)
    }

    @Test
    fun `state async false produces the synchronous FileStatePersistor`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: true
              async: false
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.stateAsync).isFalse
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
        // Close remains harmless for the synchronous default and keeps the test lifecycle explicit.
        (persistor as? AutoCloseable)?.close()

        assertThat(root.resolve("hedge-straddle").resolve("bracket-pairs.json")).exists()
    }

    @Test
    fun `journal retention defaults to fourteen days and accepts zero`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: tv\n")
        assertThat(Config.load(cfg).journalRetentionDays).isEqualTo(14)

        Files.writeString(
            cfg,
            """
            state:
              journal_retention_days: 0
            """.trimIndent(),
        )
        assertThat(Config.load(cfg).journalRetentionDays).isZero()
    }

    @Test
    fun `journal retention rejects a negative value`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              journal_retention_days: -1
            """.trimIndent(),
        )
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { Config.load(cfg).journalRetentionDays }
    }
}
