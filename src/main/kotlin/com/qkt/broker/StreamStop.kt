package com.qkt.broker

/**
 * A stop a failed roll left on [strategyId]: its orders on the continuous [stream] are refused, because of [reason].
 * [holds] says what the venue still holds for it on the stream's contracts, e.g. `-0.001 of DERIBIT:BTC_USDC_4NOV26`.
 */
data class StreamStop(
    val strategyId: String,
    val stream: String,
    val reason: String,
    val holds: List<String> = emptyList(),
)
