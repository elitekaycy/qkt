package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResyncCommandTest {
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
            val code = ResyncCommand(Args(argv)) { client }.run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `resync calls control client with name file and dry run`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        var capturedName: String? = null
        var capturedFile: Path? = null
        var capturedDryRun: Boolean? = null
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resync(
                    name: String,
                    file: Path,
                    dryRun: Boolean,
                    ignoreMismatches: Boolean,
                    waiver: String?,
                    waiverReason: String?,
                ): String {
                    capturedName = name
                    capturedFile = file
                    capturedDryRun = dryRun
                    return """{"name":"alpha","kind":"strategy","state":"planned","dryRun":true}"""
                }
            }

        val (code, stdout, stderr) =
            invoke(arrayOf("resync", file.toString(), "--as", "alpha", "--dry-run"), client)

        assertThat(code).withFailMessage(stderr).isEqualTo(ExitCodes.SUCCESS)
        assertThat(capturedName).isEqualTo("alpha")
        assertThat(capturedFile).isEqualTo(file.toAbsolutePath())
        assertThat(capturedDryRun).isTrue()
        assertThat(stdout).contains("alpha")
        assertThat(stdout).contains("planned")
    }

    @Test
    fun `resync prints raw json when json flag is set`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resync(
                    name: String,
                    file: Path,
                    dryRun: Boolean,
                    ignoreMismatches: Boolean,
                    waiver: String?,
                    waiverReason: String?,
                ): String = """{"name":"alpha","kind":"strategy","state":"running","dryRun":false}"""
            }

        val (code, stdout, _) = invoke(arrayOf("resync", file.toString(), "--as", "alpha", "--json"), client)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout.trim()).isEqualTo("""{"name":"alpha","kind":"strategy","state":"running","dryRun":false}""")
    }

    @Test
    fun `resync maps daemon errors to user errors`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resync(
                    name: String,
                    file: Path,
                    dryRun: Boolean,
                    ignoreMismatches: Boolean,
                    waiver: String?,
                    waiverReason: String?,
                ): String = throw DaemonError(404, """{"error":"unknown strategy: alpha"}""")
            }

        val (code, _, stderr) = invoke(arrayOf("resync", file.toString(), "--as", "alpha"), client)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("unknown strategy")
    }

    @Test
    fun `resync requires a waiver reason`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val client = ControlClient(StateDir.resolve(tmp.toString()))

        val (code, _, stderr) = invoke(arrayOf("resync", file.toString(), "--as", "alpha", "--waive"), client)

        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("--waive requires --reason")
    }
}
