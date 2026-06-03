package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private class RecordingNotifier(
    override var metrics: NotifierMetrics? = null,
    private val throwOnClose: Boolean = false,
) : Notifier {
    val received = mutableListOf<NotificationEvent>()
    var closed = false

    override fun notify(event: NotificationEvent) {
        received.add(event)
    }

    override fun close() {
        if (throwOnClose) error("deliberate close failure")
        closed = true
    }
}

class CompositeNotifierTest {
    private val event = NotificationEvent.Halted(strategyId = null, reason = "test", timestamp = 0L)

    @Test
    fun `notify reaches all underlying notifiers`() {
        val a = RecordingNotifier()
        val b = RecordingNotifier()
        val composite = CompositeNotifier(listOf(a, b))

        composite.notify(event)

        assertThat(a.received).containsExactly(event)
        assertThat(b.received).containsExactly(event)
    }

    @Test
    fun `close closes all notifiers even if one throws`() {
        val a = RecordingNotifier(throwOnClose = true)
        val b = RecordingNotifier()
        val composite = CompositeNotifier(listOf(a, b))

        composite.close()

        assertThat(b.closed).isTrue()
    }

    @Test
    fun `metrics returns first non-null when first is null`() {
        val m =
            object : NotifierMetrics {
                override val sent = 1L
                override val dropped = 0L
                override val failed = 0L
                override val rateLimitHits = 0L
                override val degradedMode = false
            }
        val a = RecordingNotifier(metrics = null)
        val b = RecordingNotifier(metrics = m)
        val composite = CompositeNotifier(listOf(a, b))

        assertThat(composite.metrics).isSameAs(m)
    }

    @Test
    fun `metrics returns first non-null when first has metrics`() {
        val m =
            object : NotifierMetrics {
                override val sent = 5L
                override val dropped = 0L
                override val failed = 0L
                override val rateLimitHits = 0L
                override val degradedMode = false
            }
        val a = RecordingNotifier(metrics = m)
        val b = RecordingNotifier(metrics = null)
        val composite = CompositeNotifier(listOf(a, b))

        assertThat(composite.metrics).isSameAs(m)
    }
}
