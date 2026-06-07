package com.qkt.bus

import com.qkt.common.Clock
import com.qkt.common.SequenceGenerator
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.Event
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.events.WarmupTickEvent
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

/**
 * Single-threaded publish/subscribe bus for [Event]s.
 *
 * The backbone of qkt's event-driven pipeline. Every component publishes through one
 * bus instance and subscribes through the same bus instance — strategies emit signals,
 * brokers emit fills, the engine emits ticks. Subscribers are invoked synchronously in
 * registration order on the publishing thread; the bus is intentionally not thread-safe
 * because the engine is single-threaded by design.
 *
 * The bus stamps every published event with a deterministic [com.qkt.common.SequenceGenerator]
 * id and the current [Clock] time, which is what makes backtest replay bit-identical
 * to a live run on the same tick stream.
 */
class EventBus(
    private val clock: Clock,
    private val sequencer: SequenceGenerator,
) {
    private val log = LoggerFactory.getLogger(EventBus::class.java)

    @PublishedApi
    internal val subscribers = mutableMapOf<KClass<out Event>, MutableList<(Event) -> Unit>>()

    /**
     * Live-mode binding: the single engine-loop thread and a sink that hands an event to that
     * loop's inbound queue. When set, a [publish] called from any OTHER thread (a broker poller,
     * a WebSocket reader, the schedule heartbeat, an HTTP control request) is rerouted to [sink]
     * instead of dispatching inline — so every subscriber still runs on exactly one thread, the
     * way the engine assumes. Left null in backtest (one thread already), where publish is always
     * inline and behaviour is unchanged.
     */
    @Volatile private var engineThread: Thread? = null

    @Volatile private var offThreadSink: ((Event) -> Unit)? = null

    /**
     * Route off-thread publishes onto the single-consumer engine loop. [engineThread] is the loop
     * thread; [sink] enqueues an event for that loop to publish inline when it drains the queue.
     * Call once, before any off-thread source can publish.
     */
    fun bindEngineLoop(
        engineThread: Thread,
        sink: (Event) -> Unit,
    ) {
        this.engineThread = engineThread
        this.offThreadSink = sink
    }

    /**
     * Registers [handler] to be invoked for every published event of type [T].
     *
     * Handlers run synchronously on the publishing thread, in registration order. A
     * handler that throws will propagate and prevent later handlers from running for
     * that event — keep handlers fast and exception-free.
     */
    inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        subscribers
            .getOrPut(T::class) { mutableListOf() }
            .add { event -> handler(event as T) }
    }

    /**
     * Stamps [event] with the current clock time + next sequence id, then dispatches it
     * to every subscriber registered for the event's concrete class.
     *
     * Dispatch is synchronous; the call returns once all subscribers have run.
     */
    fun publish(event: Event) {
        // Live mode: a publish from any thread other than the engine loop is handed to the loop's
        // inbound queue (raw, unstamped — the loop stamps it inline so the deterministic sequence
        // id stays single-threaded). The engine thread, and any backtest (no binding), dispatch
        // inline here.
        val sink = offThreadSink
        if (sink != null && Thread.currentThread() !== engineThread) {
            sink(event)
            return
        }
        val stamped = stamp(event)
        log.trace("publish {} seq={} ts={}", stamped::class.simpleName, stamped.sequenceId, stamped.timestamp)
        subscribers[stamped::class]?.forEach { it(stamped) }
    }

    private fun stamp(event: Event): Event {
        val ts = clock.now()
        val seq = sequencer.next()
        return when (event) {
            is TickEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is WarmupTickEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is CandleEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is SignalEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is OrderEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is RiskRejectedEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is TradeEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.OrderAccepted -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.OrderRejected -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.OrderFilled -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.OrderPartiallyFilled -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.OrderCancelled -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.OrderModified -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.BalancesUpdated -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.PositionReconciled -> event.copy(timestamp = ts, sequenceId = seq)
            is RiskEvent.Halted -> event.copy(timestamp = ts, sequenceId = seq)
            is RiskEvent.Resumed -> event.copy(timestamp = ts, sequenceId = seq)
        }
    }
}
