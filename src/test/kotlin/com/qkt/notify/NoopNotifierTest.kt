package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoopNotifierTest {
    @Test
    fun `notify is a no-op and never throws`() {
        val n = NoopNotifier
        n.notify(NotificationEvent.Resumed(strategyId = null, timestamp = 1L))
        n.notify(NotificationEvent.DaemonStarted("0.27.0", emptyList(), 2L))
    }

    @Test
    fun `close is a no-op and never throws`() {
        NoopNotifier.close()
    }
}
