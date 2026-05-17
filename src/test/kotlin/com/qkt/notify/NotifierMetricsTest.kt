package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotifierMetricsTest {
    @Test
    fun `fresh metrics report zero counters and not degraded`() {
        val m = AtomicNotifierMetrics()
        assertThat(m.sent).isZero()
        assertThat(m.dropped).isZero()
        assertThat(m.failed).isZero()
        assertThat(m.rateLimitHits).isZero()
        assertThat(m.degradedMode).isFalse()
    }

    @Test
    fun `increment methods bump counters atomically`() {
        val m = AtomicNotifierMetrics()
        m.recordSent()
        m.recordSent()
        m.recordDropped()
        m.recordFailed()
        m.recordRateLimit()
        m.recordRateLimit()
        m.recordRateLimit()
        assertThat(m.sent).isEqualTo(2L)
        assertThat(m.dropped).isEqualTo(1L)
        assertThat(m.failed).isEqualTo(1L)
        assertThat(m.rateLimitHits).isEqualTo(3L)
    }

    @Test
    fun `flipDegraded sets degradedMode true and is idempotent`() {
        val m = AtomicNotifierMetrics()
        m.flipDegraded()
        assertThat(m.degradedMode).isTrue()
        m.flipDegraded()
        assertThat(m.degradedMode).isTrue()
    }
}
