package com.qkt.broker.continuous

import java.security.MessageDigest

/**
 * The prefix of a roll's legs for one strategy: `roll:<atMs>:<digest>`, where the digest is the first 8 hex
 * digits of the SHA-256 of the stream, the roll time and the strategy. It is the same after a restart, differs
 * per stream, roll and strategy, and stays short whatever the names, so a leg's id plus its `:<kind>` and the
 * gateway's submit-time suffix fit VGP's 64 characters (`roll:PAPER_DERIBIT:BTC_USDC@front:<ms>:soak_o14_roll`
 * alone was 67), and even the last attempt's `:unwind-5` fits Bybit's 45 unshortened (#1643).
 */
internal object RollLegIds {
    private const val DIGEST_HEX = 8

    /** How often a leg, or a roll's look at the quotes, is tried: five times about 60 s all told. */
    const val MAX_ATTEMPTS = 5
    const val RETRY_SPACING_MS = 15_000L

    /** No retry inside this long of the old contract's expiry: the exchange settles what is left. */
    const val RETRY_EXPIRY_MARGIN_MS = 600_000L

    /** Which leg [id] is: `close`, `open` or `unwind`. */
    fun kind(id: String): String = id.substringAfterLast(':').substringBefore('-')

    /** Which attempt [id] is: the first has no suffix, the next `-2`. */
    fun attempt(id: String): Int = id.substringAfterLast(':').substringAfter('-', "1").toInt()

    /** The id of the attempt after [id], new at the venue. */
    fun next(id: String): String = "${id.substringBeforeLast(':')}:${kind(id)}-${attempt(id) + 1}"

    fun base(
        stream: String,
        atMs: Long,
        strategyId: String,
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$stream\n$atMs\n$strategyId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "roll:$atMs:${digest.take(DIGEST_HEX)}"
    }
}
