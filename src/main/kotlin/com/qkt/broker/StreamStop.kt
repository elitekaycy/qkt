package com.qkt.broker

/** A stop a failed roll left on [strategyId]: its orders on the continuous [stream] are refused, because of [reason]. */
data class StreamStop(
    val strategyId: String,
    val stream: String,
    val reason: String,
)
