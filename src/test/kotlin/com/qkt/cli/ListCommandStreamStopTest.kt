package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ListCommandStreamStopTest {
    private val stop = """"streamStops":[{"strategyId":"s","stream":"D:BTC@front","reason":"roll failed"}]"""

    private fun rows(
        tmp: Path,
        vararg flags: String,
    ): List<String> {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun list(): String =
                    """[{"name":"alpha","kind":"strategy","uptimeMs":1,"state":"running","halted":false,$stop},
                        {"name":"beta","kind":"strategy","uptimeMs":1,"state":"running","halted":true,$stop},
                        {"name":"gamma","kind":"strategy","uptimeMs":1,"state":"running","halted":false}]"""
            }
        val out = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(out))
        try {
            ListCommand(Args(arrayOf("list", "--state-dir", tmp.toString(), *flags))) { client }.run()
        } finally {
            System.setOut(saved)
        }
        return out.toString().lines()
    }

    @Test
    fun `the STATE column names a stopped stream, beside a halt, and stays running without one`(
        @TempDir tmp: Path,
    ) {
        val rows = rows(tmp)

        assertThat(rows.single { it.startsWith("alpha") }).endsWith(" stream_stopped")
        assertThat(rows.single { it.startsWith("beta") }).endsWith(" halted,stream_stopped")
        assertThat(rows.single { it.startsWith("gamma") }).endsWith(" running")
    }

    @Test
    fun `json keeps state as running and carries the stop beside it`(
        @TempDir tmp: Path,
    ) {
        val json = rows(tmp, "--json").joinToString("\n")

        assertThat(json).contains(""""state":"running"""").contains(stop)
    }
}
