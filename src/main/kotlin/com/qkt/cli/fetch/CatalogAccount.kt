package com.qkt.cli.fetch

import com.qkt.cli.Config
import com.qkt.cli.openAccounts
import com.qkt.connectivity.TradingAccount
import java.nio.file.Path

/**
 * The `brokers:` account named [venue] in the config a catalog fetch reads (`--config`, else the located
 * one), opened on first use: null when no config or no such account exists, or when it failed to open,
 * which is said on stderr and kept in [failed] so the fetch does not quietly fall back to another source.
 */
internal class CatalogAccount(
    private val venue: String,
    private val configOption: String?,
) {
    /** Whether the account is configured but could not be opened. */
    var failed: Boolean = false
        private set

    /** The opened account, or null. */
    val account: TradingAccount? by lazy(::open)

    private fun open(): TradingAccount? {
        val config = (configOption?.let { Path.of(it) } ?: Config.locate())?.let(Config::load) ?: return null
        return try {
            config.openAccounts().byName(venue)
        } catch (e: IllegalArgumentException) {
            failure(e)
        } catch (e: IllegalStateException) {
            failure(e)
        }
    }

    private fun failure(e: RuntimeException): TradingAccount? {
        failed = true
        System.err.println("qkt: failed to open broker '$venue': ${e.message}")
        return null
    }
}
