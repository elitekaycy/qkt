package com.qkt.observe.insights

import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

/**
 * Egress to a qkt-insights collector.
 *
 * The contract that matters: **the publishing (engine) thread never blocks and never
 * touches the network.** [offer] only enqueues onto a bounded queue - O(1), no waits.
 * When the queue is full the oldest envelope is dropped and [dropped] increments.
 *
 * Without [journalDir] the sink is best-effort: failed batches are dropped after
 * [maxPostAttempts]. With [journalDir], the drain thread first spools serialized
 * envelopes to disk and advances the journal cursor only after qkt-insights accepts
 * the batch. Collector downtime therefore causes retry/replay, not batch loss.
 */
class InsightsSink(
    private val url: String,
    private val token: String,
    private val instanceId: String,
    private val batchSize: Int = 200,
    private val flushIntervalMs: Long = 250L,
    private val queueCapacity: Int = 10_000,
    private val failureBackoffMs: Long = 1_000L,
    private val maxPostAttempts: Int = 3,
    private val healthIntervalMs: Long = 30_000L,
    journalDir: Path? = null,
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build(),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(InsightsSink::class.java)
    private val queue = ArrayBlockingQueue<InsightsEnvelope>(queueCapacity)
    private val running = AtomicBoolean(true)
    private val journal: InsightsJournal? = journalDir?.let { InsightsJournal(it, instanceId) }

    /** Envelopes lost from queue shedding or, in best-effort mode, exhausted POST attempts. */
    val dropped = AtomicLong(0)

    /** POST attempts that failed (non-2xx or I/O error). */
    val failed = AtomicLong(0)

    /** Envelopes acknowledged by the collector. */
    val sent = AtomicLong(0)

    private val healthSeq = AtomicLong(0)

    private val drainThread =
        Thread({ drainLoop() }, "insights-sink").apply {
            isDaemon = true
            start()
        }

    /**
     * Enqueue an envelope. Never blocks: on a full queue the oldest entry is dropped
     * to make room. Safe to call from the engine thread.
     */
    fun offer(envelope: InsightsEnvelope) {
        if (!running.get()) return
        while (!queue.offer(envelope)) {
            if (queue.poll() != null) dropped.incrementAndGet()
        }
    }

    private fun drainLoop() {
        if (journal == null) {
            bestEffortDrainLoop()
        } else {
            durableDrainLoop(journal)
        }
    }

    private fun bestEffortDrainLoop() {
        val batch = ArrayList<InsightsEnvelope>(batchSize)
        var reportedDropped = 0L
        var lastHealthTs = System.currentTimeMillis()
        var lastHealthDropped = 0L
        var lastHealthFailed = 0L
        while (running.get() || queue.isNotEmpty()) {
            batch.clear()
            val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
            if (first == null) {
                val postedAt = maybePostHealthBestEffort(lastHealthTs, lastHealthDropped, lastHealthFailed)
                if (postedAt != lastHealthTs) {
                    lastHealthTs = postedAt
                    lastHealthDropped = dropped.get()
                    lastHealthFailed = failed.get()
                }
                continue
            }
            batch.add(first)
            val deadline = System.currentTimeMillis() + flushIntervalMs
            while (batch.size < batchSize) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                batch.add(queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break)
            }
            var delivered = false
            var attempts = 0
            while (!delivered) {
                attempts++
                delivered = post(batch)
                if (delivered) break
                failed.incrementAndGet()
                if (attempts >= maxPostAttempts || !running.get()) break
                Thread.sleep(failureBackoffMs)
            }
            if (delivered) {
                sent.addAndGet(batch.size.toLong())
            } else {
                dropped.addAndGet(batch.size.toLong())
                log.warn("[insights] dropped batch of {} after {} failed POST attempts", batch.size, attempts)
            }
            val shed = dropped.get()
            if (shed > reportedDropped) {
                log.warn("[insights] {} envelopes lost so far (queue shedding or exhausted retries)", shed)
                reportedDropped = shed
            }
            if (queue.isEmpty()) {
                val postedAt = maybePostHealthBestEffort(lastHealthTs, lastHealthDropped, lastHealthFailed)
                if (postedAt != lastHealthTs) {
                    lastHealthTs = postedAt
                    lastHealthDropped = dropped.get()
                    lastHealthFailed = failed.get()
                }
            }
        }
    }

    private fun durableDrainLoop(journal: InsightsJournal) {
        var lastHealthTs = System.currentTimeMillis()
        var lastHealthDropped = 0L
        var lastHealthFailed = 0L
        while (running.get() || queue.isNotEmpty()) {
            appendQueuedBatchToJournal(journal)
            val postedAt = maybeAppendHealth(journal, lastHealthTs, lastHealthDropped, lastHealthFailed)
            if (postedAt != lastHealthTs) {
                lastHealthTs = postedAt
                lastHealthDropped = dropped.get()
                lastHealthFailed = failed.get()
            }

            val pending = journal.pending(batchSize)
            if (pending.isEmpty()) {
                if (running.get()) {
                    val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                    if (first != null) appendBatchToJournal(journal, listOf(first))
                }
                continue
            }

            var delivered = false
            var attempts = 0
            while (!delivered) {
                attempts++
                delivered = postRaw(pending.map { it.eventJson })
                if (delivered) break
                failed.incrementAndGet()
                if (attempts >= maxPostAttempts || !running.get()) break
                Thread.sleep(failureBackoffMs)
            }
            if (delivered) {
                journal.ack(pending.last().journalSeq)
                sent.addAndGet(pending.size.toLong())
            } else {
                log.warn(
                    "[insights] kept {} journaled envelopes for replay after {} failed POST attempts",
                    pending.size,
                    attempts,
                )
                if (running.get()) Thread.sleep(failureBackoffMs)
            }
        }
    }

    private fun appendQueuedBatchToJournal(journal: InsightsJournal) {
        val batch = ArrayList<InsightsEnvelope>(batchSize)
        while (batch.size < batchSize) {
            batch.add(queue.poll() ?: break)
        }
        appendBatchToJournal(journal, batch)
    }

    private fun appendBatchToJournal(
        journal: InsightsJournal,
        batch: List<InsightsEnvelope>,
    ) {
        if (batch.isEmpty()) return
        journal.append(batch.map { it.toJson(instanceId) })
    }

    private fun maybePostHealthBestEffort(
        lastHealthTs: Long,
        lastHealthDropped: Long,
        lastHealthFailed: Long,
    ): Long {
        if (healthIntervalMs <= 0) return lastHealthTs
        if (!running.get()) return lastHealthTs
        val now = System.currentTimeMillis()
        val dueByTime = now - lastHealthTs >= healthIntervalMs
        val dueByLoss = dropped.get() != lastHealthDropped || failed.get() != lastHealthFailed
        if (!dueByTime && !dueByLoss) return lastHealthTs

        val delivered = post(listOf(healthEnvelope(now)))
        if (delivered) {
            sent.incrementAndGet()
        } else {
            failed.incrementAndGet()
            dropped.incrementAndGet()
            log.warn("[insights] dropped health snapshot after failed POST")
        }
        return now
    }

    private fun maybeAppendHealth(
        journal: InsightsJournal,
        lastHealthTs: Long,
        lastHealthDropped: Long,
        lastHealthFailed: Long,
    ): Long {
        if (healthIntervalMs <= 0) return lastHealthTs
        if (!running.get()) return lastHealthTs
        val now = System.currentTimeMillis()
        val dueByTime = now - lastHealthTs >= healthIntervalMs
        val dueByLoss = dropped.get() != lastHealthDropped || failed.get() != lastHealthFailed
        if (!dueByTime && !dueByLoss) return lastHealthTs

        journal.append(listOf(healthEnvelope(now).toJson(instanceId)))
        return now
    }

    private fun healthEnvelope(now: Long): InsightsEnvelope =
        InsightsEnvelope(
            id = "insights-health-$now-${healthSeq.incrementAndGet()}",
            seq = 0,
            ts = now,
            strategyId = null,
            type = "insights.health",
            payload =
                mapOf(
                    "sent" to sent.get(),
                    "failed" to failed.get(),
                    "dropped" to dropped.get(),
                    "queued" to queue.size,
                    "queueCapacity" to queueCapacity,
                    "batchSize" to batchSize,
                    "flushIntervalMs" to flushIntervalMs,
                    "maxPostAttempts" to maxPostAttempts,
                    "journalEnabled" to (journal != null),
                    "journalPending" to (journal?.pendingCount() ?: 0L),
                ),
        )

    private fun post(batch: List<InsightsEnvelope>): Boolean = postRaw(batch.map { it.toJson(instanceId) })

    private fun postRaw(eventsJson: List<String>): Boolean {
        val body =
            eventsJson.joinToString(
                separator = ",",
                prefix = "{\"instanceId\":${jsonString(instanceId)},\"events\":[",
                postfix = "]}",
            )
        val req =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON))
                .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.warn("[insights] collector returned {} for batch of {}", resp.code, eventsJson.size)
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            log.warn("[insights] POST failed for batch of {}: {}", eventsJson.size, e.message)
            false
        }
    }

    /** Stops the drain thread after a final flush attempt of whatever is queued. */
    override fun close() {
        running.set(false)
        drainThread.join(2_000)
        if (drainThread.isAlive) {
            http.dispatcher.cancelAll()
            drainThread.join(2_000)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
