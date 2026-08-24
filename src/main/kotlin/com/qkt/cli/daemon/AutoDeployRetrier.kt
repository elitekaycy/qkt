package com.qkt.cli.daemon

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Retries `--load-dir` auto-deploys that failed at startup (#1055).
 *
 * A deploy can fail for a reason that clears on its own — the warmup time-base guard
 * during the first bar after a venue gap, a gateway still reconnecting at boot, a
 * transient history error. Warning once and running with zero strategies is the worst
 * outcome for a forward test, so failed files stay pending here and are retried on a
 * backoff ([backoffMs], last value repeats) until they deploy or an operator deploys the
 * same name by hand. Pending entries are reported through `/health` so the daemon does
 * not look healthy while idle.
 *
 * Deterministic core: [retryDue] runs every retry whose time has come at the supplied
 * instant, so tests drive it with a fixed clock; [start] wraps it in a daemon thread.
 */
class AutoDeployRetrier(
    private val deploy: (name: String, file: Path) -> Unit,
    private val alreadyDeployed: (name: String) -> Boolean,
    private val clock: Clock = SystemClock(),
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {
    /** One file awaiting redeploy. */
    data class Pending(
        val name: String,
        val file: Path,
        val attempts: Int,
        val lastError: String,
        val nextAttemptAtMs: Long,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    init {
        require(backoffMs.isNotEmpty() && backoffMs.all { it > 0L }) { "backoff schedule must be positive" }
    }

    /** Record a failed auto-deploy; the first retry is due after the first backoff step. */
    fun schedule(
        name: String,
        file: Path,
        error: String,
    ) {
        pending[name] =
            Pending(name, file, attempts = 1, lastError = error, nextAttemptAtMs = clock.now() + backoffMs.first())
    }

    /**
     * Files still awaiting a successful deploy, oldest next attempt first. A name the
     * operator has since deployed by hand is dropped here as well as in [retryDue], so
     * `/health` stops reporting `degraded` the moment the strategy is actually running
     * instead of at the next backoff tick (#1060).
     */
    fun pending(): List<Pending> =
        pending.values
            .filterNot { entry ->
                alreadyDeployed(entry.name).also { deployed ->
                    if (deployed && pending.remove(entry.name) != null) {
                        log("[INFO] auto-deploy retry for ${entry.name} dropped: already deployed")
                    }
                }
            }.sortedBy { it.nextAttemptAtMs }

    /**
     * Run every retry due at [nowMs]. Returns the names that deployed. A name the operator
     * has since deployed by hand is dropped without a retry — the file on disk is theirs.
     */
    fun retryDue(nowMs: Long): List<String> {
        val deployed = ArrayList<String>()
        for (entry in pending.values.sortedBy { it.nextAttemptAtMs }) {
            if (entry.nextAttemptAtMs > nowMs) continue
            if (alreadyDeployed(entry.name)) {
                pending.remove(entry.name)
                log("[INFO] auto-deploy retry for ${entry.name} skipped: already deployed")
                continue
            }
            runCatching { deploy(entry.name, entry.file) }
                .onSuccess {
                    pending.remove(entry.name)
                    deployed += entry.name
                    log("[INFO] auto-deployed ${entry.name} from ${entry.file} on retry ${entry.attempts}")
                }.onFailure { e ->
                    val step = backoffMs[minOf(entry.attempts, backoffMs.size - 1)]
                    val message = e.message ?: e::class.java.simpleName
                    pending[entry.name] =
                        entry.copy(attempts = entry.attempts + 1, lastError = message, nextAttemptAtMs = nowMs + step)
                    log(
                        "[WARN] auto-deploy retry ${entry.attempts} for ${entry.name} failed: $message; next in ${step / 1000}s",
                    )
                }
        }
        return deployed
    }

    /** Start the background loop; idempotent. Returns immediately when nothing is pending. */
    fun start() {
        if (pending.isEmpty() || !running.compareAndSet(false, true)) return
        thread =
            Thread({
                while (running.get() && pending.isNotEmpty()) {
                    runCatching { retryDue(clock.now()) }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                running.set(false)
            }, "qkt-auto-deploy-retry").apply { isDaemon = true }
        thread?.start()
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
    }

    companion object {
        /** 1m, 2m, 5m, then every 15m: quick enough for a boot blip, quiet enough for a 4h bar gap. */
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(60_000L, 120_000L, 300_000L, 900_000L)
        private const val POLL_MS: Long = 1_000L
    }
}
