package com.qkt.marketdata.depth

import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * The live half of depth: every [pollMs] it asks [source] once per contract for the snapshots since the newest
 * it delivered, and emits each new one as a tick of every stream of that contract in [streams], at the instant
 * the venue stamped it. Each read asks up to a poll ahead, since a gateway serves no snapshot after its own now:
 * a book the read itself recorded, stamped by the venue while the request was on its way, is then served by that
 * read rather than the next. [first] holds what the start read returned per contract, delivered first. A failed read
 * is reported as a disconnect and retried at the next poll; it never ends the feed, because depth going quiet
 * must not stop the price ticks. Its work is one read and a few ticks per contract per poll, off the tick path.
 */
internal class BookDepthPoll(
    private val source: BookDepthSource,
    private val streams: Map<String, List<String>>,
    private val first: Map<String, List<BookDepth>>,
    private val clock: () -> Long,
    private val pollMs: Long,
) : LiveTickSource {
    private val log = LoggerFactory.getLogger(BookDepthPoll::class.java)
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
        for ((contract, symbols) in streams) {
            val snapshots = first[contract].orEmpty()
            snapshots.forEach { emit(symbols, it, onTick) }
            newest[contract] = snapshots.lastOrNull()?.timeMs ?: (clock() - pollMs)
        }
        thread =
            Thread({ loop(newest, onTick, onDisconnect, onReconnect) }, "qkt-book-depth").apply {
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
                for ((contract, after) in newest) {
                    val fresh = source.snapshots(contract, after + 1, clock() + pollMs).filter { it.timeMs > after }
                    fresh.forEach { emit(streams.getValue(contract), it, onTick) }
                    fresh.lastOrNull()?.let { newest[contract] = it.timeMs }
                }
                if (down) onReconnect().also { down = false }
            } catch (e: Exception) {
                // A poll must outlive any single failed read: report it as a disconnect and keep the feed alive.
                log.warn("book depth poll failed: {}", e.message)
                if (!down) onDisconnect().also { down = true }
            }
        }
    }

    private fun emit(
        symbols: List<String>,
        depth: BookDepth,
        onTick: (Tick) -> Unit,
    ) = symbols.forEach { onTick(BookDepthMarketSource.tick(it, depth)) }

    companion object {
        /** How often the live feed reads each contract's book: a recording gateway keeps one snapshot per read. */
        const val DEFAULT_POLL_MS: Long = 10_000L
    }
}
