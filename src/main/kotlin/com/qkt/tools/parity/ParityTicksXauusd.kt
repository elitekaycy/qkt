package com.qkt.tools.parity

import com.qkt.common.SystemClock
import com.qkt.marketdata.live.tv.TradingViewFrame
import com.qkt.marketdata.live.tv.TradingViewListener
import com.qkt.marketdata.live.tv.TradingViewQuoteSession
import com.qkt.marketdata.live.tv.TradingViewWebSocket
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Compare live TradingView vs MT5 ticks for XAUUSD over a fixed window.
 *
 * Env vars (all optional):
 *   TV_SYMBOL=OANDA:XAUUSD
 *   MT5_URL=http://localhost:5002
 *   MT5_SYMBOL=XAUUSDm
 *   DURATION_SECONDS=60
 *   MT5_POLL_MS=50
 *   OUT=docs/parity/parity-ticks-xauusd.md
 *
 * Prerequisite: SSH tunnel to the MT5 gateway:
 *   ssh -L 5002:localhost:5002 root@173.249.58.247
 */
fun main() {
    val tvSymbol = System.getenv("TV_SYMBOL") ?: "OANDA:XAUUSD"
    val mt5Url = System.getenv("MT5_URL") ?: "http://localhost:5002"
    val mt5Symbol = System.getenv("MT5_SYMBOL") ?: "XAUUSDm"
    val duration = System.getenv("DURATION_SECONDS")?.toLongOrNull() ?: 60L
    val pollMs = System.getenv("MT5_POLL_MS")?.toLongOrNull() ?: 50L
    val outPath = System.getenv("OUT") ?: "docs/parity/parity-ticks-xauusd.md"

    println("[parity-ticks] TV=$tvSymbol  MT5=$mt5Symbol @ $mt5Url  duration=${duration}s  pollMs=$pollMs")

    val captures = CopyOnWriteArrayList<CapturedTick>()
    val ws = TradingViewWebSocket.connect()
    val sniff = System.getenv("TV_SNIFF") == "1"
    if (sniff) {
        ws.addListener(
            object : TradingViewListener {
                override fun onFrame(frame: TradingViewFrame) {
                    val s = frame.toString()
                    println("[tv-frame] ${s.take(200)}")
                }

                override fun onConnected() {
                    println("[tv-frame] onConnected")
                }

                override fun onDisconnected(reason: String) {
                    println("[tv-frame] onDisconnected reason=$reason")
                }
            },
        )
    }
    val quoteSession = TradingViewQuoteSession(webSocket = ws, clock = SystemClock())

    quoteSession.subscribe(
        symbols = listOf(tvSymbol),
        onTick = { tick ->
            captures.add(
                CapturedTick(
                    source = CapturedTick.Source.TV,
                    capturedAtMs = tick.timestamp,
                    brokerTimeMs = null,
                    bid = tick.bid,
                    ask = tick.ask,
                    last = tick.price,
                ),
            )
        },
        onError = { t -> System.err.println("[tv] error: ${t.message}") },
        onDisconnect = { System.err.println("[tv] disconnected") },
    )

    val mt5 = Mt5TickClient(mt5Url)
    val startMs = System.currentTimeMillis()
    val deadlineMs = startMs + duration * 1000L

    val mt5Thread =
        Thread({
            mt5.pollUntil(symbol = mt5Symbol, deadlineMs = deadlineMs, intervalMs = pollMs) { t ->
                captures.add(
                    CapturedTick(
                        source = CapturedTick.Source.MT5,
                        capturedAtMs = t.capturedAtMs,
                        brokerTimeMs = t.brokerTimeMs,
                        bid = t.bid,
                        ask = t.ask,
                        last = t.last,
                    ),
                )
            }
        }, "mt5-poll")
    mt5Thread.start()

    Thread.sleep(duration * 1000L)
    runCatching { quoteSession.close() }
    runCatching { ws.close() }
    mt5Thread.join(5_000L)

    val tvCount = captures.count { it.source == CapturedTick.Source.TV }
    val mt5Count = captures.count { it.source == CapturedTick.Source.MT5 }
    println("[parity-ticks] captured: TV=$tvCount  MT5=$mt5Count")

    val stats = TicksParity.compare(captures.toList(), windowMs = duration * 1000L)
    val md =
        stats.toMarkdown(
            title = "Ticks parity — XAUUSD — TV vs MT5",
            windowSeconds = duration,
            generatedAt = Instant.now(),
        )

    val out = Paths.get(outPath)
    out.parent?.let { Files.createDirectories(it) }
    Files.writeString(out, md)
    println(
        "[parity-ticks] wrote $outPath  (paired=${stats.pairedCount}, " +
            "mean |Δmid|=${stats.meanMidDelta.toPlainString()})",
    )
}
