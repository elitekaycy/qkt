package com.qkt.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MonotonicSequenceGeneratorTest {
    @Test
    fun `next returns 0 first`() {
        val sequencer = MonotonicSequenceGenerator()
        assertThat(sequencer.next()).isEqualTo(0L)
    }

    @Test
    fun `next is monotonically increasing`() {
        val sequencer = MonotonicSequenceGenerator()
        assertThat(sequencer.next()).isEqualTo(0L)
        assertThat(sequencer.next()).isEqualTo(1L)
        assertThat(sequencer.next()).isEqualTo(2L)
        assertThat(sequencer.next()).isEqualTo(3L)
    }

    @Test
    fun `independent instances have independent counters`() {
        val a = MonotonicSequenceGenerator()
        val b = MonotonicSequenceGenerator()
        assertThat(a.next()).isEqualTo(0L)
        assertThat(a.next()).isEqualTo(1L)
        assertThat(b.next()).isEqualTo(0L)
        assertThat(a.next()).isEqualTo(2L)
        assertThat(b.next()).isEqualTo(1L)
    }

    @Test
    fun `a resumed generator continues after the last issued sequence`() {
        assertThat(MonotonicSequenceGenerator.resumingAfter(41L).next()).isEqualTo(42L)
        assertThat(MonotonicSequenceGenerator.resumingAfter(null).next()).isEqualTo(0L)
    }

    @Test
    fun `resuming below the current counter never reissues a sequence`() {
        val sequencer = MonotonicSequenceGenerator()
        repeat(5) { sequencer.next() }

        sequencer.resumeAfter(2L)

        assertThat(sequencer.next()).isEqualTo(5L)
    }
}
