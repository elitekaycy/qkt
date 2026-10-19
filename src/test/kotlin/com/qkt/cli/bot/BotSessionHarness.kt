package com.qkt.cli.bot

import com.qkt.cli.Args
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

/**
 * A backtest bot session over two days of seeded 1-minute XAUUSD ticks, started in the background and
 * driven through `qkt bot` verbs, as an agent would. [bot] runs one verb against it and returns the
 * exit code and stdout; [common] are the flags that find the session.
 */
internal class BotSessionHarness(
    private val tmp: Path,
    private val run: String = "harness",
    private val tf: String = "15m",
    private val identities: String = "brain",
) {
    val stateDir: Path = tmp.resolve("state")
    val out: Path = tmp.resolve("report")
    val common = arrayOf("--run", run, "--state-dir", stateDir.toString(), "--json")
    private lateinit var thread: Thread

    fun start(): BotSessionHarness {
        val dataRoot = tmp.resolve("data")
        seedTicks(dataRoot)
        val argv =
            arrayOf(
                "bot",
                "session",
                "start",
                "--backtest",
                "--symbols",
                "EXNESS:XAUUSD",
                "--tf",
                tf,
                "--from",
                "2024-01-02",
                "--to",
                "2024-01-04",
                "--identities",
                identities,
                "--run",
                run,
                "--out",
                out.toString(),
                "--data-root",
                dataRoot.toString(),
                "--state-dir",
                stateDir.toString(),
                "--no-fetch",
                "--json",
            )
        thread = Thread { BotCommand(Args(argv)).run() }.apply { isDaemon = true }
        thread.start()
        val descriptor = stateDir.resolve("state/bot/sessions/$run/session.json")
        val deadline = System.currentTimeMillis() + 30_000L
        while (!Files.exists(descriptor) && System.currentTimeMillis() < deadline) Thread.sleep(100L)
        check(Files.exists(descriptor)) { "session $run did not start" }
        return this
    }

    fun bot(vararg tokens: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        val code =
            try {
                BotCommand(Args(arrayOf("bot", *tokens, *common))).run()
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }

    /** Finishes the session and waits for it to write its report. */
    fun finish(vararg flags: String): String {
        val (_, text) = bot("session", "finish", *flags)
        thread.join(15_000L)
        return text
    }

    private fun seedTicks(dataRoot: Path) {
        val start =
            LocalDate
                .parse("2024-01-02")
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        (0 until 2 * 1440)
            .map { m -> Tick("XAUUSD", Money.of("%.3f".format(1850.0 + 8.0 * sin(m / 40.0))), start + m * 60_000L) }
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve("XAUUSD").resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, "XAUUSD", dayTicks)
            }
    }
}
