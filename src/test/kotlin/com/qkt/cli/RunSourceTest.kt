package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt run` takes its fallback market source from the config's `source:`, as `qkt daemon` does. */
class RunSourceTest {
    @Test
    fun `a run with source local opens no TradingView socket`(
        @TempDir dir: Path,
    ) {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY cont VERSION 1\nSYMBOLS\n    btc = BINANCE_UM:BTCUSDT@front EVERY 1m\n" +
                "RULES\n    WHEN btc.close > 0 THEN LOG \"x\"\n",
        )
        val config = dir.resolve("qkt.config.yaml").also { Files.writeString(it, "source: local\n") }
        val before = httpThreads()
        val out = System.out

        val code =
            try {
                System.setOut(PrintStream(ByteArrayOutputStream()))
                exitCodeOf(
                    arrayOf("run", strategy.toString(), "--config", config.toString(), "--no-observe"),
                    PrintStream(ByteArrayOutputStream()),
                )
            } finally {
                System.setOut(out)
            }

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(httpThreads() - before).describedAs("HTTP client threads the run started").isEmpty()
    }

    private fun httpThreads(): Set<Thread> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive && it.name.startsWith("OkHttp") && !it.isDaemon }
            .toSet()
}
