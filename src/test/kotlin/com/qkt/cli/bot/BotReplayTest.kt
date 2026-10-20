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
        val dir = liveRecord(tmp, listOf(Triple("BUY", "2400.95", 45_000L), Triple("SELL", "2401.95", 100_000L)))

        val (code, text) = replay(dir.toString(), "--out", tmp.resolve("out").toString())

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("\"replayedFills\":2").contains("\"sameTrades\":true")
        // Over the ticks it recorded, each intent enters where it entered live: the same fills, to the tick.
        assertThat(text).contains("\"identical\":true")
    }

    @Test
    fun `a live replay takes the venue's measured round trip, filling at the quote standing when it ends`(
        @TempDir tmp: Path,
    ) {
        // The venue took 6 s each time: the buy fired on tick 9 filled at tick 10's ask, the close at tick 21's bid.
        val dir = liveRecord(tmp, listOf(Triple("BUY", "2401.05", 51_000L), Triple("SELL", "2402.05", 106_000L)))

        val (code, text) = replay(dir.toString(), "--out", tmp.resolve("out").toString())

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("\"latencyMs\":6000").contains("\"samePrices\":true")
        val diff = Files.readString(tmp.resolve("out").resolve("replay-diff.json"))
        val prices = Regex(""""price":([0-9.]+)""").findAll(diff).map { BigDecimal(it.groupValues[1]) }.toList()
        // Each mismatch row is recorded then replayed: a row may differ only in its time, never its price.
        prices.chunked(2).forEach { (recorded, replayed) -> assertThat(replayed).isEqualByComparingTo(recorded) }
    }

    @Test
    fun `a trace replays each fill at the quote standing when the venue filled it, whatever its round trip`(
        @TempDir tmp: Path,
    ) {
        // Round trips of 3 s and 12 s: no single latency lands both, the trace lands each (ticks 9 and 22).
        val dir = liveRecord(tmp, listOf(Triple("BUY", "2400.95", 48_000L), Triple("SELL", "2402.15", 112_000L)))

        val (code, text) = replay(dir.toString(), "--out", tmp.resolve("out").toString(), "--trace")

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("\"samePrices\":true").contains("\"replay\":\"trace\"")
    }

    @Test
    fun `a live intent re-enters on the tick its fate says it fired on`(
        @TempDir tmp: Path,
    ) {
        // The buy arrived at 42 s but the engine, behind a burst, fired it on tick 11 (55 s): filled at its ask.
        val dir = liveRecord(tmp, listOf(Triple("BUY", "2401.15", 55_000L), Triple("SELL", "2401.95", 100_000L)))
        val t0 = Files.readAllLines(dir.resolve("ticks.csv"))[1].substringBefore(',').toLong()
        Files.writeString(
            dir.resolve("intents.jsonl"),
            """{"type":"fate","intentId":"int-1","identity":"brain","state":"fired","orderIds":[],"reason":null,""" +
                """"atMs":${t0 + 55_000L},"compiled":true,"tickMs":${t0 + 55_000L}}""" + "\n",
            java.nio.file.StandardOpenOption.APPEND,
        )

        val (code, text) = replay(dir.toString(), "--out", tmp.resolve("out").toString())

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("\"identical\":true")
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
        assertThat(moved.samePrices).isFalse
        assertThat(moved.json()).contains("\"sameTrades\":true").contains("\"samePrices\":false")
        val later = ReplayDiff.of(listOf(fill("BUY", "1850.10")), listOf(fill("BUY", "1850.10").copy(timeMs = 61_000L)))
        assertThat(later.samePrices).isTrue
        assertThat(later.identical).isFalse
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
