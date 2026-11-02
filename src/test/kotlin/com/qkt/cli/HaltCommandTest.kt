package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HaltCommandTest {
    private fun invokeHalt(
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
            val code = HaltCommand(Args(argv)) { client }.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    private fun invokeResume(
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
            val code = ResumeCommand(Args(argv)) { client }.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `halt with no name calls halt(null) and prints all strategies`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        var capturedName: String? = "sentinel"
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun halt(name: String?): String {
                    capturedName = name
                    return """{"state":"halted","affected":["alpha","beta"]}"""
                }
            }
        val (code, stdout, _) = invokeHalt(arrayOf("halt"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(capturedName).isNull()
        assertThat(stdout).contains("all strategies")
    }

    @Test
    fun `halt with name calls halt(name) and prints strategy name`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        var capturedName: String? = null
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun halt(name: String?): String {
                    capturedName = name
                    return """{"state":"halted","affected":["alpha"]}"""
                }
            }
        val (code, stdout, _) = invokeHalt(arrayOf("halt", "alpha"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(capturedName).isEqualTo("alpha")
        assertThat(stdout).contains("alpha")
    }

    @Test
    fun `halt maps DaemonError 404 to USER_ERROR`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun halt(name: String?): String =
                    throw DaemonError(404, """{"error":"unknown strategy: ghost"}""")
            }
        val (code, _, stderr) = invokeHalt(arrayOf("halt", "ghost"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("unknown strategy")
    }

    @Test
    fun `halt maps NoDaemonRunningException to USER_ERROR`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun halt(name: String?): String = throw NoDaemonRunningException("no control.port file")
            }
        val (code, _, stderr) = invokeHalt(arrayOf("halt"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("no control.port file")
    }

    @Test
    fun `resume with no name calls resume(null) and prints all strategies`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        var capturedName: String? = "sentinel"
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resume(name: String?): String {
                    capturedName = name
                    return """{"state":"resumed","affected":["alpha","beta"]}"""
                }
            }
        val (code, stdout, _) = invokeResume(arrayOf("resume"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(capturedName).isNull()
        assertThat(stdout).contains("all strategies")
    }

    @Test
    fun `resume maps DaemonError 404 to USER_ERROR`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resume(name: String?): String =
                    throw DaemonError(404, """{"error":"unknown strategy: ghost"}""")
            }
        val (code, _, stderr) = invokeResume(arrayOf("resume", "ghost"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("unknown strategy")
    }

    @Test
    fun `resume whose stream stop stands fails and says why`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resume(name: String?): String =
                    """{"state":"stream_stopped","affected":["alpha"],"streamStops":[{"strategy":"alpha",""" +
                        """"stream":"X@front","stop":"roll failed","cleared":false,"stands":"position",""" +
                        """"summary":"alpha stays stopped on X@front"}]}"""
            }
        val (code, stdout, stderr) = invokeResume(arrayOf("resume", "alpha"), client)
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stdout).doesNotContain("resumed")
        assertThat(stderr).contains("alpha stays stopped on X@front")
    }

    @Test
    fun `resume that clears a stream stop says so and succeeds`(
        @TempDir tmp: java.nio.file.Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resume(name: String?): String =
                    """{"state":"resumed","affected":["alpha"],"streamStops":[{"strategy":"alpha",""" +
                        """"stream":"X@front","stop":"roll failed","cleared":true,"stands":null,""" +
                        """"summary":"cleared the stop on X@front for alpha"}]}"""
            }
        val (code, stdout, _) = invokeResume(arrayOf("resume", "alpha"), client)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("cleared the stop on X@front for alpha", "resumed alpha")
    }
}
