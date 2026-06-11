package com.qkt.observe.insights

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
 * Best-effort, lossy egress to a qkt-insights collector.
 *
 * The contract that matters: **the publishing (engine) thread never blocks and never
 * touches the network.** [offer] only enqueues onto a bounded queue — O(1), no waits.
 * When the queue is full the oldest envelope is dropped and [dropped] increments,
 * exactly like the engine's own tick-queue shedding. A single daemon thread drains
 * the queue, batches up to [batchSize] or [flushIntervalMs] (whichever first),
 * serializes to JSON, and POSTs. If the collector is down only the drain thread
 * waits; trading is unaffected. Same philosophy as [com.qkt.observe.OrderJournal]:
 * an observability control, never a trading dependency.
 */
class InsightsSink(
    private val url: String,
    private val token: String,
    private val instanceId: String,
    private val batchSize: Int = 200,
    private val flushIntervalMs: Long = 250L,
    queueCapacity: Int = 10_000,
    private val failureBackoffMs: Long = 1_000L,
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

    /** Envelopes shed because the queue was full (collector slow or down). */
    val dropped = AtomicLong(0)

    /** POST attempts that failed (non-2xx or I/O error). */
    val failed = AtomicLong(0)

    /** Envelopes acknowledged by the collector. */
    val sent = AtomicLong(0)

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
        val batch = ArrayList<InsightsEnvelope>(batchSize)
        while (running.get() || queue.isNotEmpty()) {
            batch.clear()
            val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS) ?: continue
            batch.add(first)
            val deadline = System.currentTimeMillis() + flushIntervalMs
            while (batch.size < batchSize) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                batch.add(queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break)
            }
            if (!post(batch)) {
                failed.incrementAndGet()
                Thread.sleep(failureBackoffMs)
            } else {
                sent.addAndGet(batch.size.toLong())
            }
        }
    }

    private fun post(batch: List<InsightsEnvelope>): Boolean {
        val body =
            batch.joinToString(
                separator = ",",
                prefix = "{\"instanceId\":\"$instanceId\",\"events\":[",
                postfix = "]}",
            ) { it.toJson(instanceId) }
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
                    log.warn("[insights] collector returned {} for batch of {}", resp.code, batch.size)
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            log.warn("[insights] POST failed for batch of {}: {}", batch.size, e.message)
            false
        }
    }

    /** Stops the drain thread after a final flush attempt of whatever is queued. */
    override fun close() {
        running.set(false)
        drainThread.join(2_000)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
