package com.qkt.notify

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** In-memory observability surface for [TelegramNotifier]. Read-only from the outside. */
interface NotifierMetrics {
    val sent: Long
    val dropped: Long
    val failed: Long
    val rateLimitHits: Long
    val degradedMode: Boolean
}

/** Mutable [NotifierMetrics] used by the notifier internals. */
class AtomicNotifierMetrics : NotifierMetrics {
    private val sentRef = AtomicLong()
    private val droppedRef = AtomicLong()
    private val failedRef = AtomicLong()
    private val rateLimitRef = AtomicLong()
    private val degradedRef = AtomicBoolean(false)

    override val sent: Long get() = sentRef.get()
    override val dropped: Long get() = droppedRef.get()
    override val failed: Long get() = failedRef.get()
    override val rateLimitHits: Long get() = rateLimitRef.get()
    override val degradedMode: Boolean get() = degradedRef.get()

    fun recordSent() {
        sentRef.incrementAndGet()
    }

    fun recordDropped() {
        droppedRef.incrementAndGet()
    }

    fun recordFailed() {
        failedRef.incrementAndGet()
    }

    fun recordRateLimit() {
        rateLimitRef.incrementAndGet()
    }

    fun flipDegraded() {
        degradedRef.set(true)
    }
}
