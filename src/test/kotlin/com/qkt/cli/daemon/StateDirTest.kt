package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateDirTest {
    @Test
    fun `resolve uses override when given`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.root).isEqualTo(tmp)
        assertThat(dir.logsDir).isEqualTo(tmp.resolve("logs"))
    }

    @Test
    fun `creates root and logs subdirectory`(
        @TempDir tmp: Path,
    ) {
        val nested = tmp.resolve("nested/qkt")
        StateDir.resolve(nested.toString())
        assertThat(Files.exists(nested)).isTrue
        assertThat(Files.isDirectory(nested.resolve("logs"))).isTrue
    }

    @Test
    fun `writeControlPort then readControlPort round-trips`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        dir.acquireDaemonLock()!!.use { lock ->
            lock.writeControlPort(47291)
            assertThat(dir.readControlPort()).isEqualTo(47291)
        }
    }

    @Test
    fun `readControlPort returns null when file is absent`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.readControlPort()).isNull()
    }

    @Test
    fun `readControlPort returns null when file content is malformed`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        Files.writeString(dir.controlPortFile, "not-a-number")
        assertThat(dir.readControlPort()).isNull()
    }

    @Test
    fun `deleteControlPort removes the file`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        dir.acquireDaemonLock()!!.use { lock ->
            lock.writeControlPort(47291)
            dir.deleteControlPort()
            assertThat(dir.readControlPort()).isNull()
        }
        assertThat(Files.exists(dir.controlPortFile)).isFalse
    }

    @Test
    fun `writeControlPort overwrites previous value`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        dir.acquireDaemonLock()!!.use { lock ->
            lock.writeControlPort(40000)
            lock.writeControlPort(50000)
            assertThat(dir.readControlPort()).isEqualTo(50000)
        }
    }

    @Test
    fun `second daemon lock is refused until the owner closes`(
        @TempDir tmp: Path,
    ) {
        val firstDir = StateDir.resolve(tmp.toString())
        val secondDir = StateDir.resolve(tmp.toString())
        val first = firstDir.acquireDaemonLock()

        assertThat(first).isNotNull()
        assertThat(secondDir.acquireDaemonLock()).isNull()

        first!!.close()
        val reacquired = secondDir.acquireDaemonLock()
        assertThat(reacquired).isNotNull()
        reacquired!!.close()
    }

    @Test
    fun `acquiring daemon lock clears stale control discovery`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        Files.writeString(dir.controlPortFile, "49999")

        val lock = dir.acquireDaemonLock()

        assertThat(lock).isNotNull()
        assertThat(dir.readControlPort()).isNull()
        lock!!.close()
    }

    @Test
    fun `closing daemon lock removes control discovery but leaves reusable lock file`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        dir.acquireDaemonLock()!!.use { it.writeControlPort(47291) }

        assertThat(dir.readControlPort()).isNull()
        assertThat(Files.exists(dir.pidFile)).isTrue()
        assertThat(Files.readString(dir.pidFile)).isEmpty()
    }

    @Test
    fun `logFile returns path under logs dir`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.logFile("foo")).isEqualTo(tmp.resolve("logs/foo.log"))
    }
}
