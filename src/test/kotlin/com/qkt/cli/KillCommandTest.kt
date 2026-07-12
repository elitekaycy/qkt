package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KillCommandTest {
    private fun invokeKill(
        argv: Array<String>,
        client: ControlClient,
    ): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = KillCommand(Args(argv)) { client }.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `flatten reports success only when broker verified flat`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun kill(
                    name: String?,
                    flatten: Boolean,
                ): String = """{"state":"killed","flatten":true,"flattenVerified":true}"""
            }

        val (code, stdout, stderr) = invokeKill(arrayOf("kill", "alpha", "--flatten"), client)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("flattened and verified")
        assertThat(stderr).isEmpty()
    }

    @Test
    fun `flatten returns error and remaining tickets when verification fails`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun kill(
                    name: String?,
                    flatten: Boolean,
                ): String =
                    """{"state":"killed","flatten":true,"flattenVerified":false,""" +
                        """"remainingTickets":["42"]}"""
            }

        val (code, stdout, stderr) = invokeKill(arrayOf("kill", "alpha", "--flatten"), client)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).isEmpty()
        assertThat(stderr).contains("not verified", "42")
    }
}
