package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.io.TempDir

abstract class StatusCommandDeepFixture {
    protected fun invoke(
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

    protected fun fakeClient(
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
}
