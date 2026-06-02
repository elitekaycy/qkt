package com.qkt.research

import com.qkt.common.Money
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import java.io.BufferedReader
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReplayReplTest {
    @Test
    fun `drives a scripted session and renders tape + footer`(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY sample VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > 100
                THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        val ticks = (1..6).map { Tick("BACKTEST:BTCUSDT", Money.of((100 + it).toString()), it * 60_000L) }
        val session =
            ReplaySession(
                ticks = ticks,
                strategyPath = path,
                startingBalance = BigDecimal("10000"),
                instruments = NoopInstrumentRegistry,
            )
        val input = BufferedReader(StringReader("run\nshow\nquit\n"))
        val out = StringBuilder()

        ReplayRepl(session).run(input, out)

        val text = out.toString()
        assertThat(text).contains("> ") // prompt was shown
        assertThat(text).contains("[end]") // run reached end of feed
        assertThat(text).contains("bars") // footer rendered
    }
}
