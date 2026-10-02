package com.qkt.marketdata.source

import com.qkt.common.Clock
import com.qkt.derivatives.options.chain.ChainView
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Chain analytics [streams] live: every [pollMs] each stream's latest snapshot at the [clock] is read
 * through [view] (which re-reads a day file a recorder appended to), and a snapshot newer than the last
 * one seen gives one tick at its own instant, where the metric is defined. Snapshots that existed at
 * [start] are history (warmup reads them as bars), so only later ones tick. A failed read goes to
 * `onError` and the next poll tries again.
 */
internal class ChainAnalyticsLiveSource(
    private val streams: Map<String, DeclaredChainStream>,
    private val view: ChainView,
    private val clock: Clock,
    private val pollMs: Long,
) : LiveTickSource {
    private val seen = HashMap<String, Long>()
    private var poller: ScheduledExecutorService? = null

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        streams.forEach { (symbol, declared) -> seen[symbol] = latestAt(declared) ?: Long.MIN_VALUE }
        poller =
            Executors
                .newSingleThreadScheduledExecutor { r -> Thread(r, "chain-analytics-live").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay({ poll(onTick, onError) }, pollMs, pollMs, TimeUnit.MILLISECONDS) }
    }

    override fun stop() {
        poller?.shutdownNow()
    }

    private fun poll(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        for ((symbol, declared) in streams) {
            runCatching {
                val snapshot = view.latest(declared.root.root, clock.now()) ?: return@runCatching
                if (snapshot.atMs <= seen.getValue(symbol)) return@runCatching
                seen[symbol] = snapshot.atMs
                declared.value(snapshot)?.let { onTick(Tick(symbol, it, snapshot.atMs)) }
            }.onFailure(onError)
        }
    }

    private fun latestAt(declared: DeclaredChainStream): Long? = view.latest(declared.root.root, clock.now())?.atMs
}
