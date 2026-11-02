package com.qkt.broker

/**
 * What an operator resume did about the stop [strategyId] carries on a continuous [stream]: [stop] is why orders
 * were being refused, and [stands] why they still are, or null when the stop was cleared.
 */
data class StreamStopRelease(
    val strategyId: String,
    val stream: String,
    val stop: String,
    val stands: String?,
) {
    /** Whether the stream takes the strategy's orders again. */
    val cleared: Boolean get() = stands == null
}
