package com.qkt.cli.daemon

import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.AccountPreflight
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.TradingAccount
import org.slf4j.LoggerFactory

/**
 * The accounts a daemon starts with: those that verified at boot, and the [waitlist] of those it could not reach,
 * whose strategies wait until each answers (#1642).
 */
internal class DaemonAccountBoot(
    val verified: List<Pair<TradingAccount, AccountProfile>>,
    val waitlist: AccountWaitlist,
) {
    /** Prints the accounts of [accounts] at startup, each verified one's profile, and each one still waiting. */
    fun printLoaded(accounts: AccountDirectory) {
        if (accounts.accounts.isEmpty()) return
        println("[INFO] broker accounts loaded: ${accounts.accounts.joinToString { it.config.name }}")
        verified.forEach { (_, profile) -> println("[INFO] account: ${profile.description}") }
        waitlist.waiting().forEach { println("[WARN] account: ${it.account} NOT VERIFIED: ${it.lastError}") }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DaemonAccountBoot::class.java)

        /**
         * Verifies every account of [accounts] once. An unreachable one does not stop the daemon: it is logged (daemon
         * log file and stderr) and left to the waitlist. Returns null, after logging why, when an account answered
         * with the wrong identity: the daemon refuses to start then, as before.
         */
        fun verify(
            accounts: AccountDirectory,
            preflight: AccountPreflight = AccountPreflight(),
        ): DaemonAccountBoot? {
            val boot =
                try {
                    preflight.verifyReachable(accounts.accounts)
                } catch (e: Exception) {
                    log.error("qkt: broker account preflight failed: {}", e.message ?: e::class.java.simpleName)
                    return null
                }
            for ((account, error) in boot.unreachable) {
                log.error(
                    "account {} unreachable at boot ({}); starting without it: its strategies are not started " +
                        "and start once it answers, retried in the background",
                    account.config.name,
                    error.message ?: error::class.java.simpleName,
                )
            }
            return DaemonAccountBoot(boot.verified, AccountWaitlist(boot.unreachable))
        }
    }
}
