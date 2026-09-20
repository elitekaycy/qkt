package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StatusCommandDeepRoutingTest : StatusCommandDeepFixture() {
    @Test
    fun `deep renders per-stream broker routing when daemon includes streamBrokers`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = """{"status":"ok","strategies":1,"uptimeMs":300000}""",
                listBody =
                    """[
                        {"name":"multi","kind":"strategy","port":47001,"trades":2,
                         "uptimeMs":300000,"state":"running",
                         "streamBrokers":{"gold":"EXNESS","spx":"ICMARKETS"}}
                    ]""",
            )
        val (code, stdout, _) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("streams:")
        assertThat(stdout).contains("gold→EXNESS")
        assertThat(stdout).contains("spx→ICMARKETS")
    }

    @Test
    fun `deep omits streams line when streamBrokers is absent`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = """{"status":"ok","strategies":1,"uptimeMs":300000}""",
                listBody =
                    """[
                        {"name":"alpha","kind":"strategy","port":47001,"trades":1,
                         "uptimeMs":300000,"state":"running"}
                    ]""",
            )
        val (code, stdout, _) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).doesNotContain("streams:")
    }
}
