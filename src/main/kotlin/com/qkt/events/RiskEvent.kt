package com.qkt.events

sealed interface RiskEvent : Event {
    data class Halted(
        val reason: String,
        val strategyId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent

    data class Resumed(
        val strategyId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent
}
