package com.qkt.cli.daemon

import com.qkt.broker.BrokerFactory
import com.qkt.broker.GatedBrokerFactory
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountPreflight
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.TradingAccount
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * The accounts the daemon could not reach at boot, verified again in the background until each answers (#1642).
 *
 * One gateway that is down must not stop the strategies of every other account, so the daemon starts without it.
 * Every order-entry factory of a waiting account refuses ([gate]): a strategy trading it fails to deploy and waits
 * in the [AutoDeployRetrier], so nothing trades on an account whose identity was never checked. [retryDue] verifies
 * each waiting account whose time has come, backing off from [initialBackoffMs] to [maxBackoffMs] with no deadline.
 * An account that answers leaves the list and [onVerified] runs (the daemon then retries the held deploys at once).
 * One that answers with the wrong identity stays refused for good and is not tried again.
 */
class AccountWaitlist(
    unreachable: List<Pair<TradingAccount, Exception>>,
    private val clock: Clock = SystemClock(),
    private val initialBackoffMs: Long = 2_000L,
    private val maxBackoffMs: Long = 30_000L,
) : AutoCloseable {
    /** One account still refused: since when, how often tried, the last failure, and whether it is still retried. */
    data class Waiting(
        val account: String,
        val sinceMs: Long,
        val attempts: Int,
        val lastError: String,
        val nextAttemptAtMs: Long,
        val retrying: Boolean,
    )

    private class Entry(
        val account: TradingAccount,
        @Volatile var state: Waiting,
        @Volatile var backoffMs: Long,
        @Volatile var lastLoggedMs: Long,
    )

    private val log = LoggerFactory.getLogger(AccountWaitlist::class.java)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val running = AtomicBoolean(false)

    @Volatile private var thread: Thread? = null

    /** Runs on the waitlist's thread once an account verifies. */
    @Volatile var onVerified: (TradingAccount, AccountProfile) -> Unit = { _, _ -> }

    init {
        val now = clock.now()
        for ((account, error) in unreachable) {
            val name = account.config.name
            val message = error.message ?: error::class.java.simpleName
            val waiting = Waiting(name, now, 1, message, now + initialBackoffMs, retrying = true)
            entries[name.lowercase()] = Entry(account, waiting, initialBackoffMs, now)
        }
    }

    /** The accounts still refused, in name order. */
    fun waiting(): List<Waiting> = entries.values.map { it.state }.sortedBy { it.account }

    /** Why [account] (any case) may not trade, or null when it may. */
    fun refusal(account: String): String? =
        entries[account.lowercase()]?.state?.let { w ->
            val why = if (w.retrying) "unreachable since boot, retried in the background" else "refused"
            "account ${w.account} is not verified ($why): ${w.lastError}"
        }

    /** [factories] with every waiting account's factory refusing until the account verifies. */
    fun gate(factories: Map<String, BrokerFactory>): Map<String, BrokerFactory> =
        factories.mapValues { (name, factory) ->
            if (entries.containsKey(name.lowercase())) GatedBrokerFactory(factory) { refusal(name) } else factory
        }

    /** Verifies every waiting account due at [nowMs]; returns the names that verified. */
    fun retryDue(nowMs: Long): List<String> {
        val verified = ArrayList<String>()
        for ((key, entry) in entries) {
            val w = entry.state
            if (!w.retrying || w.nextAttemptAtMs > nowMs) continue
            try {
                val profile = entry.account.verify()
                entries.remove(key)
                verified += w.account
                log.info("account {} answered: {}; its strategies start now", w.account, profile.description)
                runCatching { onVerified(entry.account, profile) }
                    .onFailure { log.error("starting the strategies of {} failed", w.account, it) }
            } catch (e: Exception) {
                failed(entry, e, nowMs)
            }
        }
        return verified
    }

    private fun failed(
        entry: Entry,
        e: Exception,
        nowMs: Long,
    ) {
        val w = entry.state
        val message = e.message ?: e::class.java.simpleName
        if (!AccountPreflight.isUnreachable(e)) {
            entry.state = w.copy(attempts = w.attempts + 1, lastError = message, retrying = false)
            log.error("account {} answered wrongly; its strategies stay stopped: {}", w.account, message)
            return
        }
        entry.backoffMs = minOf(entry.backoffMs * 2, maxBackoffMs)
        entry.state = w.copy(attempts = w.attempts + 1, lastError = message, nextAttemptAtMs = nowMs + entry.backoffMs)
        if (nowMs - entry.lastLoggedMs >= LOG_EVERY_MS || entry.backoffMs < maxBackoffMs) {
            entry.lastLoggedMs = nowMs
            log.warn(
                "account {} still unreachable after {} attempt(s) since {}s ago ({}); its strategies stay stopped",
                w.account,
                w.attempts + 1,
                (nowMs - w.sinceMs) / 1000,
                message,
            )
        }
    }

    /** Starts the background retries; idempotent, and a no-op when nothing waits. */
    fun start() {
        if (entries.isEmpty() || !running.compareAndSet(false, true)) return
        thread =
            Thread({
                while (running.get() && entries.values.any { it.state.retrying }) {
                    runCatching { retryDue(clock.now()) }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "qkt-account-waitlist").apply {
                isDaemon = true
                start()
            }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val LOG_EVERY_MS = 600_000L
    }
}
