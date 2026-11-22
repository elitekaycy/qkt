package com.qkt.cli

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CalendarVenueAndConfigPathTest {
    @Test
    fun `a venue that is known but has no readable calendars is refused`() {
        assertThatThrownBy {
            CalendarResolver.backtest(setOf("exness"), lazyOf(emptyMap()), venue = "exness", instruments = null)
        }.isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage("--venue 'exness' has no calendars qkt can read")
    }

    @Test
    fun `with no flag the config comes from the environment variable or else the default location`(
        @TempDir tmp: Path,
    ) {
        val resolved = Config.resolvePath(null, searchPaths = listOf(tmp.resolve("absent.yaml")))

        val fromEnvironment = System.getenv("QKT_CONFIG")?.takeIf { it.isNotBlank() }
        assertThat(resolved).isEqualTo(fromEnvironment?.let(Path::of) ?: Path.of("./qkt.config.yaml"))
    }
}
