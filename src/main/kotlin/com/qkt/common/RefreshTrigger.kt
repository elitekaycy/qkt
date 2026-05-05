package com.qkt.common

import java.time.LocalTime

sealed class RefreshTrigger {
    object Once : RefreshTrigger()

    data class EveryNTicks(
        val n: Int,
    ) : RefreshTrigger() {
        init {
            require(n > 0) { "EveryNTicks requires n > 0: $n" }
        }
    }

    data class OnAnchorRollover(
        val anchor: SessionAnchor,
        val calendar: TradingCalendar,
    ) : RefreshTrigger()

    object OnSessionRollover : RefreshTrigger()

    data class OnTimeOfDay(
        val time: LocalTime,
    ) : RefreshTrigger()
}
