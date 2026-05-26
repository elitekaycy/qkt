package com.qkt.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatencyRegistryTest {
    @Test
    fun `disabled registry short-circuits every operation`() {
        val r = LatencyRegistry(enabled = false, strategyIds = listOf("s1"))
        r.observe("s1", LatencyStage.SIGNAL_TO_SUBMISSION, 1_000)
        r.recordSubmit("ord-1")
        r.observeFill("ord-1", "s1")

        val snap = r.snapshot()
        assertThat(snap.enabled).isFalse()
        assertThat(snap.strategies).isEmpty()
        assertThat(r.pendingSubmits()).isEqualTo(0)
    }

    @Test
    fun `enabled registry observes per strategy and stage`() {
        val r = LatencyRegistry(enabled = true, strategyIds = listOf("alpha", "beta"))
        r.observe("alpha", LatencyStage.SIGNAL_TO_SUBMISSION, 100)
        r.observe("alpha", LatencyStage.SIGNAL_TO_SUBMISSION, 200)
        r.observe("beta", LatencyStage.SIGNAL_TO_SUBMISSION, 5_000)

        val snap = r.snapshot()
        assertThat(snap.enabled).isTrue()
        val alphaS2S = snap.strategies["alpha"]!![LatencyStage.SIGNAL_TO_SUBMISSION]!!
        assertThat(alphaS2S.count).isEqualTo(2)
        assertThat(alphaS2S.maxNanos).isEqualTo(200L)
        val betaS2S = snap.strategies["beta"]!![LatencyStage.SIGNAL_TO_SUBMISSION]!!
        assertThat(betaS2S.count).isEqualTo(1)
        assertThat(betaS2S.maxNanos).isEqualTo(5_000L)
    }

    @Test
    fun `recordSubmit then observeFill produces a SUBMISSION_TO_FILL observation`() {
        val r = LatencyRegistry(enabled = true, strategyIds = listOf("s1"))
        r.recordSubmit("ord-1")
        Thread.sleep(2)
        r.observeFill("ord-1", "s1")

        val snap = r.snapshot().strategies["s1"]!![LatencyStage.SUBMISSION_TO_FILL]!!
        assertThat(snap.count).isEqualTo(1)
        assertThat(snap.maxNanos).isGreaterThan(0L)
        assertThat(r.pendingSubmits()).isEqualTo(0)
    }

    @Test
    fun `observeFill with no prior recordSubmit is a silent no-op`() {
        val r = LatencyRegistry(enabled = true, strategyIds = listOf("s1"))
        r.observeFill("ord-never-submitted", "s1")

        val snap = r.snapshot().strategies["s1"]!![LatencyStage.SUBMISSION_TO_FILL]!!
        assertThat(snap.count).isEqualTo(0)
    }

    @Test
    fun `submit map FIFO-evicts at cap`() {
        val r = LatencyRegistry(enabled = true, strategyIds = listOf("s1"), submitMapCap = 3)
        r.recordSubmit("a")
        r.recordSubmit("b")
        r.recordSubmit("c")
        r.recordSubmit("d") // evicts "a"

        assertThat(r.pendingSubmits()).isEqualTo(3)

        // "a" was evicted — observing its fill is now a no-op
        r.observeFill("a", "s1")
        assertThat(r.snapshot().strategies["s1"]!![LatencyStage.SUBMISSION_TO_FILL]!!.count).isEqualTo(0)

        // "b" is still tracked
        r.observeFill("b", "s1")
        assertThat(r.snapshot().strategies["s1"]!![LatencyStage.SUBMISSION_TO_FILL]!!.count).isEqualTo(1)
    }

    @Test
    fun `observe to unknown strategy id is a silent no-op (does not throw)`() {
        val r = LatencyRegistry(enabled = true, strategyIds = listOf("s1"))
        r.observe("not-a-known-strategy", LatencyStage.SIGNAL_TO_SUBMISSION, 100)
        // No crash, and no spurious data.
        assertThat(r.snapshot().strategies).containsOnlyKeys("s1")
    }
}
