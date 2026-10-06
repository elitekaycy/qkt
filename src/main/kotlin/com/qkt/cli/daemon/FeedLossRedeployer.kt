package com.qkt.cli.daemon

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brings back strategies whose live feed was lost, without a daemon restart.
 *
 * A live feed that stays disconnected past its reconnect budget ends its session rather than let it
 * trade on stale prices (the session reports why through
 * [com.qkt.app.FeedLossReads.unexpectedFeedEnd]). Left alone, the deployment stays stopped until an
 * operator redeploys it or restarts the daemon, so a gateway outage longer than the budget would
 * end strategies for good. Each [sweep] removes such a stopped standalone strategy from the
 * [registry] and hands its file to the [retrier]: the same deploy path a daemon boot uses, so the
 * redeployed session recovers its state, re-adopts its venue positions and resumes order ids
 * exactly as after a restart, and retries on the retrier's backoff while the venue is still down.
 *
 * Portfolio children are left to their portfolio, and a session stopped by an operator or by a fault
 * is never redeployed.
 */
class FeedLossRedeployer(
    private val registry: StrategyRegistry,
    private val retrier: AutoDeployRetrier,
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {
    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    /** Hands every strategy stopped by a lost feed to the retrier; returns their names. */
    fun sweep(): List<String> {
        val handedOff = ArrayList<String>()
        for (handle in registry.list()) {
            if (handle.childMeta != null || handle.isRunning()) continue
            val reason = handle.live.unexpectedFeedEnd() ?: continue
            val file = handle.sourceFile ?: continue
            if (registry.get(handle.name) !== handle || !registry.stop(handle.name)) continue
            retrier.schedule(handle.name, file, "live feed lost: $reason")
            handedOff += handle.name
            log("[WARN] ${handle.name} stopped by a lost live feed ($reason); redeploying from $file")
        }
        if (handedOff.isNotEmpty()) retrier.start()
        return handedOff
    }

    /** Sweeps every [pollMs] on a daemon thread until [close]; idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread =
            Thread({
                while (running.get()) {
                    runCatching { sweep() }.onFailure { log("[WARN] feed-loss sweep failed: ${it.message}") }
                    try {
                        Thread.sleep(pollMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "qkt-feed-loss-redeploy").apply { isDaemon = true }
        thread?.start()
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
    }

    companion object {
        /** How often stopped sessions are looked for; the redeploy itself waits on the retrier's backoff. */
        const val DEFAULT_POLL_MS: Long = 5_000L
    }
}
