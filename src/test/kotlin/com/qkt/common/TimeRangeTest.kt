package com.qkt.common

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeRangeTest {
    private val a = Instant.parse("2024-01-15T00:00:00Z")
    private val b = Instant.parse("2024-01-16T00:00:00Z")

    @Test
    fun `requires from less than to`() {
        assertThatThrownBy { TimeRange(b, a) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TimeRange(a, a) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `contains is half open`() {
        val r = TimeRange(a, b)
        assertThat(a in r).isTrue()
        assertThat(b in r).isFalse()
        assertThat(Instant.parse("2024-01-15T12:00:00Z") in r).isTrue()
    }

    @Test
    fun `durationMs computes correctly`() {
        val r = TimeRange(a, b)
        assertThat(r.durationMs).isEqualTo(86_400_000L)
    }
}
