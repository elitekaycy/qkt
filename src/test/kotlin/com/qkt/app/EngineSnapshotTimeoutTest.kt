package com.qkt.app

import java.util.concurrent.CountDownLatch
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
}
