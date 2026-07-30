package com.qkt.observe

import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.events.Event
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TradeEvent
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ArrayBlockingQueue
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
    private val rootDir: Path,
    private val owner: String,
    private val clock: Clock,
    queueCapacity: Int = 10_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(EngineAuditJournal::class.java)

    private data class Line(
        val day: LocalDate,
        val bytes: ByteArray,
    )

    private val queue = ArrayBlockingQueue<Line>(queueCapacity)
    private val running = AtomicBoolean(true)

    /** Events rejected because the bounded audit queue was full. */
    val dropped: AtomicLong = AtomicLong(0L)

    private var day: LocalDate? = null
    private var channel: FileChannel? = null
    private val worker =
        Thread({
            while (running.get() || queue.isNotEmpty()) {
                val line =
                    try {
                        queue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        if (!running.get()) null else continue
                    } ?: continue
                writeLine(line)
            }
            runCatching { channel?.close() }
            channel = null
            day = null
        }, "qkt-engine-audit-journal-${owner.ifBlank { "session" }}").apply {
            isDaemon = true
            start()
        }

    /** Enqueue a stamped engine event for asynchronous JSONL persistence. */
    fun append(event: Event) {
        val eventDay =
            Instant
                .ofEpochMilli(if (event.timestamp > 0L) event.timestamp else clock.now())
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
        val line =
            buildString {
                append("{\"v\":1")
                append(",\"ts\":").append(event.timestamp)
                append(",\"seq\":").append(event.sequenceId)
                append(",\"eventType\":").append(jsonString(eventType(event)))
                strategyId(event)?.let { append(",\"strategyId\":").append(jsonString(it)) }
                orderId(event)?.let { append(",\"orderId\":").append(jsonString(it)) }
                symbol(event)?.let { append(",\"symbol\":").append(jsonString(it)) }
                append(",\"payload\":").append(jsonString(event.toString()))
                append("}\n")
            }
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (!running.get()) return
        if (!queue.offer(Line(eventDay, bytes))) {
            val n = dropped.incrementAndGet()
            if (n == 1L || n % 1_000L == 0L) {
                log.error("engine audit journal queue full for {}; dropped {} event(s)", owner, n)
            }
        }
    }

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
                channelFor(line.day).write(ByteBuffer.wrap(line.bytes))
            } catch (e: Exception) {
                log.error("engine audit journal append FAILED for {}: {}", owner, e.message)
                runCatching { channel?.close() }
                channel = null
                day = null
            }
        }
    }

    internal fun pendingForTesting(): Int = queue.size

    internal fun awaitDrainedForTesting(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) return true
            Thread.sleep(10)
        }
        return queue.isEmpty()
    }

    private fun channelFor(eventDay: LocalDate): FileChannel {
        val open = channel
        if (open != null && day == eventDay) return open
        runCatching { open?.close() }
        val dir = rootDir.resolve(owner.ifBlank { "_session" }.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        Files.createDirectories(dir)
        val next =
            FileChannel.open(
                dir.resolve("audit-$eventDay.jsonl"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC,
            )
        channel = next
        day = eventDay
        return next
    }

    private fun eventType(event: Event): String =
        event::class.qualifiedName ?: event::class.simpleName ?: event.javaClass.name

    private fun strategyId(event: Event): String? =
        when (event) {
            is SignalEvent -> event.strategyId.takeIf { it.isNotBlank() }
            is OrderEvent -> event.request.strategyId.takeIf { it.isNotBlank() }
            is BrokerEvent.OrderEvent -> event.strategyId.takeIf { it.isNotBlank() }
            is RiskRejectedEvent -> event.request.strategyId.takeIf { it.isNotBlank() }
            is RiskEvent.Halted -> event.strategyId?.takeIf { it.isNotBlank() }
            is RiskEvent.Resumed -> event.strategyId?.takeIf { it.isNotBlank() }
            else -> null
        }

    private fun orderId(event: Event): String? =
        when (event) {
            is OrderEvent -> event.request.id
            is BrokerEvent.OrderEvent -> event.clientOrderId
            is TradeEvent -> event.trade.orderId
            else -> null
        }

    private fun symbol(event: Event): String? =
        when (event) {
            is SignalEvent -> event.signal.symbolOrNull()
            is OrderEvent -> event.request.symbol
            is BrokerEvent.OrderFilled -> event.symbol
            is BrokerEvent.OrderPartiallyFilled -> event.symbol
            is BrokerEvent.PositionReconciled -> event.symbol
            is TradeEvent -> event.trade.symbol
            else -> null
        }

    private fun com.qkt.strategy.Signal.symbolOrNull(): String? =
        when (this) {
            is com.qkt.strategy.Signal.Buy -> symbol
            is com.qkt.strategy.Signal.Sell -> symbol
            is com.qkt.strategy.Signal.Submit -> request.symbol
            is com.qkt.strategy.Signal.CancelPendingForSymbol -> symbol
            is com.qkt.strategy.Signal.ArmLatch -> null
            is com.qkt.strategy.Signal.Suppressed -> symbol
        }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
}
