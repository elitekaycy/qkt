package com.qkt.research

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayCommandTest {
    @Test
    fun `parses every command form`() {
        assertThat(ReplayCommand.parse("run")).isEqualTo(ReplayCommand.Run)
        assertThat(ReplayCommand.parse("step 5")).isEqualTo(ReplayCommand.StepBars(5))
        assertThat(ReplayCommand.parse("step 2d")).isEqualTo(ReplayCommand.StepDuration(2 * 86_400_000L))
        assertThat(ReplayCommand.parse("step 30m")).isEqualTo(ReplayCommand.StepDuration(30 * 60_000L))
        assertThat(ReplayCommand.parse("run-to next-trade")).isEqualTo(ReplayCommand.RunToNextTrade)
        assertThat(ReplayCommand.parse("run-to 2024-01-15"))
            .isEqualTo(ReplayCommand.RunToTime(1_705_276_800_000L))
        assertThat(ReplayCommand.parse("reset")).isEqualTo(ReplayCommand.Reset)
        assertThat(ReplayCommand.parse("reload")).isEqualTo(ReplayCommand.Reload)
        assertThat(ReplayCommand.parse("show")).isEqualTo(ReplayCommand.Show)
        assertThat(ReplayCommand.parse("quit")).isEqualTo(ReplayCommand.Quit)
    }

    @Test
    fun `unknown input is reported, not crashed`() {
        assertThat(ReplayCommand.parse("frobnicate")).isEqualTo(ReplayCommand.Unknown("frobnicate"))
        assertThat(ReplayCommand.parse("step abc")).isEqualTo(ReplayCommand.Unknown("step abc"))
        assertThat(ReplayCommand.parse("")).isEqualTo(ReplayCommand.Unknown(""))
    }
}
