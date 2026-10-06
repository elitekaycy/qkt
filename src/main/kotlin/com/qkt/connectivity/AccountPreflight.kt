package com.qkt.connectivity

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.io.IOException

/**
 * The daemon's boot check of every account, patient with a gateway that is down but strict about
 * identity.
 *
 * An account whose venue cannot be reached ([AccountUnreachableException], or an I/O failure under
 * the connector's error) is retried with doubling backoff, [initialBackoffMs] up to [maxBackoffMs],
 * until [deadlineMs] after the first attempt; one deadline covers all accounts, so fifteen profiles
 * on one gateway wait for it once. Every other failure, an identity mismatch among them, throws at
 * once. Past the deadline the last unreachable failure throws. Nothing is returned, so nothing
 * trades, until every account verified.
 *
 * Example: `AccountPreflight.fromEnv().verifyAll(accounts)`; `QKT_PREFLIGHT_RETRY_SECONDS=0` restores
 * the old refuse-at-once behaviour.
 */
class AccountPreflight(
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    private val initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val clock: Clock = SystemClock(),
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    init {
        require(deadlineMs >= 0L) { "preflight retry deadline must not be negative" }
        require(initialBackoffMs > 0L && maxBackoffMs >= initialBackoffMs) { "preflight backoff must be positive" }
    }

    /** Verifies every account of [directory] in config order; see the class doc for what is retried. */
    fun verifyAll(directory: AccountDirectory): List<Pair<TradingAccount, AccountProfile>> {
        val deadlineAtMs = clock.now() + deadlineMs
        return directory.accounts.map { it to verify(it, deadlineAtMs) }
    }

    private fun verify(
        account: TradingAccount,
        deadlineAtMs: Long,
    ): AccountProfile {
        var backoffMs = initialBackoffMs
        var attempt = 1
        while (true) {
            try {
                return account.verify()
            } catch (e: Exception) {
                if (!isUnreachable(e)) throw e
                val remainingMs = deadlineAtMs - clock.now()
                if (remainingMs <= 0L) {
                    throw AccountUnreachableException(
                        "${e.message ?: e::class.java.simpleName} (still unreachable after ${deadlineMs / 1000}s " +
                            "of retries; set $ENV_RETRY_SECONDS to wait longer)",
                        e,
                    )
                }
                val waitMs = minOf(backoffMs, remainingMs)
                log(
                    "[WARN] preflight: ${account.config.name} unreachable (${e.message}); " +
                        "retry ${attempt + 1} in ${waitMs / 1000}s, ${remainingMs / 1000}s left",
                )
                sleep(waitMs)
                backoffMs = minOf(backoffMs * 2, maxBackoffMs)
                attempt++
            }
        }
    }

    private fun isUnreachable(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any {
            it is AccountUnreachableException || it is IOException
        }

    companion object {
        /** Env knob: seconds the boot preflight keeps retrying an unreachable account; `0` disables retries. */
        const val ENV_RETRY_SECONDS: String = "QKT_PREFLIGHT_RETRY_SECONDS"
        const val DEFAULT_DEADLINE_MS: Long = 300_000L
        const val DEFAULT_INITIAL_BACKOFF_MS: Long = 2_000L
        const val DEFAULT_MAX_BACKOFF_MS: Long = 30_000L
        private const val MAX_CAUSE_DEPTH = 8

        /** The preflight [env] configures: [ENV_RETRY_SECONDS] (default 300) bounds the retries. */
        fun fromEnv(env: Map<String, String> = System.getenv()): AccountPreflight {
            val raw = env[ENV_RETRY_SECONDS]?.trim()?.takeIf { it.isNotEmpty() }
            val seconds =
                raw?.let {
                    requireNotNull(it.toLongOrNull()?.takeIf { s -> s >= 0L }) {
                        "$ENV_RETRY_SECONDS must be a non-negative number of seconds; got '$it'"
                    }
                }
            return AccountPreflight(deadlineMs = seconds?.let { it * 1000L } ?: DEFAULT_DEADLINE_MS)
        }
    }
}
