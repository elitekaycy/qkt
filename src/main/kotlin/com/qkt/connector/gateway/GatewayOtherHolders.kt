package com.qkt.connector.gateway

import java.math.BigDecimal

/**
 * What the other holders of a shared gateway account (another qkt daemon, a manual trade) hold of each
 * venue code this daemon's strategies trade: the account's position less the strategies' holding, noted
 * when a code is first traded or judged here. A netting venue reports only the total, so other holders'
 * size cannot be told from a phantom; what can be checked is that it stays put. The account's position
 * on a traded code must equal the strategies' holding plus that size, so only this daemon's own fills may
 * move it; codes the strategies never trade are not judged. Not thread-safe: its owner calls it under one lock.
 *
 * ```kotlin
 * val code = "BTC_USDC-PERPETUAL"
 * val others = GatewayOtherHolders()
 * others.enter(code, account = BigDecimal("0.003"), held = BigDecimal.ZERO)
 * others.mismatch(mapOf(code to BigDecimal("0.1")), mapOf(code to BigDecimal("0.103"))) // null: a fill here moved it
 * ```
 */
internal class GatewayOtherHolders {
    private val size = HashMap<String, BigDecimal>()

    /** The strategies trade [code], holding [held] while the account holds [account]; a code's first note stands. */
    fun enter(
        code: String,
        account: BigDecimal,
        held: BigDecimal,
    ) {
        size.putIfAbsent(code, account.subtract(held))
    }

    /**
     * Why the account's position on a traded code moved without a fill here, or null when every traded
     * code is its [held] holding plus other holders' size. A code [held] lists that was never noted is
     * noted now (a book a restart restored).
     */
    fun mismatch(
        held: Map<String, BigDecimal>,
        account: Map<String, BigDecimal>,
    ): String? {
        val of = { map: Map<String, BigDecimal>, code: String -> map[code] ?: BigDecimal.ZERO }
        held.forEach { (code, quantity) -> enter(code, of(account, code), quantity) }
        val moved =
            size.keys.sorted().filter { code ->
                of(account, code).subtract(of(held, code)).compareTo(size.getValue(code)) != 0
            }
        if (moved.isEmpty()) return null
        return moved.joinToString("; ") { code ->
            "the account's $code moved without a fill here: strategies hold ${of(held, code).toPlainString()}, " +
                "account holds ${of(
                    account,
                    code,
                ).toPlainString()}, other holders held ${size.getValue(code).toPlainString()}"
        }
    }
}
