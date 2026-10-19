package com.qkt.trade.session

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunLockTest {
    @Test
    fun `a run can be held by one session only`(
        @TempDir dir: Path,
    ) {
        val first = RunLock.acquire(dir, "live1")

        assertThatThrownBy { RunLock.acquire(dir, "live1") }.hasMessageContaining("run live1 is already running")

        first.close()
        RunLock.acquire(dir, "live1").use { assertThat(it).isNotNull() }
    }
}
