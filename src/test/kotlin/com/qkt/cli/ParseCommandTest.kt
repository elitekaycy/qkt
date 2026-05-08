package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParseCommandTest {
    private fun runParse(file: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = ParseCommand(Args(arrayOf("parse", file))).run()
            code to (out.toString() + err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `valid strategy exits 0`() {
        val (code, out) = runParse("src/test/resources/cli/valid_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("ok")
    }

    @Test
    fun `broken strategy exits 1 with error list`() {
        val (code, out) = runParse("src/test/resources/cli/broken_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("broken_strategy.qkt:")
        assertThat(out).contains("error")
    }

    @Test
    fun `missing file exits 1`() {
        val (code, _) = runParse("does_not_exist.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
