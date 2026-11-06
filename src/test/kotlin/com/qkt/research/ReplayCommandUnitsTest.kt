package com.qkt.research

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayCommandUnitsTest {
    @Test
    fun `step durations accept seconds and hours`() {
        assertThat(ReplayCommand.parse("step 10s")).isEqualTo(ReplayCommand.StepDuration(10_000L))
        assertThat(ReplayCommand.parse("step 2h")).isEqualTo(ReplayCommand.StepDuration(7_200_000L))
    }

    @Test
    fun `run-to takes an instant with or without the zone suffix`() {
        val at = Instant.parse("2024-01-15T10:30:00Z").toEpochMilli()

        assertThat(ReplayCommand.parse("run-to 2024-01-15T10:30:00")).isEqualTo(ReplayCommand.RunToTime(at))
        assertThat(ReplayCommand.parse("run-to 2024-01-15T10:30:00Z")).isEqualTo(ReplayCommand.RunToTime(at))
    }

    @Test
    fun `run-to with a bad or missing time is unknown`() {
        assertThat(ReplayCommand.parse("run-to someday")).isEqualTo(ReplayCommand.Unknown("run-to someday"))
        assertThat(
            ReplayCommand.parse("run-to 2024-13-45T99:00"),
        ).isEqualTo(ReplayCommand.Unknown("run-to 2024-13-45T99:00"))
        assertThat(ReplayCommand.parse("run-to")).isEqualTo(ReplayCommand.Unknown("run-to"))
    }
}
