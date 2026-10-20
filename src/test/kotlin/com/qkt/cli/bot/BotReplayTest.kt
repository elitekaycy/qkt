package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.trade.session.ReplayDiff
import com.qkt.trade.session.ReplayFill
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt bot session replay`: a session's journal run again as a backtest, trade for trade. */
class BotReplayTest {
    private val gold = "EXNESS:XAUUSD"

    private fun replay(vararg argv: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        val code =
            try {
                BotCommand(Args(arrayOf("bot", "session", "replay", *argv))).run()
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }

    @Test
    fun `replay of a backtest session gives the same trades`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp, identities = "brain,other").start()
        h.bot("next", gold)
        h.bot("buy", "0.1", gold, "--as", "brain", "--sl", "by:3", "--tp", "by:6")
        h.bot("sell", "0.2", gold, "--as", "other")
        repeat(3) { h.bot("next", gold) }
        h.bot("close", gold, "--as", "other")
        repeat(4) { h.bot("next", gold) }
        h.finish()
        val out = tmp.resolve("replayed")

        val (code, text) = replay(h.out.toString(), "--out", out.toString())

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(Files.readString(out.resolve("trades.csv"))).isEqualTo(Files.readString(h.out.resolve("trades.csv")))
        assertThat(Files.readString(out.resolve("replay-diff.json"))).contains("\"identical\":true")
        assertThat(text).contains("\"identical\":true")
    }

    @Test
    fun `a live record replays over the ticks it traded on`(
        @TempDir tmp: Path,
    ) {
        val t0 =
            java.time.Instant
                .parse("2026-07-09T12:18:30Z")
                .toEpochMilli()
        val dir = tmp.resolve("live")
        val argv = listOf("start", "--symbols", gold, "--tf", "1m", "--identities", "brain", "--run", "r1", "--json")
        com.qkt.trade.session.writeSessionMeta(
            dir,
            com.qkt.trade.session
                .SessionMeta("r1", "live", listOf("brain"), argv, t0),
        )
        com.qkt.trade.session.LiveTickLog(dir.resolve("ticks.csv")).use { log ->
            (0 until 36).forEach { i ->
                val mid = BigDecimal("2400.00").add(BigDecimal("0.10").multiply(BigDecimal(i)))
                log.append(
                    com.qkt.marketdata.Tick(
                        gold,
                        mid,
                        t0 + i * 5_000L,
                        bid = mid - BigDecimal("0.10"),
                        ask = mid + BigDecimal("0.10"),
                    ),
                )
            }
        }
        val journal =
            com.qkt.trade.session
                .IntentJournal(dir.resolve("intents.jsonl"))
        val header = "STRATEGY bot VERSION 1\nSYMBOLS\n    x = $gold EVERY 1m\nRULES\n    WHEN true\n    THEN "
        journal.intent("int-1", "brain", header + "BUY x SIZING 0.01\n", t0 + 45_000L, t0 + 45_000L, 0L)
        journal.intent("int-2", "brain", header + "CLOSE x\n", t0 + 100_000L, t0 + 100_000L, 0L)
        journal.finish(t0 + 150_000L, 0L)
        Files.write(
            dir.resolve("fills.jsonl"),
            listOf("BUY" to 46_000L, "SELL" to 101_000L).map { (side, at) ->
                """{"identity":"brain","orderId":"o","symbol":"$gold","side":"$side","qty":0.01,"price":2401.5,""" +
                    """"timeMs":${t0 + at}}"""
            },
        )

        val (code, text) = replay(dir.toString(), "--out", tmp.resolve("out").toString())

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("\"replayedFills\":2").contains("\"sameTrades\":true")
    }

    @Test
    fun `the diff says when the trades are the same but prices moved`() {
        fun fill(
            side: String,
            price: String,
        ) = ReplayFill("brain", gold, side, BigDecimal("0.1"), BigDecimal(price), 60_000L)

        val moved = ReplayDiff.of(listOf(fill("BUY", "1850.10")), listOf(fill("BUY", "1850.30")))
        val other = ReplayDiff.of(listOf(fill("BUY", "1850.10")), listOf(fill("SELL", "1850.10")))

        assertThat(moved.sameTrades).isTrue
        assertThat(moved.json()).contains("\"sameTrades\":true")
        assertThat(other.sameTrades).isFalse
    }

    @Test
    fun `replay refuses a directory with no session record`(
        @TempDir tmp: Path,
    ) {
        val (code, text) = replay(tmp.toString())

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(text).contains("no session record")
    }

    @Test
    fun `the diff lists a fill that moved`() {
        fun fill(price: String) = ReplayFill("brain", gold, "BUY", BigDecimal("0.1"), BigDecimal(price), 60_000L)

        val diff = ReplayDiff.of(recorded = listOf(fill("1850.10")), replayed = listOf(fill("1850.30")))

        assertThat(diff.identical).isFalse
        assertThat(diff.mismatches).hasSize(1)
        assertThat(diff.json()).contains("\"recorded\":").contains("1850.10").contains("1850.30")
    }
}
