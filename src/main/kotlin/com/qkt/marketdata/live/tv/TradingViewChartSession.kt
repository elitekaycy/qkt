package com.qkt.marketdata.live.tv

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class TradingViewChartSession(
    private val webSocket: TradingViewWebSocketLike,
    private val sessionIdGenerator: () -> String = ::randomSessionId,
    private val seriesIdGenerator: () -> String = ::randomSeriesId,
    private val timeoutMs: Long = 30_000L,
) {
    private val log = LoggerFactory.getLogger(TradingViewChartSession::class.java)

    fun getBars(
        symbol: String,
        resolution: String,
        count: Int,
        toTimestampSeconds: Long,
    ): List<Candle> {
        require(count > 0) { "count must be > 0: $count" }
        val sessionId = sessionIdGenerator()
        val seriesId = seriesIdGenerator()

        val collected: AtomicReference<List<Candle>> = AtomicReference(emptyList())
        val latch = CountDownLatch(1)
        val errorRef: AtomicReference<Throwable?> = AtomicReference(null)

        val listener =
            object : TradingViewListener {
                override fun onFrame(frame: TradingViewFrame) {
                    if (frame !is TradingViewFrame.Message) return
                    if (frame.method != "timescale_update") return
                    runCatching {
                        val update = frame.paramAsObject(1)
                        val series = update[seriesId]?.jsonObject ?: return
                        val rows = series["s"]?.jsonArray ?: return
                        val candles = rows.map { row -> rowToCandle(symbol, resolution, row.jsonObject) }
                        collected.set(candles)
                        latch.countDown()
                    }.onFailure { t ->
                        errorRef.set(t)
                        latch.countDown()
                    }
                }

                override fun onConnected() {}

                override fun onDisconnected(reason: String) {
                    errorRef.set(IOException("TradingView disconnected: $reason"))
                    latch.countDown()
                }
            }

        webSocket.addListener(listener)
        try {
            webSocket.send("chart_create_session", listOf(sessionId))
            webSocket.send("resolve_symbol", listOf(sessionId, "symbol_1", symbol))
            webSocket.send("create_series", listOf(sessionId, seriesId, "s1", "symbol_1", resolution, count, ""))
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("TradingView chart_session timed out after ${timeoutMs}ms for $symbol@$resolution")
            }
            errorRef.get()?.let { throw IOException("TradingView chart_session failed", it) }
            return collected.get()
        } finally {
            runCatching {
                webSocket.send("remove_series", listOf(sessionId, seriesId))
                webSocket.send("chart_delete_session", listOf(sessionId))
            }.onFailure { t -> log.debug("Cleanup send failed: ${t.message}") }
            webSocket.removeListener(listener)
        }
    }

    private fun rowToCandle(
        symbol: String,
        resolution: String,
        row: JsonObject,
    ): Candle {
        val v = row["v"]?.jsonArray ?: error("timescale_update row missing v: $row")
        val timeSeconds = v[0].jsonPrimitive.content.toLong()
        val open = v[1].jsonPrimitive.content.toBigDecimal()
        val high = v[2].jsonPrimitive.content.toBigDecimal()
        val low = v[3].jsonPrimitive.content.toBigDecimal()
        val close = v[4].jsonPrimitive.content.toBigDecimal()
        val volume =
            if (v.size > 5) {
                v[5].jsonPrimitive.content.toBigDecimalOrNull() ?: BigDecimal.ZERO
            } else {
                BigDecimal.ZERO
            }
        val durationMs = resolutionToMillis(resolution)
        val startTime = timeSeconds * 1_000L
        val endTime = startTime + durationMs
        return Candle(
            symbol = symbol,
            open = open.setScale(Money.SCALE, Money.ROUNDING),
            high = high.setScale(Money.SCALE, Money.ROUNDING),
            low = low.setScale(Money.SCALE, Money.ROUNDING),
            close = close.setScale(Money.SCALE, Money.ROUNDING),
            volume = volume.setScale(Money.SCALE, Money.ROUNDING),
            startTime = startTime,
            endTime = endTime,
        )
    }

    private fun resolutionToMillis(resolution: String): Long =
        when (resolution) {
            "1S" -> 1_000L
            "5S" -> 5_000L
            "15S" -> 15_000L
            "30S" -> 30_000L
            "1" -> 60_000L
            "5" -> 300_000L
            "15" -> 900_000L
            "30" -> 1_800_000L
            "60" -> 3_600_000L
            "240" -> 14_400_000L
            "1D" -> 86_400_000L
            "1W" -> 604_800_000L
            else -> error("Unknown resolution: $resolution")
        }

    companion object {
        private fun randomSessionId(): String {
            val chars = ('a'..'z') + ('0'..'9')
            return "cs_" + (1..8).map { chars.random(Random) }.joinToString("")
        }

        private fun randomSeriesId(): String {
            val chars = ('a'..'z') + ('0'..'9')
            return "sds_" + (1..6).map { chars.random(Random) }.joinToString("")
        }
    }
}
