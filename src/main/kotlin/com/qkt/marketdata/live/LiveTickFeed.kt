package com.qkt.marketdata.live

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

class LiveTickFeed(
    private val source: LiveTickSource,
    queueCapacity: Int = 10_000,
    private val pollIntervalMs: Long = 200L,
) : TickFeed {
    private val log = LoggerFactory.getLogger(LiveTickFeed::class.java)

    private val queue: LinkedBlockingQueue<Tick> = LinkedBlockingQueue(queueCapacity)
    private val closed: AtomicBoolean = AtomicBoolean(false)

    val droppedTicks: AtomicLong = AtomicLong(0)

    init {
        source.start(
            onTick = { tick ->
                if (!queue.offer(tick)) {
                    queue.poll()
                    droppedTicks.incrementAndGet()
                    queue.offer(tick)
                }
            },
            onError = { t -> log.warn("LiveTickFeed source error: ${t.message}", t) },
            onDisconnect = { log.warn("LiveTickFeed source disconnected") },
        )
    }

    override fun next(): Tick? {
        while (!closed.get()) {
            val t = queue.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
            if (t != null) return t
        }
        return queue.poll()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { source.stop() }
        }
    }
}
