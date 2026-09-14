package com.qkt.app

/**
 * Thrown when the broker historical-bar API fails during a deploy's pre-warmup
 * phase. Aborts deploy before any rule fires, so the strategy never starts in a
 * half-warm state. Operator sees a pointed error naming the stream + symbol +
 * underlying cause.
 */
class WarmupFailedException(
    val streamAlias: String,
    val qktSymbol: String,
    cause: Throwable,
) : RuntimeException(
        "qkt: failed to fetch warmup history for stream '$streamAlias' ($qktSymbol) — " +
            "broker historical API returned: ${cause.message ?: cause::class.simpleName}. " +
            "Deploy aborted. Retry after fixing the broker connection. If the cause names an " +
            "unsupported timeframe, change the stream's EVERY window — WARMUP and indicator " +
            "periods do not affect which windows the venue can serve.",
        cause,
    )
