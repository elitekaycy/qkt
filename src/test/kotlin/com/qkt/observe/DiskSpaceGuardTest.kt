package com.qkt.observe

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiskSpaceGuardTest {
    @Test
    fun `fires once per low crossing and does not repeat while still low`(
        @TempDir tmp: Path,
    ) {
        val alerts = AtomicInteger(0)
        // A floor no real volume satisfies: every check observes "low".
        val guard = DiskSpaceGuard(tmp, floorBytes = Long.MAX_VALUE / 2, onLow = { _, _ -> alerts.incrementAndGet() })

        val first = guard.check()
        val second = guard.check()

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(alerts.get()).isEqualTo(1)
    }

    @Test
    fun `healthy volume never alerts`(
        @TempDir tmp: Path,
    ) {
        val alerts = AtomicInteger(0)
        val guard = DiskSpaceGuard(tmp, floorBytes = 1L, onLow = { _, _ -> alerts.incrementAndGet() })

        val free = guard.check()

        assertThat(free).isGreaterThan(1L)
        assertThat(alerts.get()).isZero()
    }

    @Test
    fun `zero floor disables the guard`(
        @TempDir tmp: Path,
    ) {
        val alerts = AtomicInteger(0)
        val guard = DiskSpaceGuard(tmp, floorBytes = 0L, onLow = { _, _ -> alerts.incrementAndGet() })

        assertThat(guard.check()).isNull()
        assertThat(alerts.get()).isZero()
    }
}
