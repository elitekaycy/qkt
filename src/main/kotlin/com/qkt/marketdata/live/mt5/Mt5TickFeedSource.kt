package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

/**
 * Polling [LiveTickSource] over the `mt5-gateway` HTTP `/symbol_info_tick/{symbol}` endpoint.
 *
 * Each round fetches every symbol concurrently (one pool thread per symbol), so per-symbol
 * staleness is ~one gateway round-trip + [pollIntervalMs] regardless of how many symbols are
 * configured — serial fetches would make it K round-trips for K symbols (#653). Results are
 * deduped per-symbol by `time_msc` and emitted in configured-symbol order once the round
 * completes; one symbol failing surfaces via `onError` without killing the round. Sleeps
 * [pollIntervalMs] between rounds. One polling daemon thread per source instance.
 *
 * When [symbolCalendars] is supplied, the poller skips a round only when every configured
 * calendar is out of session (sleeps [outOfSessionSleepMs] instead). A multi-asset broker keeps
 * ticking while any asset class is open — a 24/7 crypto calendar prevents the weekend FX skip.
 * Fetching an individually-closed symbol within an open round is harmless (its stale tick dedupes
 * by broker time and never re-emits).
 *
 * If the gateway keeps answering but all subscribed symbols repeat the same broker timestamp for
 * [prolongedStaleAfterMs], the source switches from the normal cadence to [staleProbeSleepMs].
 * A newer broker timestamp immediately restores normal polling. This reduces closed-market and
 * weekend load without weakening the downstream stale-data order gate.
 */
class Mt5TickFeedSource(
    private val baseUrl: String,
    private val symbolMap: Map<String, String>,
    private val pollIntervalMs: Long = 50L,
    private val http: OkHttpClient = OkHttpClient(),
    private val serverTimeZone: MT5ServerTimeZone = MT5ServerTimeZone.UTC,
    private val clock: Clock = SystemClock(),
    private val symbolCalendars: SymbolCalendars? = null,
    private val outOfSessionSleepMs: Long = 60_000L,
    private val apiKey: String? = null,
    private val prolongedStaleAfterMs: Long = DEFAULT_PROLONGED_STALE_AFTER_MS,
    private val staleProbeSleepMs: Long = DEFAULT_STALE_PROBE_SLEEP_MS,
) : LiveTickSource {
    private val log = org.slf4j.LoggerFactory.getLogger(Mt5TickFeedSource::class.java)
    private val symbols: List<String> = symbolMap.keys.toList()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    init {
        require(pollIntervalMs > 0L) { "MT5 tick poll interval must be positive" }
        require(outOfSessionSleepMs > 0L) { "MT5 out-of-session sleep must be positive" }
        require(prolongedStaleAfterMs > 0L) { "MT5 prolonged-stale threshold must be positive" }
        require(staleProbeSleepMs > 0L) { "MT5 stale probe sleep must be positive" }
    }

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        check(running.compareAndSet(false, true)) { "Mt5TickFeedSource already started" }
        val client = Mt5TickClient(baseUrl, http, serverTimeZone, apiKey)
        // One thread per symbol: a round's K fetches run in parallel against the gateway,
        // and the polling thread emits in configured order once all complete.
        val fetchPool =
            Executors.newFixedThreadPool(symbols.size.coerceAtLeast(1)) { r ->
                Thread(r, "mt5-tick-fetch-${baseUrl.hashCode()}").apply { isDaemon = true }
            }
        val lastBrokerMs = mutableMapOf<String, Long>()
        // Repeated poll failure must surface as a DISCONNECT, not an endless onError
        // stream: only onDisconnect starts the feed's reconnect budget, so without it a
        // hung gateway means silent stale prices forever. Polling self-heals — a later
        // successful round fires onReconnect and clears the budget.
        var consecutiveFailedRounds = 0
        var disconnected = false
        val staleProbe =
            ProlongedStaleProbeController(
                normalPollIntervalMs = pollIntervalMs,
                staleAfterMs = prolongedStaleAfterMs,
                probePollIntervalMs = staleProbeSleepMs,
            )
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
                        var roundHadFreshTick = false
                        val fetches: List<Future<Mt5TickClient.Mt5Tick>> =
                            symbols.map { sym ->
                                fetchPool.submit(Callable { client.fetchOnce(sym, capturedAtMs = clock.now()) })
                            }
                        for ((i, sym) in symbols.withIndex()) {
                            try {
                                val tick = fetches[i].get()
                                roundHadSuccess = true
                                val seen = lastBrokerMs[sym] ?: 0L
                                if (tick.brokerTimeMs > seen) {
                                    roundHadFreshTick = true
                                    if (!lastBrokerMs.containsKey(sym)) {
                                        val skewMs = tick.brokerTimeMs - clock.now()
                                        log.info(
                                            "MT5 tick clock check symbol={} brokerUtc={} localUtc={} skewMs={}",
                                            sym,
                                            Instant.ofEpochMilli(tick.brokerTimeMs),
                                            Instant.ofEpochMilli(clock.now()),
                                            skewMs,
                                        )
                                    }
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
                            } catch (e: ExecutionException) {
                                onError(e.cause ?: e)
                            } catch (e: Exception) {
                                onError(e)
                            }
                        }
                        val sleepMs = staleProbe.sleepAfterRound(clock.now(), roundHadFreshTick)
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
                            Thread.sleep(sleepMs)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                } finally {
                    fetchPool.shutdownNow()
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
        const val DEFAULT_PROLONGED_STALE_AFTER_MS: Long = 5 * 60_000L
        const val DEFAULT_STALE_PROBE_SLEEP_MS: Long = 60_000L
    }
}

internal class ProlongedStaleProbeController(
    private val normalPollIntervalMs: Long,
    private val staleAfterMs: Long,
    private val probePollIntervalMs: Long,
) {
    private var lastFreshAtMs: Long? = null

    init {
        require(normalPollIntervalMs > 0L) { "normal poll interval must be positive" }
        require(staleAfterMs > 0L) { "stale-after interval must be positive" }
        require(probePollIntervalMs > 0L) { "probe poll interval must be positive" }
    }

    fun sleepAfterRound(
        nowMs: Long,
        hadFreshTick: Boolean,
    ): Long {
        if (hadFreshTick) {
            lastFreshAtMs = nowMs
            return normalPollIntervalMs
        }
        val lastFresh = lastFreshAtMs ?: return normalPollIntervalMs
        return if (nowMs - lastFresh >= staleAfterMs) probePollIntervalMs else normalPollIntervalMs
    }
}
