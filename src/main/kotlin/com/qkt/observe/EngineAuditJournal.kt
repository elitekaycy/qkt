package com.qkt.observe

import com.qkt.common.Clock
import com.qkt.events.Event
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Durable all-event engine audit journal. Unlike [OrderJournal], this records every
 * stamped bus event as JSONL so incident response and qkt-insights repair workflows
 * can reconstruct the engine timeline by sequence id.
 *
 * Events are written in bus order by one worker thread, a batch per durable write. Decision events
 * (everything but ticks and candles, [isAuditMarketData]) are never shed: they always join the queue.
 * Market data may wait in the queue up to [marketDataCapacity] records; past that a tick or candle is
 * dropped, counted ([health]), logged at ERROR and recorded in the day's `.dropped` marker, so a
 * quote flood can delay the journal but never cost it a fill.
 */
class EngineAuditJournal(
    rootDir: Path,
    private val owner: String,
    private val clock: Clock,
    private val marketDataCapacity: Int = 50_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(EngineAuditJournal::class.java)

    private val queue = LinkedBlockingQueue<Event>()
    private val queuedMarketData = AtomicInteger(0)
    private val running = AtomicBoolean(true)
    private val inFlight = AtomicLong(0L)
    private val written = AtomicLong(0L)
    private val droppedMarketData = AtomicLong(0L)
    private val lostDecisionEvents = AtomicLong(0L)
    private val omittedChainQuotes = AtomicLong(0L)
    private val drops = AuditDropMarkers()

    private val files = AuditJournalFiles(rootDir, owner)
    private val batch = AuditBatchWriter(files) { event -> eventDay(event) }

    private val worker =
        Thread({
            MDC.put("strategy", owner)
            val pending = ArrayList<Event>(BATCH_SIZE)
            while (running.get() || queue.isNotEmpty()) {
                val first =
                    try {
                        queue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        if (!running.get()) null else continue
                    }
                if (first != null) {
                    pending.add(first)
                    queue.drainTo(pending, BATCH_SIZE - 1)
                    inFlight.addAndGet(pending.size.toLong())
                    try {
                        writeBatch(pending)
                    } finally {
                        inFlight.addAndGet(-pending.size.toLong())
                        pending.clear()
                    }
                }
                drops.persist(files, log, owner)
            }
            // The last pass may have read the count before the final drops: what stands on disk is the whole count.
            drops.persist(files, log, owner)
            files.close()
        }, "qkt-engine-audit-journal-${owner.ifBlank { "session" }}").apply {
            isDaemon = true
            start()
        }

    /**
     * The highest sequence this owner already journaled, or null for a first session. A session
     * restarted over the same state resumes its bus sequence after it, so a capture spanning the
     * restart never repeats a sequence. Call before the session publishes anything.
     */
    fun lastSequence(): Long? = files.lastSequence()

    /**
     * Enqueue a stamped engine event for asynchronous serialization and JSONL persistence. A decision
     * event is always queued; a tick or candle is dropped (and counted) while [marketDataCapacity]
     * market-data records already wait. O(1), allocation-free apart from the queue node.
     */
    fun append(event: Event) {
        if (!running.get()) return
        if (isAuditMarketData(event) && queuedMarketData.incrementAndGet() > marketDataCapacity) {
            queuedMarketData.decrementAndGet()
            val n = droppedMarketData.incrementAndGet()
            drops.mark(eventDay(event), 1L)
            if (n == 1L || n % DROP_LOG_EVERY == 0L) {
                log.error("engine audit journal queue full for {}; dropped {} market-data record(s)", owner, n)
            }
            return
        }
        queue.offer(event)
    }

    /** Count a quote the session chose not to journal (an option-chain contract it never traded). */
    fun omitted() {
        omittedChainQuotes.incrementAndGet()
    }

    /** What this journal has written, holds and lost so far. */
    fun health(): AuditJournalHealth =
        AuditJournalHealth(
            enabled = true,
            written = written.get(),
            queued = queue.size,
            droppedMarketData = droppedMarketData.get(),
            lostDecisionEvents = lostDecisionEvents.get(),
            omittedChainQuotes = omittedChainQuotes.get(),
        )

    private fun writeBatch(events: List<Event>) {
        var marketData = 0
        for (event in events) if (isAuditMarketData(event)) marketData++
        queuedMarketData.addAndGet(-marketData)
        val lost = synchronized(this) { batch.write(events) }
        written.addAndGet((events.size - lost.total).toLong())
        if (lost.total == 0) return
        droppedMarketData.addAndGet(lost.marketData.toLong())
        lostDecisionEvents.addAndGet(lost.decisions.toLong())
        for ((day, n) in lost.byDay) drops.mark(day, n)
        log.error(
            "engine audit journal append FAILED for {}: lost {} record(s), {} of them decision events: {}",
            owner,
            lost.total,
            lost.decisions,
            lost.error,
        )
    }

    private fun eventDay(event: Event): LocalDate =
        LocalDate.ofEpochDay(Math.floorDiv(if (event.timestamp > 0L) event.timestamp else clock.now(), MS_PER_DAY))

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.join(2_000)
        if (worker.isAlive) {
            worker.interrupt()
            log.error("engine audit journal worker did not drain within 2000ms for {}; remaining={}", owner, queue.size)
        }
    }

    internal fun pendingForTesting(): Int = queue.size

    internal fun awaitDrainedForTesting(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && inFlight.get() == 0L) return true
            Thread.sleep(10)
        }
        return queue.isEmpty() && inFlight.get() == 0L
    }

    private companion object {
        const val BATCH_SIZE = 512
        const val DROP_LOG_EVERY = 1_000L
        const val MS_PER_DAY = 86_400_000L
    }
}
