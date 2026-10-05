package com.qkt.marketdata.openinterest

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * The live half of open interest: every [pollMs] it asks [source] for each stream's figures since the newest
 * it delivered and emits each new one as a tick at the instant it became known. [first] holds what the start
 * read returned per stream, delivered first. A failed read is reported as a disconnect and retried at the next
 * poll; it never ends the feed, because open interest going quiet must not stop the price ticks.
 */
internal class OpenInterestPoll(
    private val source: OpenInterestSource,
    private val first: Map<String, List<OpenInterest>>,
    private val clock: () -> Long,
    private val pollMs: Long,
) : LiveTickSource {
    private val log = LoggerFactory.getLogger(OpenInterestPoll::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        val newest = HashMap<String, Long>()
        for ((symbol, figures) in first) {
            figures.forEach { onTick(tick(symbol, it)) }
            newest[symbol] = figures.lastOrNull()?.timeMs ?: (clock() - pollMs)
        }
        thread =
            Thread({ loop(newest, onTick, onDisconnect, onReconnect) }, "qkt-open-interest").apply {
                isDaemon = true
                start()
            }
    }

    override fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop(
        newest: MutableMap<String, Long>,
        onTick: (Tick) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        var down = false
        while (running.get()) {
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                return
            }
            try {
                for ((symbol, after) in newest) {
                    val contract = OpenInterestSymbol.contract(symbol) ?: continue
                    val fresh = source.figures(contract, after + 1, clock()).filter { it.timeMs > after }
                    fresh.forEach { onTick(tick(symbol, it)) }
                    fresh.lastOrNull()?.let { newest[symbol] = it.timeMs }
                }
                if (down) onReconnect().also { down = false }
            } catch (e: Exception) {
                // A poll must outlive any single failed read: report it as a disconnect and keep the feed alive.
                log.warn("open interest poll failed: {}", e.message)
                if (!down) onDisconnect().also { down = true }
            }
        }
    }

    private fun tick(
        symbol: String,
        figure: OpenInterest,
    ) = Tick(symbol, figure.openInterest.setScale(Money.SCALE, Money.ROUNDING), figure.timeMs)

    companion object {
        /** How often the live feed asks for new figures: Deribit's gateway records one per read. */
        const val DEFAULT_POLL_MS: Long = 60_000L
    }
}
