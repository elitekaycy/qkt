package com.qkt.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatencySnapshotMicrosTest {
    @Test
    fun `snapshot percentiles convert to whole microseconds`() {
        val s = LatencyTracker.Snapshot(count = 4, p50Nanos = 1_999L, p95Nanos = 25_000L, p99Nanos = 999L, maxNanos = 3_000_500L)

        assertThat(s.p50Micros()).isEqualTo(1L)
        assertThat(s.p95Micros()).isEqualTo(25L)
        assertThat(s.p99Micros()).isZero
        assertThat(s.maxMicros()).isEqualTo(3_000L)
    }
}
