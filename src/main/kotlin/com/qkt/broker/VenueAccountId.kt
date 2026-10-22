package com.qkt.broker

import java.security.MessageDigest

/**
 * The opaque id of a venue account, so nothing qkt reports or keeps names an account by its
 * credentials. qkt-insights computes the same id (`packages/contract/src/accountIdentity.ts`):
 *
 * - MT5: `mt5:` + the first 16 hex characters of sha256(`<login>@<server>`)
 * - gateway: `vgp:` + the first 16 hex characters of sha256(`<adapter>:<account login>`)
 */
object VenueAccountId {
    private const val HEX_CHARS = 16
    private const val MASK = "•••"
    private const val SHOWN = 4

    /** The id of MT5 account [login] on [server]. */
    fun mt5(
        login: String,
        server: String,
    ): String = "mt5:${digest("$login@$server")}"

    /** The id of gateway account [accountLogin] on [adapter]. */
    fun vgp(
        adapter: String,
        accountLogin: String,
    ): String = "vgp:${digest("$adapter:$accountLogin")}"

    /** [login] with all but its last four characters hidden (`•••9381`), for display. */
    fun maskLogin(login: String): String = if (login.startsWith(MASK)) login else MASK + login.takeLast(SHOWN)

    private fun digest(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HEX_CHARS)
}
