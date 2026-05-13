package com.qkt.tools.parity

import com.qkt.marketdata.live.tv.TradingViewChartSession
import com.qkt.marketdata.live.tv.TradingViewWebSocket
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

/**
 * Compare TradingView vs MT5 historical M5 bars for XAUUSD.
 *
 * Configuration via env vars (all optional, defaults below):
 *   TV_SYMBOL=OANDA:XAUUSD
 *   MT5_URL=http://localhost:5003
 *   MT5_SYMBOL=XAUUSD
 *   BARS=300
 *   OUT=docs/parity/parity-bars-xauusd-m5.md
 *
 * Prerequisite: SSH tunnel to the MT5 gateway:
 *   ssh -L 5003:localhost:5003 root@173.249.58.247
 */
fun main() {
    val tvSymbol = System.getenv("TV_SYMBOL") ?: "OANDA:XAUUSD"
    val mt5Url = System.getenv("MT5_URL") ?: "http://localhost:5002"
    val mt5Symbol = System.getenv("MT5_SYMBOL") ?: "XAUUSDm"
    val bars = System.getenv("BARS")?.toIntOrNull() ?: 300
    val outPath = System.getenv("OUT") ?: "docs/parity/parity-bars-xauusd-m5.md"

    println("[parity] TV=$tvSymbol  MT5=$mt5Symbol @ $mt5Url  bars=$bars")

    val mt5 = Mt5DataClient(mt5Url)
    val mt5Bars =
        runCatching { mt5.fetchBarsByPos(mt5Symbol, "M5", bars) }
            .onFailure { println("[parity] MT5 fetch failed: ${it.message}") }
            .getOrThrow()
    val mt5First = Instant.ofEpochMilli(mt5Bars.first().startTime)
    val mt5Last = Instant.ofEpochMilli(mt5Bars.last().startTime)
    println("[parity] MT5 returned ${mt5Bars.size} bars; first=$mt5First last=$mt5Last")

    val ws = TradingViewWebSocket.connect()
    val tvBars =
        try {
            val session = TradingViewChartSession(ws)
            val nowSec = System.currentTimeMillis() / 1000L
            session.getBars(symbol = tvSymbol, resolution = "5", count = bars, toTimestampSeconds = nowSec)
        } finally {
            runCatching { ws.close() }
        }
    val tvFirst = Instant.ofEpochMilli(tvBars.first().startTime)
    val tvLast = Instant.ofEpochMilli(tvBars.last().startTime)
    println("[parity] TV returned ${tvBars.size} bars; first=$tvFirst last=$tvLast")

    val stats = BarsParity.compare(tv = tvBars, mt5 = mt5Bars)
    val md = stats.toMarkdown(title = "Bars parity — XAUUSD M5 — TV vs MT5", generatedAt = Instant.now())

    val out = Paths.get(outPath)
    out.parent?.let { Files.createDirectories(it) }
    Files.writeString(out, md)
    val meanDelta = stats.meanCloseDelta.toPlainString()
    println("[parity] wrote $outPath  (aligned=${stats.alignedCount}, mean |Δclose|=$meanDelta)")
}
