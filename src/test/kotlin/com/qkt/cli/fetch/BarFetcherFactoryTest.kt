package com.qkt.cli.fetch

import com.qkt.cli.Config
import com.qkt.cli.captureCliRun
import com.qkt.cli.withUserHome
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarFetcherFactoryTest {
    @TempDir
    lateinit var tmp: Path

    private fun config(body: String): String =
        tmp
            .resolve("qkt.config.yaml")
            .also {
                Files.writeString(it, body)
            }.toString()

    private fun build(
        broker: String,
        configPath: String?,
    ) = captureCliRun { if (buildFetcher(broker, configPath) == null) 1 else 0 }

    @Test
    fun `mt5 profiles that cannot be resolved give no fetcher`() {
        val run = build("custom", config("brokers:\n  custom:\n    type: mt5\n    magic: 1\n"))

        assertThat(run.code).isEqualTo(1)
        assertThat(run.err).contains("qkt: failed to load broker profiles:")
    }

    @Test
    fun `an account whose connector serves no bars gives no fetcher`() {
        val run = build("paperish", config("brokers:\n  paperish:\n    type: paper\n"))

        assertThat(run.code).isEqualTo(1)
        assertThat(
            run.err,
        ).containsAnyOf("qkt: broker 'paperish' serves no historical bars", "qkt: failed to open broker 'paperish'")
    }

    @Test
    fun `without any config an mt5 broker cannot be resolved`() {
        withUserHome(tmp) {
            assumeTrue(Config.locate() == null, "a qkt.config.yaml outside the temp home is visible")
            val run = build("EXNESS", null)
            assertThat(run.code).isEqualTo(1)
            assertThat(run.err).contains("qkt: no qkt.config.yaml found (need it to resolve broker 'EXNESS')")
        }
    }
}
