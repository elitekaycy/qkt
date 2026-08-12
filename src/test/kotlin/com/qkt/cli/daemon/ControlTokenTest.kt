package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ControlTokenTest {
    @Test
    fun `daemon generates a stable owner-only token and client reads it`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())

        val first = ControlToken.forDaemon(stateDir, emptyMap())
        val second = ControlToken.forDaemon(stateDir, emptyMap())

        assertThat(first.value).hasSize(43)
        assertThat(second.value).isEqualTo(first.value)
        assertThat(ControlToken.forClient(stateDir, emptyMap())!!.value).isEqualTo(first.value)
        val permissions =
            runCatching { Files.getPosixFilePermissions(stateDir.controlTokenFile) }
                .getOrNull()
        if (permissions != null) {
            assertThat(permissions).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `environment token takes precedence without writing a state token`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())

        val token = ControlToken.forDaemon(stateDir, mapOf("QKT_CONTROL_TOKEN" to "operator-secret"))

        assertThat(token.value).isEqualTo("operator-secret")
        assertThat(token.source).isEqualTo("QKT_CONTROL_TOKEN")
        assertThat(Files.exists(stateDir.controlTokenFile)).isFalse()
    }

    @Test
    fun `daemon repairs permissions on an existing state token`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        Files.writeString(stateDir.controlTokenFile, "existing-secret")
        val originalPermissions = runCatching { Files.getPosixFilePermissions(stateDir.controlTokenFile) }.getOrNull()
        if (originalPermissions != null) {
            Files.setPosixFilePermissions(stateDir.controlTokenFile, PosixFilePermissions.fromString("rw-r--r--"))
        }

        val token = ControlToken.forDaemon(stateDir, emptyMap())

        assertThat(token.value).isEqualTo("existing-secret")
        if (originalPermissions != null) {
            assertThat(Files.getPosixFilePermissions(stateDir.controlTokenFile))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"))
        }
    }
}
