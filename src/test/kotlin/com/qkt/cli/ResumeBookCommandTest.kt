package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** #1640: `qkt resume <book>` says which book halt it cleared; `qkt list` says why each book is halted. */
class ResumeBookCommandTest {
    private val reason = "portfolio risk evaluation failed 3 consecutive times: busy"

    private fun capture(run: () -> Int): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            Triple(run(), out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    private fun client(
        tmp: Path,
        resume: (String?) -> String = { error("no resume expected") },
        list: () -> String = { error("no list expected") },
    ) = object : ControlClient(StateDir.resolve(tmp.toString())) {
        override fun resume(name: String?): String = resume(name)

        override fun list(): String = list()
    }

    @Test
    fun `resuming a halted book reports the halt it cleared`(
        @TempDir tmp: Path,
    ) {
        val c =
            client(tmp, resume = { """{"state":"resumed","affected":["soak_k56"],"bookHalts":{"$it":"$reason"}}""" })

        val (code, out, _) = capture { ResumeCommand(Args(arrayOf("resume", "soak_k56"))) { c }.run() }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("cleared the halt on portfolio soak_k56: $reason").contains("resumed soak_k56")
    }

    @Test
    fun `a child refused while its book is halted fails with the daemon's reason`(
        @TempDir tmp: Path,
    ) {
        val why = "portfolio soak_k56 is halted (PERSISTENT): $reason; `qkt resume soak_k56` clears the book"
        val c = client(tmp, resume = { throw ControlClient.DaemonError(409, """{"error":"$why"}""") })

        val (code, _, err) = capture { ResumeCommand(Args(arrayOf("resume", "soak_k56:coin"))) { c }.run() }

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("qkt: error: $why").doesNotContain("unknown strategy")
    }

    @Test
    fun `list says under the table why each halted book is halted`(
        @TempDir tmp: Path,
    ) {
        val body =
            """[{"name":"soak_k56","kind":"portfolio","childAliases":["coin"],"uptimeMs":1,"state":"running",""" +
                """"halted":true,"haltReason":"$reason","haltScope":"PERSISTENT"},""" +
                """{"name":"soak_k56/coin","kind":"child","parent":"soak_k56","port":1,"trades":0,"uptimeMs":1,""" +
                """"state":"running","halted":true,"haltReason":"$reason","haltScope":"PERSISTENT"}]"""
        val c = client(tmp, list = { body })

        val (code, out, _) = capture { ListCommand(Args(arrayOf("list"))) { c }.run() }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val lines = out.lines()
        assertThat(lines.single { it.startsWith("soak_k56 ") }).endsWith("halted")
        assertThat(lines).contains("portfolio soak_k56 halted (PERSISTENT): $reason")
    }
}
