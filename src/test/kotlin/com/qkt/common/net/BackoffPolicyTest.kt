package com.qkt.common.net

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BackoffPolicyTest {
    @Test
    fun `ExponentialBackoff doubles each attempt up to cap`() {
        val backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L, multiplier = 2.0)
        assertThat(backoff.nextDelayMs(1)).isEqualTo(1_000L)
        assertThat(backoff.nextDelayMs(2)).isEqualTo(2_000L)
        assertThat(backoff.nextDelayMs(3)).isEqualTo(4_000L)
        assertThat(backoff.nextDelayMs(7)).isEqualTo(60_000L)
        assertThat(backoff.nextDelayMs(20)).isEqualTo(60_000L)
    }

    @Test
    fun `ExponentialBackoff with multiplier 1_5 grows slower`() {
        val backoff = ExponentialBackoff(initialMs = 100L, capMs = 10_000L, multiplier = 1.5)
        assertThat(backoff.nextDelayMs(1)).isEqualTo(100L)
        assertThat(backoff.nextDelayMs(2)).isEqualTo(150L)
        assertThat(backoff.nextDelayMs(3)).isEqualTo(225L)
    }

    @Test
    fun `FixedDelayBackoff returns the same delay every attempt`() {
        val backoff = FixedDelayBackoff(500L)
        assertThat(backoff.nextDelayMs(1)).isEqualTo(500L)
        assertThat(backoff.nextDelayMs(5)).isEqualTo(500L)
        assertThat(backoff.nextDelayMs(100)).isEqualTo(500L)
    }

    @Test
    fun `ExponentialBackoff requires positive parameters`() {
        assertThatThrownBy { ExponentialBackoff(initialMs = 0L, capMs = 60_000L) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ExponentialBackoff(initialMs = 1_000L, capMs = 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L, multiplier = 0.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `FixedDelayBackoff requires positive delay`() {
        assertThatThrownBy { FixedDelayBackoff(0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
