package com.qkt.app

import java.util.concurrent.CountDownLatch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EngineSnapshotTimeoutTest {
    @Test
    fun `a read the engine never answers is reported as a timeout, not a failed read`() {
        val release = CountDownLatch(1)
        val busyEngine = Thread { release.await() }.apply { isDaemon = true }
        busyEngine.start()
        try {
            assertThatThrownBy { EngineSnapshot(busyEngine, EngineMailbox()).engineSnapshot { 1 } }
                .isInstanceOf(EngineSnapshotTimeoutException::class.java)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `an engine that dies holding a read without terminating is reported, not read around`() {
        val mailbox = EngineMailbox()
        val engine = Thread { mailbox.control.take() }.apply { isDaemon = true }
        engine.start()

        assertThatThrownBy { EngineSnapshot(engine, mailbox).engineSnapshot { 1 } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("live engine stopped without publishing its termination barrier")
    }

    @Test
    fun `an engine that terminated while a read was queued has the read run on the caller`() {
        val mailbox = EngineMailbox()
        val engine =
            Thread {
                mailbox.control.take()
                mailbox.terminated.countDown()
            }.apply { isDaemon = true }
        engine.start()

        assertThat(EngineSnapshot(engine, mailbox).engineSnapshot { 7 }).isEqualTo(7)
    }
}
