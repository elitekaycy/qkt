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
    internal val subscribers = mutableMapOf<Class<out Event>, MutableList<(Event) -> Unit>>()

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
     * Bind ONLY the sink, before the engine thread exists. From this call until
     * [bindEngineLoop] supplies the thread, EVERY publish (any thread — broker pollers
     * starting at construction, the deploy thread's recovery events) is queued instead
     * of dispatched inline, closing the window where early events raced a half-built
     * pipeline on the wrong thread. Queued events drain in order once the loop starts.
     */
    fun bindSink(sink: (Event) -> Unit) {
        this.offThreadSink = sink
    }

    /**
     * Registers [handler] to be invoked for every published event of type [T].
     *
     * Handlers run synchronously on the publishing thread, in registration order. A
     * handler that throws no longer prevents later handlers from running — the bus
     * dispatches to every subscriber, then rethrows the first failure (see [publish]).
     */
    inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        subscribers
            .getOrPut(T::class.java) { mutableListOf() }
            .add { event -> handler(event as T) }
    }

    /**
     * Registers [handler] AHEAD of every already-registered handler for [T].
     *
     * Subscriber order on `OrderFilled` is load-bearing for risk: the book-applying
     * handler must run before any handler with venue side effects (OCO sibling cancels,
     * stack child submissions), or those act on pre-fill position state. This is the
     * explicit hook for that invariant — use it only for book-keeping handlers.
     */
    inline fun <reified T : Event> subscribeFirst(noinline handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        subscribers
            .getOrPut(T::class.java) { mutableListOf() }
            .add(0) { event -> handler(event as T) }
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
        // Guarded: publish is the engine's hottest line. Unguarded, the two Long args box and a
        // vararg Object[] allocates on every publish even when trace is off; the guard skips all of
        // that. Output is unchanged when trace is enabled.
        if (log.isTraceEnabled) {
            log.trace("publish {} seq={} ts={}", stamped::class.simpleName, stamped.sequenceId, stamped.timestamp)
        }
        // Index loop over the handler list (the single most-traversed line in the engine) avoids
        // allocating an Iterator per published event.
        // Per-subscriber isolation: one handler's exception must not silently skip the
        // rest — a fill that mutated the venue (sibling cancelled, children sent) but
        // never reached the book-applier leaves the engine permanently diverged. Every
        // subscriber runs; the first failure then rethrows so the caller's fault
        // handling (engine-loop halt + alert in live, loud failure in backtest) still fires.
        val handlers = subscribers[stamped.javaClass] ?: return
        var firstFailure: Exception? = null
        for (i in handlers.indices) {
            try {
                handlers[i](stamped)
            } catch (e: Exception) {
                log.error("subscriber {} for {} failed", i, stamped::class.simpleName, e)
                if (firstFailure == null) firstFailure = e
            }
        }
        if (firstFailure != null) throw firstFailure
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
            is BrokerEvent.GatewayUnreachable -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.PositionReconciled -> event.copy(timestamp = ts, sequenceId = seq)
            is RiskEvent.Halted -> event.copy(timestamp = ts, sequenceId = seq)
            is RiskEvent.Resumed -> event.copy(timestamp = ts, sequenceId = seq)
        }
    }
}
