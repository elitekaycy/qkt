package com.qkt.connector.mt5

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class MT5UnknownResolveSchedulerTest {
    @Test
    fun `immediate work runs off the calling thread on a named daemon thread`() {
        val scheduler = MT5UnknownResolveScheduler("exness", periodicResolveMs = 10L)
        val ran = CountDownLatch(1)
        var threadName = ""
        var daemon = false

        scheduler.executeUnknownResolution {
            threadName = Thread.currentThread().name
            daemon = Thread.currentThread().isDaemon
            ran.countDown()
        }

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(threadName).isEqualTo("qkt-mt5-unknown-resolve-exness")
        assertThat(daemon).isTrue()
        scheduler.shutdownNow()
    }

    @Test
    fun `work offered after shutdown is dropped without throwing`() {
        val scheduler = MT5UnknownResolveScheduler("exness", periodicResolveMs = 10L)
        scheduler.shutdownNow()
        var ran = false

        assertThatCode {
            scheduler.executeUnknownResolution { ran = true }
            scheduler.scheduleUnknownResolution { ran = true }
        }.doesNotThrowAnyException()
        assertThat(ran).isFalse()
    }
}
