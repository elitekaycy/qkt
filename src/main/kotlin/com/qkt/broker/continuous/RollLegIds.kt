package com.qkt.broker.continuous

import java.security.MessageDigest

/**
 * The prefix of a roll's legs for one strategy: `roll:<atMs>:<digest>`, where the digest is the first 12 hex
 * digits of the SHA-256 of the stream, the roll time and the strategy. It is the same after a restart, differs
 * per stream, roll and strategy, and stays short whatever the names, so a leg's id plus its `:<kind>` and the
 * gateway's submit-time suffix fit VGP's 64 characters (`roll:PAPER_DERIBIT:BTC_USDC@front:<ms>:soak_o14_roll`
 * alone was 67).
 */
internal object RollLegIds {
    private const val DIGEST_HEX = 12

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
