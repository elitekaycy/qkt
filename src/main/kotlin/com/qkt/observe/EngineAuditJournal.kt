package com.qkt.observe

import com.qkt.common.Clock
import com.qkt.events.Event
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Durable all-event engine audit journal. Unlike [OrderJournal], this records every
 * stamped bus event as JSONL so incident response and qkt-insights repair workflows
 * can reconstruct the engine timeline by sequence id.
 */
class EngineAuditJournal(
    rootDir: Path,
    private val owner: String,
    private val clock: Clock,
    queueCapacity: Int = 50_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(EngineAuditJournal::class.java)

    private data class Line(
        val day: LocalDate,
        val bytes: ByteArray,
    )

    private val queue = ArrayBlockingQueue<Event>(queueCapacity)
    private val running = AtomicBoolean(true)
    private val inFlight = AtomicLong(0L)
    private val droppedByDay = ConcurrentHashMap<LocalDate, AtomicLong>()
    private val persistedDroppedByDay = HashMap<LocalDate, Long>()

    /** Events rejected because the bounded audit queue was full. */
    val dropped: AtomicLong = AtomicLong(0L)

    private val files = AuditJournalFiles(rootDir, owner)

    private val worker =
        Thread({
            while (running.get() || queue.isNotEmpty()) {
                val event =
                    try {
                        queue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        if (!running.get()) null else continue
                    }
                if (event != null) {
                    inFlight.incrementAndGet()
                    try {
                        writeEvent(event)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
                persistDropMarkers()
            }
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

    /** Enqueue a stamped engine event for asynchronous serialization and JSONL persistence. */
    fun append(event: Event) {
        if (!running.get()) return
        if (!queue.offer(event)) {
            val eventDay = eventDay(event)
            val n = markDropped(eventDay)
            if (n == 1L || n % 1_000L == 0L) {
                log.error("engine audit journal queue full for {}; dropped {} event(s)", owner, n)
            }
        }
    }

    private fun writeEvent(event: Event) {
        val bytes = encodeAuditLine(event).toByteArray(StandardCharsets.UTF_8)
        writeLine(Line(eventDay(event), bytes))
    }

    private fun eventDay(event: Event): LocalDate =
        Instant
            .ofEpochMilli(if (event.timestamp > 0L) event.timestamp else clock.now())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.join(2_000)
        if (worker.isAlive) {
            worker.interrupt()
            log.error("engine audit journal worker did not drain within 2000ms for {}; remaining={}", owner, queue.size)
        }
    }

    private fun writeLine(line: Line) {
        synchronized(this) {
            try {
                files.append(line.day, line.bytes)
            } catch (e: Exception) {
                markDropped(line.day)
                log.error("engine audit journal append FAILED for {}: {}", owner, e.message)
                files.close()
            }
        }
    }

    private fun markDropped(eventDay: LocalDate): Long {
        droppedByDay.computeIfAbsent(eventDay) { AtomicLong(0L) }.incrementAndGet()
        return dropped.incrementAndGet()
    }

    private fun persistDropMarkers() {
        for ((eventDay, countRef) in droppedByDay) {
            val count = countRef.get()
            if (persistedDroppedByDay[eventDay] == count) continue
            try {
                files.writeDropMarker(eventDay, count)
                persistedDroppedByDay[eventDay] = count
            } catch (error: Exception) {
                log.error("engine audit journal could not persist drop marker for {}: {}", owner, error.message)
            }
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
}
