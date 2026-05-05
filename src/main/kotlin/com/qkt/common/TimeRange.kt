package com.qkt.common

import java.time.Instant

data class TimeRange(
    val from: Instant,
    val to: Instant,
) {
    init {
        require(from < to) { "TimeRange requires from < to: from=$from, to=$to" }
    }

    val durationMs: Long
        get() = to.toEpochMilli() - from.toEpochMilli()

    operator fun contains(t: Instant): Boolean = t >= from && t < to
}
