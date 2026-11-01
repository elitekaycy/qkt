package com.qkt.lsp

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DiagnosticsSchedulerTest {
    @Test
    fun `a burst of edits to one document is analysed once, with the last edit`() {
        val scheduler = DiagnosticsScheduler(quietMs = 100)
        val analysed = CopyOnWriteArrayList<Int>()
        val done = CountDownLatch(1)
        for (version in 1..20) {
            scheduler.schedule("file:///a.qkt") {
                analysed.add(version)
                done.countDown()
            }
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(200)
        assertThat(analysed).containsExactly(20)
        scheduler.shutdown()
    }

    @Test
    fun `edits to different documents do not cancel each other`() {
        val scheduler = DiagnosticsScheduler(quietMs = 50)
        val done = CountDownLatch(2)
        scheduler.schedule("file:///a.qkt") { done.countDown() }
        scheduler.schedule("file:///b.qkt") { done.countDown() }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        scheduler.shutdown()
    }

    @Test
    fun `a cancelled document is not analysed`() {
        val scheduler = DiagnosticsScheduler(quietMs = 100)
        val analysed = CopyOnWriteArrayList<String>()
        scheduler.schedule("file:///a.qkt") { analysed.add("a") }
        scheduler.cancel("file:///a.qkt")
        Thread.sleep(300)
        assertThat(analysed).isEmpty()
        scheduler.shutdown()
    }
}
