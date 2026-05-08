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
        dir.writeControlPort(47291)
        assertThat(dir.readControlPort()).isEqualTo(47291)
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
        dir.writeControlPort(47291)
        dir.deleteControlPort()
        assertThat(dir.readControlPort()).isNull()
        assertThat(Files.exists(dir.controlPortFile)).isFalse
    }

    @Test
    fun `writeControlPort overwrites previous value`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        dir.writeControlPort(40000)
        dir.writeControlPort(50000)
        assertThat(dir.readControlPort()).isEqualTo(50000)
    }

    @Test
    fun `logFile returns path under logs dir`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.logFile("foo")).isEqualTo(tmp.resolve("logs/foo.log"))
    }
}
