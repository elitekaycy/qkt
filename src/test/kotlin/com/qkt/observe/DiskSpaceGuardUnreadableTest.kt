package com.qkt.observe

import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiskSpaceGuardUnreadableTest {
    @Test
    fun `a volume that cannot be read reports nothing and raises no alert`(
        @TempDir tmp: Path,
    ) {
        // A path on a file system that has since been closed: every read of it fails.
        val archive = FileSystems.newFileSystem(tmp.resolve("state.zip"), mapOf("create" to "true"))
        val gone = archive.getPath("/state")
        archive.close()
        val alerts = AtomicInteger(0)
        val guard = DiskSpaceGuard(gone, floorBytes = Long.MAX_VALUE / 2, onLow = { _, _ -> alerts.incrementAndGet() })

        assertThat(guard.check()).isNull()
        assertThat(alerts.get()).isZero()
        guard.close()
    }
}
