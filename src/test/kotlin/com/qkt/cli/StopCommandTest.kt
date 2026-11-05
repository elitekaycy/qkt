package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt stop` says `stopped` only for a stop that left nothing held. */
class StopCommandTest {
    private val notFlat =
        """{"name":"alpha","state":"not_flat","trades":1,"flat":false,""" +
            """"held":["-0.001 of D:BTC_4NOV26"],"detail":"still holds it: the stream is stopped"}"""

    private fun stop(
        tmp: Path,
        answer: String,
        vararg argv: String,
    ): Triple<Int, String, String> {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun stop(
                    name: String,
                    flatten: Boolean,
                    timeoutMs: Long?,
                ): String = answer
            }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = StopCommand(Args(arrayOf("stop", "alpha", "--flatten", *argv))) { client }.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `a flat stop prints stopped`(
        @TempDir tmp: Path,
    ) {
        val (code, stdout, _) = stop(tmp, """{"name":"alpha","state":"stopped","trades":1,"flat":true}""")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("[INFO] stopped alpha")
    }

    @Test
    fun `a stop still holding fails, names what is held and why, and never prints stopped`(
        @TempDir tmp: Path,
    ) {
        val (code, stdout, stderr) = stop(tmp, notFlat)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).doesNotContain("stopped")
        assertThat(stderr).contains("alpha is not flat")
        assertThat(stderr).contains("-0.001 of D:BTC_4NOV26")
        assertThat(stderr).contains("the stream is stopped")
    }

    @Test
    fun `json output of a stop still holding is the daemon's answer, and still fails`(
        @TempDir tmp: Path,
    ) {
        val (code, stdout, _) = stop(tmp, notFlat, "--json")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout.trim()).isEqualTo(notFlat)
    }
}
