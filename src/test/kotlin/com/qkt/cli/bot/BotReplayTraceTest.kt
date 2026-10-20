package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.connectivity.DirectDeal
import com.qkt.trade.session.VenueDealLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `session replay --trace`: a live record's fills at the quotes the venue executed them at. */
class BotReplayTraceTest {
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

    private fun deal(
        side: String,
        price: String,
        timeMs: Long,
    ) = DirectDeal(
        "d",
        "p",
        "XAUUSDm",
        side,
        "IN",
        BigDecimal("0.01"),
        BigDecimal(price),
        null,
        null,
        null,
        null,
        timeMs,
        null,
    )

    @Test
    fun `a trace fills at the quote standing when the venue executed, not when its confirmation arrived`(
        @TempDir tmp: Path,
    ) {
        // The venue executed at 47 s and 107 s (ticks 9 and 21); the confirmations came after ticks 10 and 22.
        val dir = liveRecord(tmp, listOf(Triple("BUY", "2400.95", 51_000L), Triple("SELL", "2402.05", 112_000L)))
        val t0 = Files.readAllLines(dir.resolve("ticks.csv"))[1].substringBefore(',').toLong()
        VenueDealLog.write(
            dir.resolve(VenueDealLog.FILE),
            listOf(deal("BUY", "2400.95", t0 + 47_000L), deal("SELL", "2402.05", t0 + 107_000L)),
        )

        val (code, text) = replay(dir.toString(), "--out", tmp.resolve("out").toString(), "--trace")

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("\"samePrices\":true").contains("\"venueDeals\":2")
    }

    @Test
    fun `a record with the venue's deals replays what happened by default, and predicts only when asked`(
        @TempDir tmp: Path,
    ) {
        val dir = liveRecord(tmp, listOf(Triple("BUY", "2400.95", 51_000L), Triple("SELL", "2402.05", 112_000L)))
        val t0 = Files.readAllLines(dir.resolve("ticks.csv"))[1].substringBefore(',').toLong()
        VenueDealLog.write(
            dir.resolve(VenueDealLog.FILE),
            listOf(deal("BUY", "2400.95", t0 + 47_000L), deal("SELL", "2402.05", t0 + 107_000L)),
        )

        val (_, replayed) = replay(dir.toString(), "--out", tmp.resolve("out").toString())
        val (_, predicted) = replay(dir.toString(), "--out", tmp.resolve("out2").toString(), "--predict")

        assertThat(replayed).contains("\"replay\":\"trace\"").contains("\"samePrices\":true")
        assertThat(predicted).contains("\"replay\":\"venue-latency\"")
    }

    @Test
    fun `the deal log reads back what the venue reported`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve(VenueDealLog.FILE)

        VenueDealLog.write(path, listOf(deal("BUY", "4134.927", 1_792_463_833_210L)))

        val read = VenueDealLog.read(path).single()
        assertThat(read.side).isEqualTo("BUY")
        assertThat(read.price).isEqualByComparingTo("4134.927")
        assertThat(read.timeMs).isEqualTo(1_792_463_833_210L)
    }
}
