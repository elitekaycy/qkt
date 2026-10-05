package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A command that fails by throwing still ends the process with a non-zero code, naming the failure. */
class MainExitTest {
    @Test
    fun `a live run refused at start exits non-zero with the refusal instead of leaving the process running`(
        @TempDir dir: Path,
    ) {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY cont VERSION 1\nSYMBOLS\n    btc = BINANCE_UM:BTCUSDT@front EVERY 1m\n" +
                "RULES\n    WHEN btc.close > 0 THEN LOG \"x\"\n",
        )
        val config = dir.resolve("qkt.config.yaml").also { Files.writeString(it, "source: local\n") }
        val err = ByteArrayOutputStream()
        val out = System.out

        val code =
            try {
                System.setOut(PrintStream(ByteArrayOutputStream()))
                exitCodeOf(
                    arrayOf("run", strategy.toString(), "--config", config.toString(), "--no-observe"),
                    PrintStream(err),
                )
            } finally {
                System.setOut(out)
            }

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err.toString()).contains("qkt: error: continuous futures streams [BINANCE_UM:BTCUSDT@front]")
        assertThat(
            Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("OkHttp") && !it.isDaemon },
        ).describedAs("non-daemon HTTP threads left by `source: local`")
            .isEmpty()
    }
}
