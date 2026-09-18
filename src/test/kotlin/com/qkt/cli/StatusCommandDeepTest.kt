package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StatusCommandDeepTest : StatusCommandDeepFixture() {
    @Test
    fun `deep returns 0 and prints HEALTHY when daemon and strategies are healthy`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = """{"status":"ok","strategies":2,"uptimeMs":300000}""",
                listBody =
                    """[
                        {"name":"alpha","kind":"strategy","port":47001,"trades":5,"uptimeMs":290000,"state":"running"},
                        {"name":"beta","kind":"strategy","port":47002,"trades":1,"uptimeMs":120000,"state":"running"}
                    ]""",
            )
        val (code, stdout, _) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).startsWith("qkt: HEALTHY")
        assertThat(stdout).contains("DAEMON       running")
        assertThat(stdout).contains("CONTROL      reachable")
        assertThat(stdout).contains("alpha")
        assertThat(stdout).contains("beta")
        assertThat(stdout).contains("5 trades")
    }

    @Test
    fun `deep returns 1 and reports unhealthy strategy when one is in error state`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = """{"status":"ok","strategies":2,"uptimeMs":300000}""",
                listBody =
                    """[
                        {"name":"alpha","kind":"strategy","port":47001,"trades":5,"uptimeMs":290000,"state":"running"},
                        {"name":"beta","kind":"strategy","port":47002,"trades":0,"uptimeMs":1000,"state":"error"}
                    ]""",
            )
        val (code, stdout, stderr) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).startsWith("qkt: UNHEALTHY (1 issue)")
        assertThat(stderr).contains("strategy 'beta' state=error")
    }

    @Test
    fun `deep returns 1 when no daemon is running`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = "ignored",
                listBody = "ignored",
                healthThrows = ControlClient.NoDaemonRunningException("no control.port file"),
            )
        val (code, stdout, stderr) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).contains("UNHEALTHY")
        assertThat(stderr).contains("DAEMON       not running")
    }

    @Test
    fun `deep reports operator-stopped child as unhealthy`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = """{"status":"ok","strategies":1,"uptimeMs":300000}""",
                listBody =
                    """[
                        {"name":"port_a/child1","kind":"child","parent":"port_a","port":47010,
                         "trades":3,"uptimeMs":250000,"state":"running","gateState":"operator_stopped"}
                    ]""",
            )
        val (code, _, stderr) = invoke(arrayOf("status", "--deep"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("operator-stopped")
    }

    @Test
    fun `deep reports enforced promotion gates as unhealthy`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            fakeClient(
                tmp,
                healthBody = """{"status":"ok","strategies":1,"uptimeMs":300000}""",
                listBody =
                    """[
                        {"name":"alpha","kind":"strategy","port":47001,"trades":1,
                         "uptimeMs":300000,"state":"running",
                         "promotionEnforced":true,
                         "promotionState":"candidate",
                         "promotionEligible":false,
                         "promotionMissingGates":["state:production","operator_approval"]}
                    ]""",
            )
        val (code, stdout, stderr) = invoke(arrayOf("status", "--deep"), client)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).contains("promotion: candidate eligible=no")
        assertThat(stdout).contains("missing=state:production,operator_approval")
        assertThat(stderr).contains("promotion gates missing: state:production,operator_approval")
    }

    @Test
    fun `shallow status without --deep still works unchanged`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val statusJson = """[{"name":"alpha","state":"running"}]"""
        val client = fakeClient(tmp, healthBody = "unused", statusBody = statusJson)
        val (code, stdout, _) = invoke(arrayOf("status"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout.trim()).isEqualTo(statusJson)
    }
}
