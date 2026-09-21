package com.qkt.app

import com.qkt.app.LiveSession.Companion.ENGINE_QUERY_TIMEOUT_MS
import com.qkt.app.LiveSession.Companion.QUEUE_POLL_MS
import java.util.concurrent.TimeUnit

/**
 * Reads engine-thread state from any other thread without racing the engine: the read is queued
 * as an [Inbound.Query] and answered by the engine loop between two messages, e.g. an HTTP
 * status request for positions waits for the loop, then returns a consistent list. HTTP/operator
 * snapshot requests fail loud instead of waiting forever on a stalled engine.
 */
internal class EngineSnapshot(
    private val thread: Thread,
    mailbox: EngineMailbox,
) {
    private val control = mailbox.control
    private val terminated = mailbox.terminated

    /** Run [read] on the engine thread (inline when already on it, or once it has terminated). */
    fun <T> engineSnapshot(read: () -> T): T {
        if (Thread.currentThread() === thread || !thread.isAlive) return read()
        val result = java.util.concurrent.CompletableFuture<T>()
        control.put(
            Inbound.Query {
                runCatching(read)
                    .onSuccess(result::complete)
                    .onFailure(result::completeExceptionally)
            },
        )
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENGINE_QUERY_TIMEOUT_MS)
        while (thread.isAlive) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) {
                throw IllegalStateException("live engine did not produce a consistent snapshot within the timeout")
            }
            try {
                return result.get(
                    minOf(remainingNs, TimeUnit.MILLISECONDS.toNanos(QUEUE_POLL_MS)),
                    TimeUnit.NANOSECONDS,
                )
            } catch (_: java.util.concurrent.TimeoutException) {
                // Recheck thread liveness so a finite feed cannot strand the query on shutdown.
            }
        }
        return if (result.isDone) {
            result.get()
        } else {
            check(terminated.await(0L, TimeUnit.MILLISECONDS)) {
                "live engine stopped without publishing its termination barrier"
            }
            // CountDownLatch establishes a happens-before edge from the engine's final
            // mutation to this read. The engine is terminated, so no concurrent writer exists.
            read()
        }
    }
}
