package com.qkt.persistence

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Writes [delegate]'s excursion marks on a writer thread of their own, the latest per leg book, and every other save
 * as [delegate] does. Marks are the one state written from ticks: a new extreme saves them up to once a second per
 * open leg, and on a synchronous persistor each save is a flushed file write on the engine thread, which a loaded
 * disk turns into a hold of a second or more while ticks, fills and reads queue behind it (#1609). Marks are a
 * convenience a restart extends again from the downtime bars, so they need no flush before the engine moves on;
 * order intent, positions, P&L and risk state keep the delegate's synchronous write.
 *
 * Legs take turns, so one leg saved every second never holds another's mark back. A pending mark is answered by
 * [loadExcursion] until it is on disk; [clearStrategy] drops a strategy's pending marks
 * before its files go, so none lands after; [close] writes what is pending, then closes [delegate].
 */
class ExcursionWriteBehind(
    private val delegate: StatePersistor,
) : StatePersistor by delegate {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val pending = LinkedHashMap<Pair<String, String>, PersistedExcursion>()
    private var writing: Pair<String, String>? = null
    private var written: PersistedExcursion? = null
    private var closed = false
    private val writer = Thread(::drain, "qkt-excursion-writer").apply { isDaemon = true }

    init {
        writer.start()
    }

    override fun saveExcursion(
        strategyId: String,
        symbol: String,
        excursion: PersistedExcursion,
    ) {
        lock.withLock {
            if (!closed) {
                pending[strategyId to symbol] = excursion
                changed.signalAll()
                return
            }
        }
        delegate.saveExcursion(strategyId, symbol, excursion)
    }

    override fun loadExcursion(
        strategyId: String,
        symbol: String,
    ): PersistedExcursion? =
        lock.withLock {
            val key = strategyId to symbol
            pending[key] ?: written.takeIf { writing == key }
        } ?: delegate.loadExcursion(strategyId, symbol)

    override fun clearStrategy(strategyId: String) {
        lock.withLock {
            pending.keys.removeIf { it.first == strategyId }
            while (writing?.first == strategyId) changed.await()
            delegate.clearStrategy(strategyId)
        }
    }

    /** Waits until no mark is pending or being written, up to [timeoutMs]; true when none is. */
    fun awaitIdle(timeoutMs: Long = 5_000L): Boolean {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (pending.isNotEmpty() || writing != null) {
                if (left <= 0) return false
                left = changed.awaitNanos(left)
            }
        }
        return true
    }

    override fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        writer.join(CLOSE_WAIT_MS)
        delegate.close()
    }

    private fun drain() {
        while (true) {
            val (key, excursion) =
                lock.withLock {
                    while (pending.isEmpty() && !closed) changed.await()
                    val key = pending.keys.firstOrNull() ?: return
                    // Taken off the map, so a leg saved again goes to the back and every leg gets its turn.
                    val excursion = pending.getValue(key).also { pending.remove(key) }
                    writing = key
                    written = excursion
                    key to excursion
                }
            runCatching { delegate.saveExcursion(key.first, key.second, excursion) }
            lock.withLock {
                writing = null
                written = null
                changed.signalAll()
            }
        }
    }

    private companion object {
        const val CLOSE_WAIT_MS = 5_000L
    }
}
