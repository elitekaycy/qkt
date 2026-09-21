package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ListCommandHaltTest {
    @Test
    fun `the STATE column reads halted for a halted strategy and running for the rest`(
        @TempDir tmp: Path,
    ) {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun list(): String =
                    """[{"name":"alpha","kind":"strategy","port":1,"trades":0,"uptimeMs":1000,"state":"running","halted":true,
                        "haltReason":"operator","haltScope":"PERSISTENT"},
                        {"name":"beta","kind":"strategy","port":2,"trades":0,"uptimeMs":1000,"state":"running","halted":false}]"""
            }
        val out = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(out))
        val code =
            try {
                ListCommand(Args(arrayOf("list", "--state-dir", tmp.toString()))) { client }.run()
            } finally {
                System.setOut(saved)
            }

        val rows = out.toString().lines()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(rows.single { it.startsWith("alpha") }).endsWith("halted")
        assertThat(rows.single { it.startsWith("beta") }).endsWith("running")
    }
}
