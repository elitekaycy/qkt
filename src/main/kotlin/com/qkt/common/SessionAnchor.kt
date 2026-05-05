package com.qkt.common

import java.time.Duration

sealed class SessionAnchor {
    object PreviousDay : SessionAnchor()

    object CurrentSession : SessionAnchor()

    object PreviousSession : SessionAnchor()

    data class Rolling(
        val duration: Duration,
    ) : SessionAnchor() {
        init {
            require(!duration.isZero && !duration.isNegative) {
                "Rolling duration must be positive: $duration"
            }
        }
    }
}
