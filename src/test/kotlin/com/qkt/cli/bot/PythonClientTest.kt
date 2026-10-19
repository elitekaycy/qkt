package com.qkt.cli.bot

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The Python example drives a real backtest session from another language, end to end. */
class PythonClientTest {
    private fun python(): String? =
        listOf("python3", "python").firstOrNull { cmd ->
            runCatching { ProcessBuilder(cmd, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        }

    @Test
    fun `the python agent loop trades a backtest session and finishes it`(
        @TempDir tmp: Path,
    ) {
        val python = python()
        assumeTrue(python != null, "no python on PATH")
        val h = BotSessionHarness(tmp, identities = "cross").start()
        val script = Path.of("examples/bot/python/agent_loop.py").toAbsolutePath()

        val process =
            ProcessBuilder(
                python,
                script.toString(),
                "--run",
                "harness",
                "--state-dir",
                h.stateDir.toString(),
                "--symbol",
                "EXNESS:XAUUSD",
                "--fast",
                "3",
                "--slow",
                "8",
                "--max-bars",
                "120",
            ).directory(script.parent.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)

        assertThat(process.exitValue()).withFailMessage(output).isEqualTo(0)
        assertThat(output).contains("\"bars\": 120").contains("\"report\":")
        val trades = Files.readAllLines(h.out.resolve("trades.csv")).drop(1).filter { it.contains("cross") }
        assertThat(trades).isNotEmpty()
        assertThat(Files.readString(h.out.resolve("intents.jsonl"))).contains("\"identity\":\"cross\"")
    }
}
