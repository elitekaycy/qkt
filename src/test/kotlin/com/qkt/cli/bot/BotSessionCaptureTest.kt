package com.qkt.cli.bot

import com.qkt.cli.Config
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.SessionInstruments
import com.qkt.trade.session.VenueDealLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What a live session reads from its venues at finish: their deals and instrument specs, best-effort per symbol. */
class BotSessionCaptureTest {
    private val mt5 = BotMt5Stub()

    @TempDir lateinit var dir: Path

    @AfterEach
    fun stop() = mt5.close()

    private val streams = BotSessionStreams(listOf("EXNESS:XAUUSD", "OTHER:X"), listOf("1h"))

    private fun stderr(body: () -> Unit): String {
        val err = ByteArrayOutputStream()
        val prev = System.err
        System.setErr(PrintStream(err))
        try {
            body()
        } finally {
            System.setErr(prev)
        }
        return err.toString()
    }

    @Test
    fun `deals keep only the session's symbols and a venue that cannot be read is named`() {
        mt5.responses["/history_deals_get"] =
            200 to
            """[{"ticket":5,"symbol":"XAUUSDm","type":0,"price":2650,"time_msc":1000},""" +
            """{"ticket":6,"symbol":"EURUSDm","type":1,"price":1.1,"time_msc":2000}]"""
        val cfg = Config.load(mt5.config(dir))

        val err = stderr { captureVenueDeals(cfg, streams, dir, 0L) }

        val deals = VenueDealLog.read(dir.resolve(VenueDealLog.FILE))
        assertThat(deals.map { it.symbol to it.timeMs }).containsExactly("XAUUSDm" to 1000L)
        assertThat(err).contains("qkt bot: venue deals not read for OTHER:X: no broker 'OTHER' in config")
    }

    @Test
    fun `instrument specs are written for symbols whose venue states them`() {
        mt5.responses["/symbol_info_tick/XAUUSDm"] = 200 to """{"bid":2650.0,"ask":2650.5,"time":1}"""
        mt5.responses["/symbol_info/XAUUSDm"] =
            200 to """{"digits":2,"volume_min":0.01,"volume_step":0.01,"volume_max":50,"trade_contract_size":100}"""
        val cfg = Config.load(mt5.config(dir))

        val err = stderr { captureVenueInstruments(cfg, streams, dir) }

        val yaml = Files.readString(dir.resolve(SessionInstruments.FILE))
        assertThat(yaml).contains("qktSymbol: \"EXNESS:XAUUSD\"", "contractSize: 100", "volumeStep: 0.01", "digits: 2")
        assertThat(err).contains("qkt bot: venue spec not read for OTHER:X")
    }

    @Test
    fun `a venue without symbol metadata leaves no spec`() {
        mt5.responses["/symbol_info_tick/XAUUSDm"] = 200 to """{"bid":2650.0,"ask":2650.5,"time":1}"""
        val cfg = Config.load(mt5.config(dir))

        stderr { captureVenueInstruments(cfg, BotSessionStreams(listOf("EXNESS:XAUUSD"), listOf("1h")), dir) }

        assertThat(dir.resolve(SessionInstruments.FILE)).doesNotExist()
    }

    @Test
    fun `the session insights sink exists only when insights are on, journaling under the state root`() {
        val off = Config.load(mt5.config(dir))
        val onFile = dir.resolve("on.yaml")
        Files.writeString(onFile, "insights:\n  enabled: true\n  url: http://127.0.0.1:1\n  journal_enabled: true\n")

        val sink = sessionInsightsSink(Config.load(onFile), dir.resolve("state"))

        try {
            assertThat(sessionInsightsSink(off, dir.resolve("state"))).isNull()
            assertThat(sink).isNotNull
            assertThat(dir.resolve("state/bot-insights-journal")).isDirectory()
        } finally {
            sink?.close()
        }
    }
}
