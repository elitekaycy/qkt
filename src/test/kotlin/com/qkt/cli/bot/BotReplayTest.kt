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
