package com.qkt.marketdata.live.mt5

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

/**
 * Polling [LiveTickSource] over the `mt5-gateway` HTTP `/symbol_info_tick/{symbol}` endpoint.
 *
 * Round-robins across [symbols] each iteration, dedupes per-symbol by `time_msc`, sleeps
 * [pollIntervalMs] between rounds. One daemon thread per source instance.
 */
class Mt5TickFeedSource(
    private val baseUrl: String,
    private val symbols: List<String>,
    private val pollIntervalMs: Long = 50L,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
) : LiveTickSource {
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
                        for (sym in symbols) {
                            if (!running.get()) break
                            try {
                                val tick = client.fetchOnce(sym, capturedAtMs = clock.now())
                                val seen = lastBrokerMs[sym] ?: 0L
                                if (tick.brokerTimeMs > seen) {
                                    lastBrokerMs[sym] = tick.brokerTimeMs
                                    onTick(
                                        Tick(
                                            symbol = sym,
                                            price = tick.last.setScale(Money.SCALE, Money.ROUNDING),
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
