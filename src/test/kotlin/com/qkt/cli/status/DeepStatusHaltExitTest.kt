package com.qkt.cli.status

import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeepStatusHaltExitTest {
    private fun run(list: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(out))
        val code =
            try {
                renderDeepStatus("""{"status":"ok","uptimeMs":60000}""", list)
            } finally {
                System.setOut(saved)
            }
        return code to out.toString()
    }

    @Test
    fun `a risk halt is named on the first line and does not fail the health check`() {
        val (code, screen) =
            run(
                """[{"name":"silver","kind":"strategy","state":"running","trades":0,"uptimeMs":1000,
                    "halted":true,"haltReason":"strategy drawdown 0.0896 exceeds max 0.05","haltScope":"PERSISTENT"}]""",
            )

        // Container healthchecks and deploy verification run this command. A drawdown halt is the
        // engine working; failing here marked a healthy quant-live daemon unhealthy on v0.52.0.
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(screen.lines().first()).isEqualTo("qkt: HEALTHY (1 halted by risk control)")
        assertThat(screen).contains("[HALTED]").contains("clear with: qkt resume silver")
    }

    @Test
    fun `a strategy that is not running still fails it`() {
        val (code, screen) =
            run("""[{"name":"silver","kind":"strategy","state":"stopped","trades":0,"uptimeMs":1000,"halted":false}]""")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(screen.lines().first()).startsWith("qkt: UNHEALTHY")
    }
}
