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

class EventBus(
    private val clock: Clock,
    private val sequencer: SequenceGenerator,
) {
    private val log = LoggerFactory.getLogger(EventBus::class.java)

    @PublishedApi
    internal val subscribers = mutableMapOf<KClass<out Event>, MutableList<(Event) -> Unit>>()

    inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        subscribers
            .getOrPut(T::class) { mutableListOf() }
            .add { event -> handler(event as T) }
    }

    fun publish(event: Event) {
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
            is BrokerEvent.BalancesUpdated -> event.copy(timestamp = ts, sequenceId = seq)
            is BrokerEvent.PositionReconciled -> event.copy(timestamp = ts, sequenceId = seq)
            is RiskEvent.Halted -> event.copy(timestamp = ts, sequenceId = seq)
            is RiskEvent.Resumed -> event.copy(timestamp = ts, sequenceId = seq)
        }
    }
}
