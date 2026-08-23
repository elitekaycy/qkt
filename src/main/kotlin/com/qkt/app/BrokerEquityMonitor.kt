package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.common.Clock
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/** Retrying, last-known-good account-equity sampler used by live drawdown controls. */
internal class BrokerEquityMonitor(
    private val broker: Broker,
    private val clock: Clock,
    private val equity: AtomicReference<BigDecimal?>,
    private val staleAfterMs: Long,
    private val onStale: (consecutiveFailures: Int, staleForMs: Long?) -> Unit,
) {
    private val log = LoggerFactory.getLogger(BrokerEquityMonitor::class.java)
    private var consecutiveFailures = 0
    private var lastSuccessAtMs: Long? = null
    private var staleAlerted = false

    init {
        require(staleAfterMs >= 0L) { "staleAfterMs must be >= 0: $staleAfterMs" }
    }

    fun tick() {
        val now = clock.now()
        // Equity cannot move while the venue is closed; keep the last sample instead of
        // polling an idle account every few seconds all weekend. A session that starts
        // closed still takes its first sample so drawdown controls have a basis.
        if (lastSuccessAtMs != null && !broker.marketOpen(now)) return
        val observed = runCatching { broker.accountEquity() }.getOrNull()
        if (observed != null) {
            if (staleAlerted) {
                log.info(
                    "Broker equity for {} recovered after {} consecutive failures",
                    broker.name,
                    consecutiveFailures,
                )
            }
            equity.set(observed)
            lastSuccessAtMs = now
            consecutiveFailures = 0
            staleAlerted = false
            return
        }

        consecutiveFailures++
        val staleForMs = lastSuccessAtMs?.let { (now - it).coerceAtLeast(0L) }
        val isStale = staleForMs == null || staleForMs >= staleAfterMs
        if (consecutiveFailures >= FAILURE_ALERT_THRESHOLD && isStale && !staleAlerted) {
            staleAlerted = true
            equity.set(null)
            log.error(
                "Broker equity for {} unavailable for {} consecutive polls (staleForMs={})",
                broker.name,
                consecutiveFailures,
                staleForMs?.toString() ?: "never-sampled",
            )
            onStale(consecutiveFailures, staleForMs)
        }
    }

    private companion object {
        const val FAILURE_ALERT_THRESHOLD = 3
    }
}
