package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StatusCommandDeepTest {
    private fun invoke(
        argv: Array<String>,
        client: ControlClient,
    ): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = StatusCommand(Args(argv)) { client }.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    private fun fakeClient(
        @TempDir tmp: java.nio.file.Path,
        healthBody: String,
        listBody: String = "[]",
        statusBody: String = "[]",
        healthThrows: Exception? = null,
    ): ControlClient =
        object : ControlClient(StateDir.resolve(tmp.toString())) {
            override fun health(): String = healthThrows?.let { throw it } ?: healthBody

            override fun list(): String = listBody

            override fun status(name: String?): String = statusBody
        }

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
