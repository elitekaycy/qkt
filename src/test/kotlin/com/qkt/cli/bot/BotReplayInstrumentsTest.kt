package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.trade.session.SessionInstruments
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A live session trades on the venue's own instrument spec; its record keeps it (`instruments.yaml`), so the record
 * replays on any machine. Without it a replay needed an instruments file that happened to cover the symbol: a live
 * BTCUSD session on the Exness demo replayed 0 of its 2 fills, every order refused for want of a spec (2026-10-20).
 */
class BotReplayInstrumentsTest {
    private val btc = "EXNESS:BTCUSD"

    private fun replay(vararg argv: String): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        try {
            BotCommand(Args(arrayOf("bot", "session", "replay", *argv))).run()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    private val fills = listOf(Triple("BUY", "2400.95", 47_000L), Triple("SELL", "2402.05", 102_000L))

    @Test
    fun `a record that keeps the venue's spec replays its trades`(
        @TempDir tmp: Path,
    ) {
        val dir = liveRecord(tmp, fills, symbol = btc)
        SessionInstruments.write(
            dir.resolve(SessionInstruments.FILE),
            mapOf(btc to SessionInstruments.Spec(BigDecimal.ONE, BigDecimal("0.01"), BigDecimal("0.01"), null, 2)),
        )

        val text =
            replay(dir.toString(), "--out", tmp.resolve("out").toString())

        assertThat(text).contains("\"sameTrades\":true").contains("\"replayedFills\":2")
    }

    @Test
    fun `without the spec the orders are refused, which is the gap the record closes`(
        @TempDir tmp: Path,
    ) {
        val dir = liveRecord(tmp, fills, symbol = btc)

        val text =
            replay(dir.toString(), "--out", tmp.resolve("out").toString())

        assertThat(text).contains("\"replayedFills\":0")
    }
}
