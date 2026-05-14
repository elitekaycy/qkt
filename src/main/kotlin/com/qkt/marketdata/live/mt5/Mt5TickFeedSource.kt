package com.qkt.marketdata.live.mt5

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

/**
 * Polling [LiveTickSource] over the `mt5-gateway` HTTP `/symbol_info_tick/{symbol}` endpoint.
 *
 * Round-robins across [symbols] each iteration, dedupes per-symbol by `time_msc`, sleeps
 * [pollIntervalMs] between rounds. One daemon thread per source instance.
 *
 * When [calendar] is supplied, the poller skips iterations outside the trading session
 * (sleeps [outOfSessionSleepMs] instead). Saves ~30% of polls for FX/metals across a week
 * and avoids log spam from stale ticks.
 */
class Mt5TickFeedSource(
    private val baseUrl: String,
    private val symbolMap: Map<String, String>,
    private val pollIntervalMs: Long = 50L,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
    private val calendar: TradingCalendar? = null,
    private val outOfSessionSleepMs: Long = 60_000L,
) : LiveTickSource {
    private val symbols: List<String> = symbolMap.keys.toList()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
    ) {
        check(running.compareAndSet(false, true)) { "Mt5TickFeedSource already started" }
        val client = Mt5TickClient(baseUrl, http)
        val lastBrokerMs = mutableMapOf<String, Long>()
        thread =
            Thread({
                try {
                    while (running.get()) {
                        if (calendar != null &&
                            symbols.isNotEmpty() &&
                            !calendar.isInSession(symbols.first(), Instant.ofEpochMilli(clock.now()))
                        ) {
                            try {
                                Thread.sleep(outOfSessionSleepMs)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@Thread
                            }
                            continue
                        }
                        for (sym in symbols) {
                            if (!running.get()) break
                            try {
                                val tick = client.fetchOnce(sym, capturedAtMs = clock.now())
                                val seen = lastBrokerMs[sym] ?: 0L
                                if (tick.brokerTimeMs > seen) {
                                    lastBrokerMs[sym] = tick.brokerTimeMs
                                    onTick(
                                        Tick(
                                            symbol = symbolMap[sym] ?: sym,
                                            // Quote-driven instruments (FX, metals) report last = 0
                                            // — no last-traded price exists. Fall back to the
                                            // bid/ask mid so candles carry a real price, not zero.
                                            price =
                                                (if (tick.last.signum() > 0) tick.last else tick.mid)
                                                    .setScale(Money.SCALE, Money.ROUNDING),
                                            timestamp = clock.now(),
                                            bid = tick.bid.setScale(Money.SCALE, Money.ROUNDING),
                                            ask = tick.ask.setScale(Money.SCALE, Money.ROUNDING),
                                            volume = null,
                                        ),
                                    )
                                }
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@Thread
                            } catch (e: Exception) {
                                onError(e)
                            }
                        }
                        try {
                            Thread.sleep(pollIntervalMs)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                } finally {
                    onDisconnect()
                }
            }, "mt5-tick-feed-${baseUrl.hashCode()}").apply {
                isDaemon = true
                start()
            }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            thread?.interrupt()
            thread = null
        }
    }
}
