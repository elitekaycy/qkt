package com.qkt.cli.observe

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventRingTest {
    private fun obj(s: String): JsonObject = buildJsonObject { put("v", s) }

    private fun valueOf(e: EventEntry): String = (e.payload["v"] as JsonPrimitive).content

    @Test
    fun `append then snapshot returns entries in insertion order`() {
        val ring = EventRing(capacity = 5)
        ring.append("trade", obj("a"))
        ring.append("trade", obj("b"))
        assertThat(ring.snapshot(since = 0L, limit = 100).map(::valueOf)).containsExactly("a", "b")
    }

    @Test
    fun `oldest entries evict at capacity`() {
        val ring = EventRing(capacity = 3)
        for (c in listOf("a", "b", "c", "d", "e")) ring.append("trade", obj(c))
        assertThat(ring.snapshot(since = 0L, limit = 100).map(::valueOf)).containsExactly("c", "d", "e")
    }

    @Test
    fun `snapshot since filters by timestamp`() {
        val ring = EventRing(capacity = 5)
        ring.append("trade", obj("a"))
        Thread.sleep(5)
        val cutoff = System.currentTimeMillis()
        Thread.sleep(5)
        ring.append("trade", obj("b"))
        val s = ring.snapshot(since = cutoff, limit = 100)
        assertThat(s).hasSize(1)
        assertThat(valueOf(s[0])).isEqualTo("b")
    }

    @Test
    fun `snapshot limit caps result size`() {
        val ring = EventRing(capacity = 100)
        repeat(20) { i -> ring.append("trade", obj(i.toString())) }
        assertThat(ring.snapshot(since = 0L, limit = 5)).hasSize(5)
    }

    @Test
    fun `subscriber receives appended entries until close`() {
        val ring = EventRing(capacity = 5)
        val received = mutableListOf<String>()
        val sub = ring.subscribe { e -> received.add(valueOf(e)) }
        ring.append("trade", obj("x"))
        ring.append("trade", obj("y"))
        sub.close()
        ring.append("trade", obj("z"))
        assertThat(received).containsExactly("x", "y")
    }

    @Test
    fun `listener exception does not block subsequent appends`() {
        val ring = EventRing(capacity = 5)
        ring.subscribe { throw RuntimeException("boom") }
        ring.append("trade", obj("a"))
        ring.append("trade", obj("b"))
        assertThat(ring.snapshot(since = 0L, limit = 100)).hasSize(2)
    }

    @Test
    fun `capacity zero or negative is rejected`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy { EventRing(capacity = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions
            .assertThatThrownBy { EventRing(capacity = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
