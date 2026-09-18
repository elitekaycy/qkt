package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StatusCommandDeepFailureTest : StatusCommandDeepFixture() {
    @Test
    fun `deep returns 1 when list call throws after health succeeded`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun health(): String = """{"status":"ok","uptimeMs":1000}"""

                override fun list(): String = throw ControlClient.NoDaemonRunningException("daemon died mid-check")
            }
        val (code, stdout, stderr) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).contains("UNHEALTHY")
        assertThat(stderr).contains("daemon died mid-check")
    }

    @Test
    fun `deep returns 1 when daemon returns malformed JSON`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = "{",
                listBody = "[]",
            )
        val (code, stdout, stderr) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).contains("UNHEALTHY")
        assertThat(stderr).contains("malformed daemon response")
    }

    @Test
    fun `deep returns 1 when daemon returns wrong JSON shape`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = "\"a-quoted-string-instead-of-object\"",
                listBody = "[]",
            )
        val (code, stdout, stderr) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).contains("UNHEALTHY")
        assertThat(stderr).contains("unexpected daemon response shape")
    }
}
