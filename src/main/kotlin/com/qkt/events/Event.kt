package com.qkt.events

import com.qkt.execution.Order
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal

sealed class Event {
    abstract val timestamp: Long
    abstract val sequenceId: Long
}

data class TickEvent(
    val tick: Tick,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class SignalEvent(
    val signal: Signal,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class OrderEvent(
    val order: Order,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class TradeEvent(
    val trade: Trade,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()
