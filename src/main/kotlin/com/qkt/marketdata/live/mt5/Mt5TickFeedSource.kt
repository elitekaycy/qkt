package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
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
 * When [symbolCalendars] is supplied, the poller skips a round only when every configured
 * calendar is out of session (sleeps [outOfSessionSleepMs] instead). A multi-asset broker keeps
 * ticking while any asset class is open — a 24/7 crypto calendar prevents the weekend FX skip.
 * Fetching an individually-closed symbol within an open round is harmless (its stale tick dedupes
 * by broker time and never re-emits).
 */
class Mt5TickFeedSource(
    private val baseUrl: String,
    private val symbolMap: Map<String, String>,
    private val pollIntervalMs: Long = 50L,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
    private val symbolCalendars: SymbolCalendars? = null,
    private val outOfSessionSleepMs: Long = 60_000L,
) : LiveTickSource {
    private val log = org.slf4j.LoggerFactory.getLogger(Mt5TickFeedSource::class.java)
    private val symbols: List<String> = symbolMap.keys.toList()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        check(running.compareAndSet(false, true)) { "Mt5TickFeedSource already started" }
        val client = Mt5TickClient(baseUrl, http)
        val lastBrokerMs = mutableMapOf<String, Long>()
        // Repeated poll failure must surface as a DISCONNECT, not an endless onError
        // stream: only onDisconnect starts the feed's reconnect budget, so without it a
        // hung gateway means silent stale prices forever. Polling self-heals — a later
        // successful round fires onReconnect and clears the budget.
        var consecutiveFailedRounds = 0
        var disconnected = false
        thread =
            Thread({
                try {
                    while (running.get()) {
                        if (symbolCalendars != null &&
                            symbols.isNotEmpty() &&
                            !symbolCalendars.anyCalendarInSession(Instant.ofEpochMilli(clock.now()))
                        ) {
                            try {
                                Thread.sleep(outOfSessionSleepMs)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@Thread
                            }
                            continue
                        }
                        var roundHadSuccess = false
                        for (sym in symbols) {
                            if (!running.get()) break
                            try {
                                val tick = client.fetchOnce(sym, capturedAtMs = clock.now())
                                roundHadSuccess = true
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
                                            // Stamp the broker's authoritative tick time, not local wall-clock,
                                            // so candle boundaries match MT5 replay and the Bybit convention.
                                            timestamp = tick.brokerTimeMs,
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
                        if (symbols.isNotEmpty()) {
                            if (roundHadSuccess) {
                                consecutiveFailedRounds = 0
                                if (disconnected) {
                                    disconnected = false
                                    log.info("Mt5TickFeedSource {} gateway answering again", baseUrl)
                                    onReconnect()
                                }
                            } else {
                                consecutiveFailedRounds++
                                if (!disconnected && consecutiveFailedRounds >= DISCONNECT_AFTER_FAILED_ROUNDS) {
                                    disconnected = true
                                    log.error(
                                        "Mt5TickFeedSource {} treated as DISCONNECTED after {} fully-failed rounds",
                                        baseUrl,
                                        consecutiveFailedRounds,
                                    )
                                    onDisconnect()
                                }
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

    private companion object {
        /** Fully-failed poll rounds before the source reports itself disconnected. */
        const val DISCONNECT_AFTER_FAILED_ROUNDS: Int = 5
    }
}
