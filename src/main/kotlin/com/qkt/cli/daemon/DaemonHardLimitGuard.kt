package com.qkt.cli.daemon

import com.qkt.app.DailyLimitReading
import com.qkt.app.LiveSessionHandle
import com.qkt.common.Money
import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltScope
import java.math.BigDecimal
import java.util.IdentityHashMap
import org.slf4j.LoggerFactory

/** One session the hard limit watches: its deploy [name] for the log, and its [live] handle. */
data class GuardedSession(
    val name: String,
    val live: LiveSessionHandle,
)

/**
 * The one writer of `risk.hard_daily_loss`: on its own thread, off every hot path, it adds the daemon's realized
 * P&L to each session's open P&L and, when the loss exceeds the limit, latches the halt in [scope] (persisted first),
 * halts every session and flattens it.
 *
 * - **No false flatten:** only fresh, valid marks count ([com.qkt.app.SessionLimitMarks]), and a breach that rests on
 *   open P&L must hold for two reads in a row. Realized P&L is booked, so past the limit alone it trips at once.
 * - **No flatten storm:** each session is flattened once. It is flattened again only while its ledger still holds legs
 *   with no close working, [RETRY_AFTER_MS] apart and at most [MAX_FLATTENS] times, then left to the operator with an error.
 * - **After a restart** every session is new here, so each gets its one flatten: a crash between the latch and the
 *   closes is finished, and a flat session sends nothing.
 *
 * A symbol with no fresh price cannot be measured, which fails towards not flattening, and is logged as such.
 */
class DaemonHardLimitGuard(
    private val scope: DaemonRiskScope,
    private val sessions: () -> List<GuardedSession>,
    private val pollMs: Long = 1_000L,
) : AutoCloseable {
    private class Flatten(
        var attempts: Int,
        var lastAtMs: Long,
        var gaveUp: Boolean = false,
    )

    private val log = LoggerFactory.getLogger(DaemonHardLimitGuard::class.java)
    private val flattens = IdentityHashMap<LiveSessionHandle, Flatten>()
    private val warned = HashSet<String>()
    private var overOnLastRead = false

    @Volatile private var thread: Thread? = null

    /** Starts the polling thread; does nothing with the limit off. */
    fun start() {
        if (scope.hardDailyLoss == null) return
        thread =
            Thread({
                while (!Thread.currentThread().isInterrupted) {
                    runCatching { checkOnce() }.onFailure { log.error("hard_daily_loss check failed: {}", it.message) }
                    try {
                        Thread.sleep(pollMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "qkt-daemon-hard-limit").apply {
                isDaemon = true
                start()
            }
    }

    /** One pass: read every session, trip if the limit is crossed, enforce if it stands. */
    @Synchronized
    fun checkOnce() {
        val limit = scope.hardDailyLoss ?: return
        val running = sessions().filter { it.live.running }
        val readings = running.associateWith(::readingOf)
        for (reading in readings.values) reading?.let { scope.realizedToday(it.sessionId, it.realizedToday) }
        warnUnmeasured(running, readings)
        if (scope.hardHaltReason() == null) {
            val realized = scope.totalToday()
            val open = readings.values.filterNotNull().fold(Money.ZERO) { acc, r -> acc.add(r.open) }
            judge(limit, realized, open)
        }
        val reason = scope.hardHaltReason()
        if (reason == null) {
            flattens.clear()
            return
        }
        for (session in running) enforce(session, reason, readings[session])
    }

    private fun judge(
        limit: BigDecimal,
        realized: BigDecimal,
        open: BigDecimal,
    ) {
        val loss = realized.add(open).negate()
        val over = loss > limit
        if (over && (overOnLastRead || realized.negate() > limit)) {
            val reason =
                "hard_daily_loss: loss ${loss.toPlainString()} exceeds $limit (realized ${realized.toPlainString()}, " +
                    "open ${open.toPlainString()}), " +
                    "flattened and halted until the next UTC day"
            log.error("{}", reason)
            scope.latchHardHalt(reason)
        }
        overOnLastRead = over
    }

    private fun enforce(
        session: GuardedSession,
        reason: String,
        reading: DailyLimitReading?,
    ) {
        session.live.halt(reason, HaltScope.DAILY)
        val now = scope.clock.now()
        val flatten = flattens[session.live]
        if (flatten == null) {
            flattens[session.live] = Flatten(1, now)
            session.live.flatten()
            return
        }
        val stuck = reading != null && reading.openLegs > 0 && !reading.closing
        if (!stuck || flatten.gaveUp || now - flatten.lastAtMs < RETRY_AFTER_MS) return
        if (flatten.attempts >= MAX_FLATTENS) {
            flatten.gaveUp = true
            log.error(
                "hard_daily_loss: {} still holds {} leg(s) after {} flattens; close them at the venue",
                session.name,
                reading?.openLegs,
                flatten.attempts,
            )
            return
        }
        flatten.attempts++
        flatten.lastAtMs = now
        log.error("hard_daily_loss: {} still holds positions, flattening again", session.name)
        session.live.flatten()
    }

    private fun readingOf(session: GuardedSession): DailyLimitReading? =
        try {
            session.live.limitReading().also { warned.remove("session ${session.name}") }
        } catch (e: IllegalStateException) {
            if (warned.add("session ${session.name}")) {
                log.warn(
                    "hard_daily_loss: {} did not answer ({}); its open P&L counts as nothing until it does, " +
                        "so a real loss there cannot trigger the flatten",
                    session.name,
                    e.message,
                )
            }
            null
        }

    private fun warnUnmeasured(
        running: List<GuardedSession>,
        readings: Map<GuardedSession, DailyLimitReading?>,
    ) {
        val seen = HashSet<String>()
        for (session in running) {
            for ((symbol, why) in readings[session]?.unmeasured.orEmpty()) {
                val key = "${session.name} $symbol"
                seen += key
                if (warned.add(key)) {
                    log.warn(
                        "hard_daily_loss: {} holds {} with no usable mark ({}); its open P&L counts as nothing " +
                            "until a fresh price arrives, so a real loss there cannot trigger the flatten",
                        session.name,
                        symbol,
                        why,
                    )
                }
            }
        }
        warned.removeIf { !it.startsWith("session ") && it !in seen }
    }

    override fun close() {
        thread?.interrupt()
        thread?.join(STOP_WAIT_MS)
    }

    private companion object {
        const val RETRY_AFTER_MS = 30_000L
        const val MAX_FLATTENS = 3
        const val STOP_WAIT_MS = 2_000L
    }
}
