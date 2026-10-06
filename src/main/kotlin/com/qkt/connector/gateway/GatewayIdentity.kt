package com.qkt.connector.gateway

/**
 * The gateway an account must be talking to: protocol `vgp1`, [adapter], [accountLogin] and [tradeMode].
 * Checked before anything trades and again whenever the gateway's event log restarts, since a restarted
 * gateway could have come back against another account.
 */
internal data class GatewayIdentity(
    val adapter: String,
    val accountLogin: String,
    val tradeMode: String,
) {
    /** Why [health] is not this gateway, or null when it is. */
    fun mismatch(health: WireHealth): String? =
        when {
            health.protocol != "vgp1" -> "gateway speaks '${health.protocol}', not vgp1"
            health.adapter != adapter -> "gateway adapter '${health.adapter}', expected '$adapter'"
            health.accountLogin != accountLogin -> "gateway account '${health.accountLogin}', expected '$accountLogin'"
            health.tradeMode != tradeMode -> "gateway trade mode '${health.tradeMode}', expected '$tradeMode'"
            else -> null
        }
}
