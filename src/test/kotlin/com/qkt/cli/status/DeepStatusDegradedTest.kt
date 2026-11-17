package com.qkt.cli.status

import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeepStatusDegradedTest {
    @Test
    fun `a daemon that reports degraded is shown as such and fails the check`() {
        val run = captureCliRun { renderDeepStatus("""{"status":"degraded","uptimeMs":60000}""", "[]") }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out).contains("DAEMON       degraded (uptime ").contains("qkt: UNHEALTHY")
        assertThat(run.out + run.err).contains("daemon status=degraded")
    }
}
