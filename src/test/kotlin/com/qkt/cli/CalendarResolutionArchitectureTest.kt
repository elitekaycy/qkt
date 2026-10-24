package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every trading-calendar choice goes through [CalendarResolver], so backtest and live cannot drift apart again. The
 * built-in rules are read directly only by the resolver and by data coverage, which judges a store's days by the
 * data's own market rather than by the venue a run trades on.
 */
class CalendarResolutionArchitectureTest {
    private val allowed =
        setOf(
            "cli/CalendarResolver.kt",
            "cli/BuiltInCalendars.kt",
            "cli/BacktestContext.kt",
            "cli/ParseCommand.kt",
            "cli/BacktestBarReplay.kt",
            "cli/BacktestTickProvisioning.kt",
            "cli/data/VerifyCoverage.kt",
        )

    @Test
    fun `only the resolver and data coverage read the built-in calendar rules`() {
        val root = Path.of("src/main/kotlin/com/qkt")
        val readers =
            Files.walk(root).use { files ->
                files
                    .filter { it.extension == "kt" }
                    .filter { f -> f.readText().let { "defaultCalendars()" in it || "BuiltInCalendars.rules" in it } }
                    .map { root.relativize(it).toString() }
                    .toList()
            }
        assertThat(readers).isNotEmpty.allMatch { it in allowed }
    }
}
