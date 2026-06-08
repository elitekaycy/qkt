package com.qkt.broker.bybit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BoundedExecIdSetTest {
    @Test
    fun `dedups within the window and evicts the oldest beyond max size`() {
        val seen = boundedExecIdSet(3)
        assertThat(seen.add("a")).isTrue
        assertThat(seen.add("b")).isTrue
        assertThat(seen.add("c")).isTrue
        // still within the window -> a repeat is deduped
        assertThat(seen.add("a")).isFalse
        // fourth distinct id pushes past the cap -> oldest ("a") is evicted
        assertThat(seen.add("d")).isTrue
        assertThat(seen).containsExactlyInAnyOrder("b", "c", "d")
        // "a" was evicted, so it counts as new again
        assertThat(seen.add("a")).isTrue
    }

    @Test
    fun `never grows beyond max size under sustained adds`() {
        val seen = boundedExecIdSet(100)
        for (i in 0 until 10_000) seen.add("exec-$i")
        assertThat(seen.size).isEqualTo(100)
    }
}
