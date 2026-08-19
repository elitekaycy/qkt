package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end: a backtest bot session driven purely through `qkt bot` verbs produces
 * the standard report artifacts, and the no-session paths stay untouched.
 */
class BotSessionCliTest {
    private fun seedTicks(
        dataRoot: Path,
        days: Int,
        symbol: String = "XAUUSD",
    ) {
        val start =
            LocalDate
                .parse("2024-01-02")
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        (0 until days * 1440)
            .map { m ->
                val mid = 1850.0 + 8.0 * sin(m / 40.0)
                Tick(symbol, Money.of("%.3f".format(mid)), start + m * 60_000L)
            }.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve(symbol).resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, symbol, dayTicks)
            }
    }

    private fun bot(vararg tokens: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        val code =
            try {
                BotCommand(Args(arrayOf("bot", *tokens))).run()
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }

    @Test
    fun `backtest session over bot verbs writes the standard report`(
        @TempDir tmp: Path,
    ) {
        val dataRoot = tmp.resolve("data")
        val stateDir = tmp.resolve("state")
        val out = tmp.resolve("report")
        seedTicks(dataRoot, days = 2)

        val sessionThread =
            Thread {
                BotCommand(
                    Args(
                        arrayOf(
                            "bot",
                            "session",
                            "start",
                            "--backtest",
                            "--symbols",
                            "EXNESS:XAUUSD",
                            "--tf",
                            "15m",
                            "--from",
                            "2024-01-02",
                            "--to",
                            "2024-01-04",
                            "--identities",
                            "brain",
                            "--run",
                            "clitest",
                            "--out",
                            out.toString(),
                            "--data-root",
                            dataRoot.toString(),
                            "--state-dir",
                            stateDir.toString(),
                            "--no-fetch",
                            "--json",
                        ),
                    ),
                ).run()
            }
        sessionThread.isDaemon = true
        sessionThread.start()
        val descriptor = stateDir.resolve("state/bot/sessions/clitest/session.json")
        val deadline = System.currentTimeMillis() + 30_000L
        while (!Files.exists(descriptor) && System.currentTimeMillis() < deadline) Thread.sleep(100L)
        assertThat(Files.exists(descriptor)).describedAs("session.json appears").isTrue()

        val common = arrayOf("--run", "clitest", "--state-dir", stateDir.toString(), "--json")
        val (nextCode, nextOut) = bot("next", "EXNESS:XAUUSD", *common)
        assertThat(nextCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(nextOut).contains("\"type\":\"bar\"")

        val (buyCode, buyOut) = bot("buy", "0.5", "EXNESS:XAUUSD", "--as", "brain", *common)
        assertThat(buyCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(buyOut).contains("\"queued\":true")

        bot("next", "EXNESS:XAUUSD", *common)
        bot("next", "EXNESS:XAUUSD", *common)
        val (barsCode, barsOut) = bot("bars", "EXNESS:XAUUSD", "--count", "2", *common)
        assertThat(barsCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(barsOut).contains("\"type\":\"bar\"")
        val (posCode, posOut) = bot("positions", *common)
        assertThat(posCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(posOut).contains("EXNESS:XAUUSD")

        val (finishCode, finishOut) = bot("session", "finish", *common)
        assertThat(finishCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(finishOut).contains("\"finished\":true")
        sessionThread.join(10_000L)

        assertThat(out.resolve("result.json")).exists()
        assertThat(out.resolve("trades.csv")).exists()
        assertThat(out.resolve("equity_global.csv")).exists()
        assertThat(out.resolve("manifest.json")).exists()
        val trades = Files.readString(out.resolve("trades.csv"))
        assertThat(trades).contains("brain")
        assertThat(Files.exists(descriptor)).describedAs("descriptor removed on finish").isFalse()
    }

    @Test
    fun `without a session bot next fails with guidance and quote keeps venue behavior`(
        @TempDir tmp: Path,
    ) {
        val stateDir = tmp.resolve("state")
        val (code, _) = bot("next", "EXNESS:XAUUSD", "--state-dir", stateDir.toString(), "--json")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
