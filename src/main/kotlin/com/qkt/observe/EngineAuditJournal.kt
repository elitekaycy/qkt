package com.qkt.observe

import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.Event
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.events.StrategyCandleEvaluatedEvent
import com.qkt.events.StreamCandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderRequestEvidence
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import com.qkt.strategy.Signal
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
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
    private val rootDir: Path,
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

    private var day: LocalDate? = null
    private var channel: FileChannel? = null
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
            runCatching { channel?.close() }
            channel = null
            day = null
        }, "qkt-engine-audit-journal-${owner.ifBlank { "session" }}").apply {
            isDaemon = true
            start()
        }

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
        val line =
            buildString {
                append("{\"v\":1")
                append(",\"ts\":").append(event.timestamp)
                append(",\"seq\":").append(event.sequenceId)
                append(",\"eventType\":").append(jsonString(eventType(event)))
                strategyId(event)?.let { append(",\"strategyId\":").append(jsonString(it)) }
                orderId(event)?.let { append(",\"orderId\":").append(jsonString(it)) }
                symbol(event)?.let { append(",\"symbol\":").append(jsonString(it)) }
                if (event is TickEvent) appendTick(event.tick)
                if (event is WarmupTickEvent) {
                    event.sourceTimeframeMs?.let { append(",\"sourceTimeframeMs\":").append(it) }
                    appendTick(event.tick)
                }
                if (event is CandleEvent) appendCandle(event.candle)
                if (event is StreamCandleEvent) {
                    append(",\"broker\":").append(jsonString(event.broker))
                    append(",\"timeframe\":").append(jsonString(event.timeframe))
                    appendCandle(event.candle)
                }
                if (event is StrategyCandleEvaluatedEvent) {
                    append(",\"alias\":").append(jsonString(event.alias))
                    append(",\"broker\":").append(jsonString(event.broker))
                    append(",\"timeframe\":").append(jsonString(event.timeframe))
                    append(",\"rulesEvaluated\":").append(event.rulesEvaluated)
                    appendCandle(event.candle)
                }
                if (event is RuleDecisionEvent) {
                    append(",\"decisionId\":").append(jsonString(event.decisionId))
                    append(",\"ruleId\":").append(jsonString(event.ruleId))
                    append(",\"strategyFingerprint\":").append(jsonString(event.strategyFingerprint))
                    append(",\"ruleFingerprint\":").append(jsonString(event.ruleFingerprint))
                    append(",\"conditionFingerprint\":").append(jsonString(event.conditionFingerprint))
                    append(",\"conditionResult\":").append(event.conditionResult)
                    append(",\"alias\":").append(jsonString(event.alias))
                    append(",\"broker\":").append(jsonString(event.broker))
                    append(",\"timeframe\":").append(jsonString(event.timeframe))
                    append(",\"signalCount\":").append(event.signalCount)
                    appendCandle(event.candle)
                }
                if (event is DecisionOrderLinkedEvent) {
                    append(",\"decisionId\":").append(jsonString(event.decisionId))
                    append(",\"ruleId\":").append(jsonString(event.ruleId))
                    append(",\"signalIndex\":").append(event.signalIndex)
                }
                if (event is OrderEvent) appendOrder(event.request)
                if (event is RiskRejectedEvent) {
                    append(",\"reason\":").append(jsonString(event.reason))
                    appendOrder(event.request)
                }
                if (event is SignalEvent && event.signal is Signal.Submit) {
                    appendOrder(event.signal.request)
                }
                if (event is FillAccountedEvent) appendAccountedFill(event)
                if (event is BrokerEvent.OrderFilled) appendFill(event)
                if (event is BrokerEvent.OrderPartiallyFilled) appendPartialFill(event)
                append(",\"payload\":").append(jsonString(event.toString()))
                append("}\n")
            }
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
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
                val buffer = ByteBuffer.wrap(line.bytes)
                val output = channelFor(line.day)
                while (buffer.hasRemaining()) output.write(buffer)
            } catch (e: Exception) {
                markDropped(line.day)
                log.error("engine audit journal append FAILED for {}: {}", owner, e.message)
                runCatching { channel?.close() }
                channel = null
                day = null
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
                val dir = ownerDir()
                createPrivateDirectory(dir)
                val marker = dir.resolve("audit-$eventDay.dropped")
                Files.writeString(
                    marker,
                    "$count\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.DSYNC,
                )
                makeOwnerOnly(marker)
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

    private fun channelFor(eventDay: LocalDate): FileChannel {
        val open = channel
        if (open != null && day == eventDay) return open
        runCatching { open?.close() }
        val dir = ownerDir()
        createPrivateDirectory(dir)
        val path = dir.resolve("audit-$eventDay.jsonl")
        val next =
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC,
            )
        makeOwnerOnly(path)
        channel = next
        day = eventDay
        return next
    }

    private fun ownerDir(): Path = rootDir.resolve(owner.ifBlank { "_session" }.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun createPrivateDirectory(path: Path) {
        Files.createDirectories(path)
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
    }

    private fun makeOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
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
            is StrategyCandleEvaluatedEvent -> event.strategyId.takeIf { it.isNotBlank() }
            is RuleDecisionEvent -> event.strategyId.takeIf { it.isNotBlank() }
            is DecisionOrderLinkedEvent -> event.strategyId.takeIf { it.isNotBlank() }
            is FillAccountedEvent -> event.strategyId.takeIf { it.isNotBlank() }
            is TradeEvent -> event.strategyId.takeIf { it.isNotBlank() }
            is SignalSuppressedEvent -> event.strategyId.takeIf { it.isNotBlank() }
            else -> null
        }

    private fun orderId(event: Event): String? =
        when (event) {
            is OrderEvent -> event.request.id
            is BrokerEvent.OrderEvent -> event.clientOrderId
            is TradeEvent -> event.trade.orderId
            is RiskRejectedEvent -> event.request.id
            is DecisionOrderLinkedEvent -> event.orderId
            is FillAccountedEvent -> event.orderId
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
            is TickEvent -> event.tick.symbol
            is WarmupTickEvent -> event.tick.symbol
            is CandleEvent -> event.candle.symbol
            is StreamCandleEvent -> event.candle.symbol
            is StrategyCandleEvaluatedEvent -> event.candle.symbol
            is RuleDecisionEvent -> event.candle.symbol
            is FillAccountedEvent -> event.symbol
            is RiskRejectedEvent -> event.request.symbol
            else -> null
        }

    private fun StringBuilder.appendOrder(request: OrderRequest) {
        append(",\"orderSchemaVersion\":").append(OrderRequestEvidence.SCHEMA_VERSION)
        append(",\"order\":").append(OrderRequestEvidence.toJson(request))
    }

    private fun StringBuilder.appendAccountedFill(event: FillAccountedEvent) {
        append(",\"accountedFill\":{")
        append("\"fillSliceId\":").append(jsonString(event.fillSliceId))
        append(",\"sourceFillSequenceId\":").append(event.sourceFillSequenceId)
        event.cumulativeFilled?.let {
            append(",\"cumulativeFilled\":").append(jsonString(it.toPlainString()))
        }
        append(",\"modeledCommissionAccount\":")
            .append(jsonString(event.modeledCommissionAccount.toPlainString()))
        append(",\"venueCostsAccount\":").append(jsonString(event.venueCostsAccount.toPlainString()))
        append(",\"totalCostsAccount\":").append(jsonString(event.totalCostsAccount.toPlainString()))
        append(",\"accountNativeRealized\":").append(jsonString(event.accountNativeRealized.toPlainString()))
        append(",\"strategyNativeRealized\":")
            .append(jsonString(event.strategyNativeRealized.toPlainString()))
        append(",\"nativeCurrency\":").append(jsonString(event.nativeCurrency))
        append(",\"grossAccountRealized\":").append(jsonString(event.grossAccountRealized.toPlainString()))
        append(",\"grossStrategyAccountRealized\":")
            .append(jsonString(event.grossStrategyAccountRealized.toPlainString()))
        append(",\"accountCurrency\":").append(jsonString(event.accountCurrency))
        append(",\"netAccountRealized\":").append(jsonString(event.netAccountRealized.toPlainString()))
        append(",\"netStrategyAccountRealized\":")
            .append(jsonString(event.netStrategyAccountRealized.toPlainString()))
        event.conversionRate?.let {
            append(",\"conversionRate\":").append(jsonString(it.toPlainString()))
        }
        event.conversionTimestampMs?.let { append(",\"conversionTimestampMs\":").append(it) }
        event.conversionSource?.let { append(",\"conversionSource\":").append(jsonString(it)) }
        event.contractSize?.let { append(",\"contractSize\":").append(jsonString(it.toPlainString())) }
        append(",\"accountPositionBefore\":").appendPosition(event.accountPositionBefore)
        append(",\"accountPositionAfter\":").appendPosition(event.accountPositionAfter)
        append(",\"strategyPositionBefore\":").appendPosition(event.strategyPositionBefore)
        append(",\"strategyPositionAfter\":").appendPosition(event.strategyPositionAfter)
        append(",\"reducedExposure\":").append(event.reducedExposure)
        append(",\"partial\":").append(event.partial)
        append('}')
    }

    private fun StringBuilder.appendPosition(position: Position?): StringBuilder {
        if (position == null) return append("null")
        append('{')
        append("\"symbol\":").append(jsonString(position.symbol))
        append(",\"quantity\":").append(jsonString(position.quantity.toPlainString()))
        append(",\"avgEntryPrice\":").append(jsonString(position.avgEntryPrice.toPlainString()))
        position.openedAt?.let { append(",\"openedAtMs\":").append(it) }
        return append('}')
    }

    private fun StringBuilder.appendTick(tick: Tick) {
        append(",\"tick\":{")
        append("\"timestampMs\":").append(tick.timestamp)
        append(",\"price\":").append(jsonString(tick.price.toPlainString()))
        tick.volume?.let { append(",\"volume\":").append(jsonString(it.toPlainString())) }
        tick.bid?.let { append(",\"bid\":").append(jsonString(it.toPlainString())) }
        tick.ask?.let { append(",\"ask\":").append(jsonString(it.toPlainString())) }
        tick.bidVolume?.let { append(",\"bidVolume\":").append(jsonString(it.toPlainString())) }
        tick.askVolume?.let { append(",\"askVolume\":").append(jsonString(it.toPlainString())) }
        append('}')
    }

    private fun StringBuilder.appendCandle(candle: Candle) {
        append(",\"candle\":{")
        append("\"startTimeMs\":").append(candle.startTime)
        append(",\"endTimeMs\":").append(candle.endTime)
        append(",\"open\":").append(jsonString(candle.open.toPlainString()))
        append(",\"high\":").append(jsonString(candle.high.toPlainString()))
        append(",\"low\":").append(jsonString(candle.low.toPlainString()))
        append(",\"close\":").append(jsonString(candle.close.toPlainString()))
        append(",\"volume\":").append(jsonString(candle.volume.toPlainString()))
        candle.bid?.let { append(",\"bid\":").append(jsonString(it.toPlainString())) }
        candle.ask?.let { append(",\"ask\":").append(jsonString(it.toPlainString())) }
        append('}')
    }

    private fun StringBuilder.appendFill(event: BrokerEvent.OrderFilled) {
        append(",\"fill\":{")
        append("\"side\":").append(jsonString(event.side.name))
        append(",\"price\":").append(jsonString(event.price.toPlainString()))
        append(",\"quantity\":").append(jsonString(event.quantity.toPlainString()))
        append(",\"brokerOrderId\":").append(jsonString(event.brokerOrderId.orEmpty()))
        append(",\"partial\":false}")
    }

    private fun StringBuilder.appendPartialFill(event: BrokerEvent.OrderPartiallyFilled) {
        append(",\"fill\":{")
        append("\"side\":").append(jsonString(event.side.name))
        append(",\"price\":").append(jsonString(event.price.toPlainString()))
        append(",\"quantity\":").append(jsonString(event.quantity.toPlainString()))
        append(",\"cumulativeFilled\":").append(jsonString(event.cumulativeFilled.toPlainString()))
        append(",\"brokerOrderId\":").append(jsonString(event.brokerOrderId.orEmpty()))
        append(",\"partial\":true}")
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
