package com.qkt.backtest.walkforward

import com.qkt.common.TimeRange
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WindowsRollingTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun range(
        startDays: Long,
        endDays: Long,
    ): TimeRange = TimeRange(t0.plus(Duration.ofDays(startDays)), t0.plus(Duration.ofDays(endDays)))

    @Test
    fun `exact-fit single fold`() {
        val total = range(0, 30)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
            )
        assertThat(folds).hasSize(1)
        assertThat(folds[0].first).isEqualTo(range(0, 20))
        assertThat(folds[0].second).isEqualTo(range(20, 30))
    }

    @Test
    fun `multi-fold non-overlapping when step equals testSize`() {
        val total = range(0, 60)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
            )
        assertThat(folds).hasSize(4)
        assertThat(folds[0].first).isEqualTo(range(0, 20))
        assertThat(folds[3].second).isEqualTo(range(50, 60))
    }

    @Test
    fun `overlapping test windows when step is less than testSize`() {
        val total = range(0, 50)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(20),
                stepSize = Duration.ofDays(5),
            )
        assertThat(folds).hasSize(3)
        assertThat(folds[1].second).isEqualTo(range(25, 45))
    }

    @Test
    fun `off-by-one stops when testEnd exceeds total`() {
        val total = range(0, 25)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(5),
            )
        assertThat(folds).isEmpty()
    }
}
