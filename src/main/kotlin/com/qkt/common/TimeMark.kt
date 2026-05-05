package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

sealed class TimeMark {
    object Now : TimeMark()

    data class Absolute(
        val instant: Instant,
    ) : TimeMark()

    data class AtSessionAnchor(
        val anchor: SessionAnchor,
        val timeOfDay: LocalTime? = null,
    ) : TimeMark()

    data class RelativeToNow(
        val offset: Duration,
    ) : TimeMark()
}
